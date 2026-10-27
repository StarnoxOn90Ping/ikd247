package ani.dantotsu.others.offline

import android.content.Context
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.MediaList
import ani.dantotsu.connections.anilist.api.Query
import ani.dantotsu.connections.anilist.fullMediaInformation
import ani.dantotsu.media.Media
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

enum class OfflinePhase { LISTS, DETAILS, EPISODES, IMAGES, CLEANUP }

data class OfflineProgress(val phase: OfflinePhase, val done: Int, val total: Int)

data class OfflineRunStats(
    val titles: Int = 0,
    val detailsFetched: Int = 0,
    val episodeInfoFetched: Int = 0,
    val imagesDownloaded: Int = 0,
    val failures: Int = 0,
    val resumed: Boolean = false,
)

sealed class OfflineRunOutcome {
    data class Completed(val stats: OfflineRunStats) : OfflineRunOutcome()

    /** Stopped before finishing (rate limit, no network ...). Safe to run again: it resumes. */
    data class Interrupted(val reason: String) : OfflineRunOutcome()

    /** Nothing to do right now (feature off, not logged in to AniList). Not an error. */
    data class NotReady(val reason: String) : OfflineRunOutcome()

    /** Another run is already going in this process. */
    object Busy : OfflineRunOutcome()
}

/**
 * The preload engine. One call to [run] does a full incremental pass:
 *
 *  1. LISTS    your AniList lists + favourites for the ticked categories (a few requests per
 *              media type, chunks of 500). Updates every title's list state and fingerprint, adds
 *              new titles, drops titles that left your lists. Image URLs here come from the list.
 *  2. DETAILS  full AniList details only for titles that are new, whose fingerprint changed, or
 *              (still airing/announced) whose details are older than 7 days.
 *  3. EPISODES (only with "Include episode info") Kitsu/Jikan/TMDB data for anime that are new,
 *              changed, or still airing and older than 7 days. Finished anime are fetched once.
 *  4. IMAGES   every image URL not yet on disk: covers/banners first, then character/staff photos,
 *              then episode thumbnails (3 downloads at a time, each throttled per host).
 *  5. CLEANUP  removes images nothing uses any more and stamps the sync time.
 *
 * Incremental: each step looks at what is already stored (fingerprints, stamps, files on disk)
 * and skips whatever is current, so a second run right after the first sends almost no requests.
 *
 * Resumable: results are written as they arrive (index flushed every [FLUSH_EVERY] titles and in
 * a `finally`), and "what is left" is recomputed from what is on disk, so a run cut short by
 * WorkManager, a lost connection, a rate limit or a killed app continues where it stopped. The
 * saved [OfflineJobState] only carries run identity and numbers for the UI.
 *
 * Throttling: every request goes through [OfflineRateLimiter] (see its table). AniList requests
 * additionally wait out `Anilist.rateLimitReset` if the app-wide limiter tripped.
 *
 * Nothing is deleted before its replacement is saved, and nothing is deleted because of age.
 */
object OfflinePreloadEngine {
    private const val FLUSH_EVERY = 15
    private const val REFRESH_AFTER_MS = 7L * 24 * 60 * 60 * 1000
    private const val IMAGE_PARALLELISM = 3
    private const val MAX_CONSECUTIVE_FAILURES = 6
    private const val LIST_CHUNK = 500
    private val ACTIVE_STATUSES = setOf("RELEASING", "NOT_YET_RELEASED", "HIATUS")

    private val runLock = Mutex()

    suspend fun run(
        context: Context,
        onProgress: (OfflineProgress) -> Unit = {},
    ): OfflineRunOutcome {
        if (!runLock.tryLock()) return OfflineRunOutcome.Busy
        try {
            return runLocked(context, onProgress)
        } finally {
            runLock.unlock()
        }
    }

