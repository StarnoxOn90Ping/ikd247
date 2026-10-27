package ani.dantotsu.others.offline

import android.content.Context
import ani.dantotsu.Mapper
import ani.dantotsu.connections.anilist.api.Query
import ani.dantotsu.currContext
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Permanent on-device store for "Preload Cache for Offline Mode".
 *
 * Storage rules (the whole point of this object):
 *  - Lives in `filesDir/offline_cache/`, NOT in `cacheDir`. Android's "clear cache", the app's
 *    own "Clear cache" button (which wipes cacheDir/externalCacheDir) and low-storage cleanup
 *    never touch it. It is not part of Glide's disk cache and not part of SmartCache's size limit.
 *  - No expiry. Nothing here compares a timestamp to decide to delete. Files go away only when
 *    a title leaves every enabled category ([pruneToEnabledCategories]) or your list, the feature
 *    is turned off with "delete" chosen, or the user presses "Delete offline cache" ([clearAll]).
 *    (Refreshing old data is the engine's job and never deletes before the new copy is saved.)
 *
 * Layout:
 *  offline_cache/entries.json          manifest ([OfflineIndex])
 *  offline_cache/job.json              [OfflineJobState] of the last/current preload run
 *  offline_cache/details/<id>.json     full AniList details (`Query.Media`, like MediaDetailsCache)
 *  offline_cache/episodes/<id>.json    [OfflineEpisodeInfo], only when "Include episode info" is on
 *  offline_cache/images/<sha1(url)>    covers, banners, logos, character/staff photos, thumbnails
 *
 * This object only reads/writes disk. It does no networking.
 * All index changes go through [lock]; file writes are atomic (temp file + rename).
 * Index writes can be deferred ([persist] = false) for bulk work; call [flushIndex] regularly
 * and at the end. The engine does so every few titles and in a `finally`.
 */
object OfflineCache {
    private const val ROOT = "offline_cache"
    private const val INDEX_FILE = "entries.json"
    private const val JOB_FILE = "job.json"
    private const val DETAILS = "details"
    private const val EPISODES = "episodes"
    private const val IMAGES = "images"

    private val lock = Any()
    @Volatile private var index: OfflineIndex? = null
    private var dirty = false
    @Volatile private var jobState: OfflineJobState? = null

    // ---- Settings (preferences) -------------------------------------------------------------

    fun isEnabled(): Boolean =
        runCatching { PrefManager.getVal<Boolean>(PrefName.OfflinePreloadEnabled) }.getOrDefault(false)

    fun includeEpisodeInfo(): Boolean =
        runCatching { PrefManager.getVal<Boolean>(PrefName.OfflinePreloadEpisodeInfo) }.getOrDefault(false)

    fun wifiOnly(): Boolean =
        runCatching { PrefManager.getVal<Boolean>(PrefName.OfflinePreloadWifiOnly) }.getOrDefault(true)

    fun autoRefresh(): Boolean =
        runCatching { PrefManager.getVal<Boolean>(PrefName.OfflinePreloadAutoRefresh) }.getOrDefault(false)

    /** The categories the user ticked. Unknown/excluded keys are ignored. */
    fun enabledCategories(): Set<OfflineCacheCategory> = OfflineCacheCategory.selected(
        runCatching { PrefManager.getVal<Set<String>>(PrefName.OfflinePreloadCategories) }
            .getOrDefault(OfflineCacheCategory.DEFAULT_KEYS)
    )

    fun setEnabledCategories(categories: Set<OfflineCacheCategory>) {
        PrefManager.setVal(PrefName.OfflinePreloadCategories, categories.map { it.key }.toSet())
    }

    // ---- Paths ------------------------------------------------------------------------------

    @Volatile private var appContext: Context? = null

    /** Gives the store a context that is available before any activity (Glide, workers). */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun root(context: Context? = null): File? {
        val base = (context ?: appContext ?: currContext())?.filesDir ?: return null
        return File(base, ROOT).apply { mkdirs() }
    }

    private fun sub(name: String, context: Context? = null): File? =
        root(context)?.let { File(it, name).apply { mkdirs() } }

    private fun hash(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun writeAtomic(target: File, write: (File) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            write(tmp)
            if (tmp.length() > 0 || target.extension == "json") {
                if (!tmp.renameTo(target)) {
                    target.delete()
                    tmp.renameTo(target)
                }
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    // ---- Index ------------------------------------------------------------------------------

    private fun loadIndex(): OfflineIndex {
        index?.let { return it }
        val loaded = runCatching {
            val f = File(root() ?: return OfflineIndex(), INDEX_FILE)
            if (f.exists()) Mapper.json.decodeFromString<OfflineIndex>(f.readText()) else OfflineIndex()
        }.onFailure { Logger.log(it) }.getOrDefault(OfflineIndex())
        index = loaded
        return loaded
    }

    /** Must be called with [lock] held. */
    private fun saveIndex(new: OfflineIndex, persist: Boolean = true) {
        index = new
        dirty = true
        if (persist) flushLocked()
    }

    private fun flushLocked() {
        if (!dirty) return
        val cur = index ?: return
        runCatching {
            val f = File(root() ?: return, INDEX_FILE)
            writeAtomic(f) { it.writeText(Mapper.json.encodeToString(cur)) }
            dirty = false
        }.onFailure { Logger.log(it) }
    }

    /** Writes any deferred index changes to disk. */
    fun flushIndex() = synchronized(lock) { flushLocked() }

    fun lastSyncAt(): Long = synchronized(lock) { loadIndex().lastSyncAt }

    fun markSynced(time: Long = System.currentTimeMillis()) = synchronized(lock) {
        saveIndex(loadIndex().copy(lastSyncAt = time))
    }

    fun getEntry(mediaId: Int): OfflineEntry? =
        synchronized(lock) { loadIndex().entries[mediaId.toString()] }

    fun allEntries(): List<OfflineEntry> =
        synchronized(lock) { loadIndex().entries.values.toList() }

    fun entriesIn(category: OfflineCacheCategory): List<OfflineEntry> =
        allEntries().filter { category.key in it.categories }

    fun titleCount(): Int = synchronized(lock) { loadIndex().entries.size }

    /**
     * Adds or refreshes titles. If a title is already stored, its fetched-data stamps are kept.
     * Categories are merged unless [replaceCategories] is true (the list sync passes true, because
     * it knows the full set).
     */
    fun upsertEntries(
        entries: Collection<OfflineEntry>,
        replaceCategories: Boolean = false,
        persist: Boolean = true,
    ) {
        if (entries.isEmpty()) return
        synchronized(lock) {
            val map = loadIndex().entries.toMutableMap()
            for (e in entries) {
                val old = map[e.mediaId.toString()]
                map[e.mediaId.toString()] = if (old == null) e else e.copy(
                    categories = if (replaceCategories) e.categories else old.categories + e.categories,
                    imageUrls = old.imageUrls + e.imageUrls,
                    episodeImageUrls = old.episodeImageUrls + e.episodeImageUrls,
                    clearLogoUrl = e.clearLogoUrl ?: old.clearLogoUrl,
                    detailsCachedAt = e.detailsCachedAt ?: old.detailsCachedAt,
                    episodeInfoCachedAt = e.episodeInfoCachedAt ?: old.episodeInfoCachedAt,
                    detailsFingerprint = e.detailsFingerprint ?: old.detailsFingerprint,
                    episodeFingerprint = e.episodeFingerprint ?: old.episodeFingerprint,
                )
            }
            saveIndex(loadIndex().copy(entries = map), persist)
        }
    }

    /** Changes one stored title. Returns the new value, or null if the title isn't stored. */
    fun updateEntry(
        mediaId: Int,
        persist: Boolean = true,
        transform: (OfflineEntry) -> OfflineEntry,
    ): OfflineEntry? = synchronized(lock) {
        val cur = loadIndex()
        val old = cur.entries[mediaId.toString()] ?: return null
        val new = transform(old)
        saveIndex(cur.copy(entries = cur.entries + (mediaId.toString() to new)), persist)
        new
    }

    // ---- Details (full AniList response) ----------------------------------------------------

    fun hasDetails(mediaId: Int): Boolean =
        File(sub(DETAILS) ?: return false, "$mediaId.json").exists()

    fun getDetails(mediaId: Int): Query.Media? = runCatching {
        val f = File(sub(DETAILS) ?: return null, "$mediaId.json")
        if (f.exists()) Mapper.json.decodeFromString<Query.Media>(f.readText()) else null
    }.getOrNull()

    /**
     * Saves the details file, then records in the index that it exists: [imageUrls] are the
     * character/staff photos found in it and [fingerprint] is the title's fingerprint at fetch time.
     */
    fun putDetails(
        mediaId: Int,
        response: Query.Media,
        imageUrls: Set<String> = emptySet(),
        fingerprint: String? = null,
        now: Long = System.currentTimeMillis(),
        persist: Boolean = true,
    ) {
        runCatching {
            val f = File(sub(DETAILS) ?: return, "$mediaId.json")
            writeAtomic(f) { it.writeText(Mapper.json.encodeToString(response)) }
            val m = response.data?.media
            updateEntry(mediaId, persist) {
                it.copy(
                    detailsCachedAt = now,
                    detailsFingerprint = fingerprint ?: it.fingerprint,
                    imageUrls = imageUrls,
                    genres = m?.genres?.filterNotNull().orEmpty().ifEmpty { it.genres },
                    tags = m?.tags?.map { t -> t.name }.orEmpty().ifEmpty { it.tags },
                    tagsIsSpoiler = m?.tags?.map { t -> t.isMediaSpoiler == true }.orEmpty().ifEmpty { it.tagsIsSpoiler },
                )
            }
        }.onFailure { Logger.log(it) }
    }

    /**
     * Titles preloaded before genres/tags were stored get them from their details file (local read,
     * no network), so offline list filtering works without refetching everything.
     */
    fun backfillMetadata() {
        val missing = allEntries().filter { it.hasDetails && it.genres.isEmpty() && it.tags.isEmpty() }
        if (missing.isEmpty()) return
        for (e in missing) {
            val m = getDetails(e.mediaId)?.data?.media ?: continue
            updateEntry(e.mediaId, persist = false) {
                it.copy(
                    genres = m.genres?.filterNotNull().orEmpty(),
                    tags = m.tags?.map { t -> t.name }.orEmpty(),
                    tagsIsSpoiler = m.tags?.map { t -> t.isMediaSpoiler == true }.orEmpty(),
                )
            }
        }
        flushIndex()
    }

    // ---- Episode info -----------------------------------------------------------------------

    fun getEpisodeInfo(mediaId: Int): OfflineEpisodeInfo? = runCatching {
        val f = File(sub(EPISODES) ?: return null, "$mediaId.json")
        if (f.exists()) Mapper.json.decodeFromString<OfflineEpisodeInfo>(f.readText()) else null
    }.getOrNull()

    fun putEpisodeInfo(
        info: OfflineEpisodeInfo,
        fingerprint: String? = null,
        persist: Boolean = true,
    ) {
        runCatching {
            val f = File(sub(EPISODES) ?: return, "${info.mediaId}.json")
            writeAtomic(f) { it.writeText(Mapper.json.encodeToString(info)) }
            val thumbs = info.episodes.mapNotNull { it.thumbnailUrl }.toSet()
            updateEntry(info.mediaId, persist) {
                it.copy(
                    episodeInfoCachedAt = info.cachedAt,
                    episodeFingerprint = fingerprint ?: it.fingerprint,
                    episodeImageUrls = thumbs,
                )
            }
        }.onFailure { Logger.log(it) }
    }

    // ---- Images -----------------------------------------------------------------------------

    /** The stored copy of [url], or null if it was never saved. */
    fun imageFile(url: String): File? {
        val f = File(sub(IMAGES) ?: return null, hash(url))
        return f.takeIf { it.exists() && it.length() > 0 }
    }

    fun hasImage(url: String): Boolean = imageFile(url) != null

    /** Saves [input] as the permanent copy of [url] (the engine streams downloads into this). */
    fun saveImage(url: String, input: InputStream): File? = runCatching {
        val target = File(sub(IMAGES) ?: return null, hash(url))
        writeAtomic(target) { tmp -> tmp.outputStream().use { input.copyTo(it) } }
        target.takeIf { it.length() > 0 }
    }.onFailure { Logger.log(it) }.getOrNull()

    // ---- Job state --------------------------------------------------------------------------

    fun getJobState(): OfflineJobState = synchronized(lock) {
        jobState ?: runCatching {
            val f = File(root() ?: return@runCatching OfflineJobState(), JOB_FILE)
            if (f.exists()) Mapper.json.decodeFromString<OfflineJobState>(f.readText()) else OfflineJobState()
        }.getOrDefault(OfflineJobState()).also { jobState = it }
    }

    fun putJobState(state: OfflineJobState) = synchronized(lock) {
        jobState = state
        runCatching {
            val f = File(root() ?: return@runCatching, JOB_FILE)
            writeAtomic(f) { it.writeText(Mapper.json.encodeToString(state)) }
        }.onFailure { Logger.log(it) }
    }

    // ---- Removal / pruning ------------------------------------------------------------------

    private fun deleteEntryFiles(mediaId: Int) {
        runCatching { File(sub(DETAILS) ?: return, "$mediaId.json").delete() }
        runCatching { File(sub(EPISODES) ?: return, "$mediaId.json").delete() }
    }

    /** Deletes image files no remaining title refers to. */
    private fun deleteOrphanImages(entries: Collection<OfflineEntry>) {
        val keep = entries.flatMap { it.allImageUrls() }.map { hash(it) }.toSet()
        sub(IMAGES)?.listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    fun removeEntry(mediaId: Int) = removeEntries(listOf(mediaId))

    /** Removes titles (with their details/episode files) and any image nothing else uses. */
    fun removeEntries(mediaIds: Collection<Int>) {
        if (mediaIds.isEmpty()) return
        synchronized(lock) {
            val cur = loadIndex()
            val keys = mediaIds.map { it.toString() }.toSet()
            if (cur.entries.keys.none { it in keys }) return
            val rest = cur.entries - keys
            saveIndex(cur.copy(entries = rest))
            mediaIds.forEach { deleteEntryFiles(it) }
            deleteOrphanImages(rest.values)
        }
    }

    /**
     * Drops titles that are in none of the currently enabled categories (and strips the
     * unticked categories from the rest), then removes orphaned images. Call this after the
     * user unticks a category. It never deletes anything because of age.
     */
    fun pruneToEnabledCategories(enabled: Set<OfflineCacheCategory> = enabledCategories()) {
        val keys = enabled.map { it.key }.toSet()
        synchronized(lock) {
            val cur = loadIndex()
            val kept = LinkedHashMap<String, OfflineEntry>()
            for ((id, e) in cur.entries) {
                val remaining = e.categories.filter { it in keys }.toSet()
                if (remaining.isEmpty()) deleteEntryFiles(e.mediaId) else kept[id] = e.copy(categories = remaining)
            }
            saveIndex(cur.copy(entries = kept))
            deleteOrphanImages(kept.values)
        }
    }

    /** Removes image files no stored title refers to (e.g. after a cover or thumbnail changed). */
    fun sweepOrphanImages() = synchronized(lock) { deleteOrphanImages(loadIndex().entries.values) }

    /** Deletes only the episode info (used when "Include episode info" is turned off). */
    fun clearEpisodeInfo() {
        synchronized(lock) {
            runCatching { sub(EPISODES)?.deleteRecursively() }
            val cur = loadIndex()
            saveIndex(
                cur.copy(
                    entries = cur.entries.mapValues { (_, e) ->
                        e.copy(episodeInfoCachedAt = null, episodeFingerprint = null, episodeImageUrls = emptySet())
                    }
                )
            )
            deleteOrphanImages(loadIndex().entries.values)
        }
    }

    /** Wipes everything (feature turned off, or the user chose "Delete offline cache"). */
    fun clearAll(context: Context? = null) {
        synchronized(lock) {
            runCatching { root(context)?.deleteRecursively() }
            index = null
            jobState = null
            dirty = false
        }
    }

    // ---- Usage ------------------------------------------------------------------------------

    private fun sizeOf(f: File?): Long =
        if (f == null || !f.exists()) 0L else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun usage(context: Context? = null): OfflineCacheUsage {
        val r = root(context)
        return OfflineCacheUsage(
            entriesBytes = sizeOf(r?.let { File(it, INDEX_FILE) }),
            detailsBytes = sizeOf(r?.let { File(it, DETAILS) }),
            episodesBytes = sizeOf(r?.let { File(it, EPISODES) }),
            imagesBytes = sizeOf(r?.let { File(it, IMAGES) }),
            titleCount = titleCount(),
        )
    }

    // ---- Hooks ------------------------------------------------------------------------------

    /** Called when the feature is switched on or the category selection changes. */
    fun onSettingsChanged() = OfflinePreloader.requestRun()
}

/**
 * Entry point the settings screen uses. [requestRun] starts (or restarts, resuming where the last
 * run stopped) a preload through WorkManager. See [OfflinePreloadScheduler].
 */
object OfflinePreloader {
    fun requestRun() {
        val ctx = currContext()?.applicationContext ?: return
        OfflinePreloadScheduler.runNow(ctx)
    }
}