    private suspend fun runLocked(
        context: Context,
        onProgress: (OfflineProgress) -> Unit,
    ): OfflineRunOutcome {
        if (!OfflineCache.isEnabled()) return OfflineRunOutcome.NotReady("Offline preload is off")
        val categories = OfflineCache.enabledCategories()
        if (categories.isEmpty()) {
            OfflineCache.pruneToEnabledCategories(categories)
            return OfflineRunOutcome.NotReady("No categories selected")
        }
        Anilist.getSavedToken()
        val userId = runCatching { PrefManager.getVal<String>(PrefName.AnilistUserId) }
            .getOrDefault("").toIntOrNull()
        if (Anilist.token.isNullOrEmpty() || userId == null) {
            return OfflineRunOutcome.NotReady("Not logged in to AniList")
        }

        // New run, or continue the one that was cut short.
        val previous = OfflineCache.getJobState()
        val resumed = previous.status == OfflineJobStatus.RUNNING ||
            previous.status == OfflineJobStatus.INTERRUPTED
        val now = System.currentTimeMillis()
        var state = if (resumed) {
            previous.copy(status = OfflineJobStatus.RUNNING, resumeCount = previous.resumeCount + 1, lastError = null, updatedAt = now)
        } else {
            OfflineJobState(runId = now, startedAt = now, updatedAt = now, status = OfflineJobStatus.RUNNING,
                lastCompletedAt = previous.lastCompletedAt, lastTitleCount = previous.lastTitleCount)
        }
        OfflineCache.putJobState(state)

        fun checkpoint(phase: OfflinePhase, done: Int, total: Int) {
            state = state.copy(phase = phase.name, done = done, total = total, updatedAt = System.currentTimeMillis())
            OfflineCache.flushIndex()
            OfflineCache.putJobState(state)
        }

        var stats = OfflineRunStats(resumed = resumed)
        try {
            // 1. LISTS
            onProgress(OfflineProgress(OfflinePhase.LISTS, 0, 0))
            checkpoint(OfflinePhase.LISTS, 0, 0)
            syncLists(userId, categories)
            OfflineCache.backfillMetadata()
            stats = stats.copy(titles = OfflineCache.titleCount())

            // 2. DETAILS
            val detailsTodo = OfflineCache.allEntries().filter { needsDetails(it) }.sortedBy { priority(it) }
            var consecutiveFailures = 0
            detailsTodo.forEachIndexed { i, e ->
                currentCoroutineContext().ensureActive()
                onProgress(OfflineProgress(OfflinePhase.DETAILS, i, detailsTodo.size))
                val resp = aniListQuery<Query.Media>(fullMediaInformation(e.mediaId))
                val media = resp?.data?.media
                if (resp == null || media == null) {
                    stats = stats.copy(failures = stats.failures + 1)
                    if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        throw OfflineNetworkException("AniList is not answering")
                    }
                    return@forEachIndexed
                }
                consecutiveFailures = 0
                OfflineCache.putDetails(
                    e.mediaId, resp, detailImageUrls(media), e.fingerprint, persist = false
                )
                stats = stats.copy(detailsFetched = stats.detailsFetched + 1)
                if ((i + 1) % FLUSH_EVERY == 0) checkpoint(OfflinePhase.DETAILS, i + 1, detailsTodo.size)
            }
            checkpoint(OfflinePhase.DETAILS, detailsTodo.size, detailsTodo.size)

            // 3. EPISODES
            if (OfflineCache.includeEpisodeInfo()) {
                val epTodo = OfflineCache.allEntries()
                    .filter { it.type == OfflineCacheCategory.ANIME && needsEpisodes(it) }
                    .sortedBy { priority(it) }
                epTodo.forEachIndexed { i, e ->
                    currentCoroutineContext().ensureActive()
                    onProgress(OfflineProgress(OfflinePhase.EPISODES, i, epTodo.size))
                    val info = OfflineEpisodeFetcher.fetch(e)
                    if (info == null) {
                        stats = stats.copy(failures = stats.failures + 1)
                    } else {
                        OfflineCache.putEpisodeInfo(info, e.fingerprint, persist = false)
                        stats = stats.copy(episodeInfoFetched = stats.episodeInfoFetched + 1)
                    }
                    if ((i + 1) % FLUSH_EVERY == 0) checkpoint(OfflinePhase.EPISODES, i + 1, epTodo.size)
                }
                checkpoint(OfflinePhase.EPISODES, epTodo.size, epTodo.size)
            }

            // 4. IMAGES
            val entries = OfflineCache.allEntries()
            val ordered = (
                entries.flatMap { listOfNotNull(it.coverUrl, it.bannerUrl, it.clearLogoUrl) } +
                    entries.flatMap { it.imageUrls } +
                    entries.flatMap { it.episodeImageUrls }
                ).filter { it.startsWith("http") }.distinct().filter { !OfflineCache.hasImage(it) }
            val imgDone = AtomicInteger(0)
            val imgOk = AtomicInteger(0)
            val imgFail = AtomicInteger(0)
            val imgStreakFail = AtomicInteger(0)
            onProgress(OfflineProgress(OfflinePhase.IMAGES, 0, ordered.size))
            checkpoint(OfflinePhase.IMAGES, 0, ordered.size)
            val sem = Semaphore(IMAGE_PARALLELISM)
            coroutineScope {
                ordered.map { url ->
                    async(Dispatchers.IO) {
                        sem.withPermit {
                            ensureActive()
                            val ok = OfflineHttp.downloadImage(url)
                            if (ok) { imgOk.incrementAndGet(); imgStreakFail.set(0) }
                            else {
                                imgFail.incrementAndGet()
                                if (imgStreakFail.incrementAndGet() >= MAX_CONSECUTIVE_FAILURES * 2 && imgOk.get() == 0) {
                                    throw OfflineNetworkException("Image servers are not answering")
                                }
                            }
                            val d = imgDone.incrementAndGet()
                            if (d % 10 == 0 || d == ordered.size) {
                                onProgress(OfflineProgress(OfflinePhase.IMAGES, d, ordered.size))
                            }
                        }
                    }
                }.awaitAll()
            }
            stats = stats.copy(imagesDownloaded = imgOk.get(), failures = stats.failures + imgFail.get())

            // 5. CLEANUP
            onProgress(OfflineProgress(OfflinePhase.CLEANUP, 0, 0))
            OfflineCache.sweepOrphanImages()
            OfflineCache.flushIndex()
            OfflineCache.markSynced()
            val done = System.currentTimeMillis()
            state = state.copy(
                status = OfflineJobStatus.COMPLETE, phase = OfflinePhase.CLEANUP.name,
                updatedAt = done, lastCompletedAt = done, lastTitleCount = OfflineCache.titleCount(),
                lastError = null,
            )
            OfflineCache.putJobState(state)
            return OfflineRunOutcome.Completed(stats)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { interrupted(state, "Stopped") }
            throw e
        } catch (e: OfflineRateLimitedException) {
            withContext(NonCancellable) { interrupted(state, e.message ?: "Rate limited") }
            return OfflineRunOutcome.Interrupted(e.message ?: "Rate limited")
        } catch (e: OfflineNetworkException) {
            withContext(NonCancellable) { interrupted(state, e.message ?: "Network error") }
            return OfflineRunOutcome.Interrupted(e.message ?: "Network error")
        } catch (e: Exception) {
            Logger.log(e)
            withContext(NonCancellable) { interrupted(state, e.message ?: "Error") }
            return OfflineRunOutcome.Interrupted(e.message ?: "Error")
        }
    }

    private fun interrupted(state: OfflineJobState, reason: String) {
        OfflineCache.flushIndex()
        OfflineCache.putJobState(
            state.copy(status = OfflineJobStatus.INTERRUPTED, lastError = reason, updatedAt = System.currentTimeMillis())
        )
    }

    // ---- incremental rules ------------------------------------------------------------------

    private fun priority(e: OfflineEntry): Int =
        e.categories.mapNotNull { OfflineCacheCategory.fromKey(it)?.ordinal }.minOrNull() ?: 99

    private fun isStale(e: OfflineEntry, stampedAt: Long?): Boolean =
        e.mediaStatus in ACTIVE_STATUSES &&
            System.currentTimeMillis() - (stampedAt ?: 0L) > REFRESH_AFTER_MS

    private fun needsDetails(e: OfflineEntry): Boolean =
        !OfflineCache.hasDetails(e.mediaId) ||
            e.detailsFingerprint != e.fingerprint ||
            isStale(e, e.detailsCachedAt)

    private fun needsEpisodes(e: OfflineEntry): Boolean =
        !e.hasEpisodeInfo ||
            e.episodeFingerprint != e.fingerprint ||
            isStale(e, e.episodeInfoCachedAt)

    // ---- phase 1: lists ---------------------------------------------------------------------

    private suspend fun syncLists(userId: Int, categories: Set<OfflineCacheCategory>) {
        val types = categories.flatMap { it.types }.toSet()
        val wantFavourites = categories.any { it.isFavourites }
        val existing = OfflineCache.allEntries().associateBy { it.mediaId }

        val listEntries = linkedMapOf<Int, Pair<Media, MediaList?>>() // id -> media + its list entry
        val favourites = linkedMapOf<Int, Pair<Media, Int?>>()          // id -> media + favourite order
        val completeTypes = mutableSetOf<String>()

        for (type in types) {
            val rows = fetchListEntries(userId, type) ?: continue
            var typeComplete = true
            for (row in rows) {
                val media = runCatching { Media(row) }.getOrNull() ?: continue
                listEntries.putIfAbsent(media.id, media to row)
            }
            if (wantFavourites) {
                val favs = fetchFavourites(userId, type)
                if (favs == null) typeComplete = false else favs.forEach { (m, order) -> favourites[m.id] = m to order }
            }
            if (typeComplete) completeTypes += type
        }

        val fresh = mutableListOf<OfflineEntry>()
        val ids = (listEntries.keys + favourites.keys).toSet()
        for (id in ids) {
            val fromList = listEntries[id]
            val fav = favourites[id]
            val media = fromList?.first ?: fav!!.first
            val type = if (media.manga != null && media.anime == null) OfflineCacheCategory.MANGA else OfflineCacheCategory.ANIME
            val status = media.userStatus
            val cats = categories.filter { c ->
                type in c.types && ((status != null && status in c.listStatuses) || (c.isFavourites && (fav != null || media.isFav)))
            }.toSet()
            if (cats.isEmpty()) continue
            var base = media.toOfflineEntry(cats, existing = existing[id])
            fromList?.second?.let { row ->
                base = base.copy(listId = row.id, notes = row.notes, repeat = row.repeat ?: 0)
            }
            fav?.second?.let { order -> base = base.copy(favouriteOrder = order, isFavourite = true) }
            fresh += base
        }
        OfflineCache.upsertEntries(fresh, replaceCategories = true)

        // Titles that left your lists, for the media types we fetched completely.
        val freshIds = fresh.map { it.mediaId }.toSet()
        val gone = existing.values.filter { it.type in completeTypes && it.mediaId !in freshIds }.map { it.mediaId }
        OfflineCache.removeEntries(gone)
        OfflineCache.pruneToEnabledCategories(categories)
    }

    private suspend fun fetchListEntries(userId: Int, type: String): List<MediaList>? {
        val out = mutableListOf<MediaList>()
        var chunk = 1
        while (true) {
            val q = """{ MediaListCollection(userId: $userId, type: $type, chunk: $chunk, perChunk: $LIST_CHUNK) { hasNextChunk lists { name isCustomList entries { id status progress progressVolumes repeat private notes score(format:POINT_100) updatedAt startedAt{year month day} completedAt{year month day} media { id idMal isAdult type status chapters volumes episodes nextAiringEpisode{episode} bannerImage meanScore isFavourite format coverImage{large} startDate{year month day} title{english romaji userPreferred} } } } } }"""
            val res = aniListQuery<Query.MediaListCollection>(q) ?: return null
            val coll = res.data?.mediaListCollection ?: return null
            coll.lists?.filter { it.isCustomList != true }?.forEach { g -> g.entries?.let { out += it } }
            if (coll.hasNextChunk != true) break
            chunk++
        }
        return out
    }

    private suspend fun fetchFavourites(userId: Int, type: String): List<Pair<Media, Int?>>? {
        val field = if (type == OfflineCacheCategory.ANIME) "anime" else "manga"
        val out = mutableListOf<Pair<Media, Int?>>()
        var page = 1
        while (true) {
            val q = """{ User(id:$userId){ id favourites{ $field(page:$page, perPage:25){ pageInfo{ hasNextPage } edges{ favouriteOrder node{ id idMal isAdult type status chapters volumes episodes nextAiringEpisode{episode} bannerImage meanScore isFavourite format coverImage{large} startDate{year month day} title{english romaji userPreferred} mediaListEntry{ progress private score(format:POINT_100) status } } } } } } }"""
            val res = aniListQuery<Query.User>(q) ?: return null
            val conn = res.data.user?.favourites?.let { if (type == OfflineCacheCategory.ANIME) it.anime else it.manga } ?: return null
            conn.edges?.forEach { edge ->
                val node = edge.node ?: return@forEach
                val m = runCatching { Media(node) }.getOrNull() ?: return@forEach
                m.isFav = true
                out += m to edge.favouriteOrder
            }
            if (conn.pageInfo?.hasNextPage != true) break
            page++
        }
        return out
    }

    // ---- AniList plumbing -------------------------------------------------------------------

    /** One throttled AniList request with rate-limit waiting. Null = failed (not rate limited). */
    private suspend inline fun <reified T : Any> aniListQuery(query: String): T? {
        for (attempt in 1..3) {
            waitForAniListReset()
            OfflineRateLimiter.acquire(OfflineRateLimiter.ANILIST_HOST)
            val result = Anilist.executeQuery<T>(query, force = true)
            if (result != null) return result
            val err = Anilist.lastError.orEmpty()
            if (err.startsWith("Rate limited")) {
                waitForAniListReset()
                continue
            }
            if (err.contains("Invalid token", true) || err.contains("disabled", true)) return null
            delay(2000L * attempt)
        }
        return null
    }

    /** Sleeps until the app-wide AniList limiter (set on a 429) has expired; too long = pause the run. */
    private suspend fun waitForAniListReset() {
        val waitSec = Anilist.rateLimitReset - System.currentTimeMillis() / 1000
        if (waitSec <= 0) return
        if (waitSec > 90) throw OfflineRateLimitedException(OfflineRateLimiter.ANILIST_HOST, waitSec)
        delay((waitSec + 1) * 1000)
    }

    /** Character, voice actor and staff photos found in a details response. */
    private fun detailImageUrls(m: ani.dantotsu.connections.anilist.api.Media): Set<String> {
        val out = linkedSetOf<String>()
        fun add(u: String?) { if (!u.isNullOrBlank() && u.startsWith("http")) out += u }
        m.characters?.edges?.forEach { edge ->
            add(edge.node?.image?.large ?: edge.node?.image?.medium)
            edge.voiceActors?.forEach { add(it.image?.large ?: it.image?.medium) }
        }
        m.staff?.edges?.forEach { add(it.node?.image?.large ?: it.node?.image?.medium) }
        return out
    }
}
