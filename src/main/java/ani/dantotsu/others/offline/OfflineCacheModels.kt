package ani.dantotsu.others.offline

import ani.dantotsu.connections.anilist.api.FuzzyDate
import ani.dantotsu.media.Media
import ani.dantotsu.media.anime.Anime
import ani.dantotsu.media.manga.Manga
import kotlinx.serialization.Serializable

/**
 * One title kept for offline use: its list state (progress, score, status ...), the light
 * metadata needed to draw cards, and the image URLs that belong to it.
 *
 * The full AniList details response is stored separately as `details/<mediaId>.json`
 * (same `Query.Media` JSON that MediaDetailsCache uses) and the optional episode info as
 * `episodes/<mediaId>.json`; [hasDetails] / [hasEpisodeInfo] say whether those files exist.
 *
 * Nothing here ever expires. [cachedAt] and friends are for "last updated" displays and for
 * deciding what to refresh, never for deleting.
 */
@Serializable
data class OfflineEntry(
    val mediaId: Int,
    /** "ANIME" or "MANGA". */
    val type: String,
    val idMal: Int? = null,

    val titleRomaji: String,
    val titleEnglish: String? = null,
    val titleUserPreferred: String,

    val coverUrl: String? = null,
    val bannerUrl: String? = null,
    val clearLogoUrl: String? = null,

    val isAdult: Boolean = false,
    val format: String? = null,
    /** AniList media status (RELEASING, FINISHED ...). */
    val mediaStatus: String? = null,
    val totalEpisodes: Int? = null,
    val totalChapters: Int? = null,
    val totalVolumes: Int? = null,
    val meanScore: Int? = null,

    // The user's own list state.
    val listId: Int? = null,
    /** AniList MediaListStatus, null if the title is only a favourite. */
    val listStatus: String? = null,
    val progress: Int? = null,
    val progressVolumes: Int? = null,
    val score: Int = 0,
    val repeat: Int = 0,
    val notes: String? = null,
    val isListPrivate: Boolean = false,
    val startedAt: FuzzyDate? = null,
    val completedAt: FuzzyDate? = null,
    val listUpdatedAt: Long? = null,
    val isFavourite: Boolean = false,
    val favouriteOrder: Int? = null,

    // Extra fields so list screens can sort, filter and draw cards offline.
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val tagsIsSpoiler: List<Boolean> = emptyList(),
    /** Last aired episode number (same meaning as Anime.nextAiringEpisode in the app). */
    val nextAiringEpisode: Int? = null,
    val startDate: FuzzyDate? = null,

    /** [OfflineCacheCategory.key]s this title belongs to. A title can be in several. */
    val categories: Set<String> = emptySet(),
    /** Character, voice-actor and staff photo URLs (filled in when the details are stored). */
    val imageUrls: Set<String> = emptySet(),
    /** Episode thumbnail URLs (filled in when the episode info is stored). */
    val episodeImageUrls: Set<String> = emptySet(),

    /**
     * Summary of the fields that mean "this title changed" (status, episode/chapter counts, next
     * airing episode, format, titles). Set from the list data on every run. Details and episode
     * info remember the fingerprint they were fetched at, so a title is refetched only when the
     * two differ. This is what makes runs incremental and resumable.
     */
    val fingerprint: String? = null,
    val detailsFingerprint: String? = null,
    val episodeFingerprint: String? = null,

    val cachedAt: Long = 0L,
    val detailsCachedAt: Long? = null,
    val episodeInfoCachedAt: Long? = null,
) {
    val hasDetails: Boolean get() = detailsCachedAt != null
    val hasEpisodeInfo: Boolean get() = episodeInfoCachedAt != null

    /** Cover/banner/logo first, then character/staff photos, then episode thumbnails. */
    fun allImageUrls(): Set<String> =
        (listOfNotNull(coverUrl, bannerUrl, clearLogoUrl) + imageUrls + episodeImageUrls)
            .filter { it.startsWith("http") }.toSet()
}

/** One episode's extra info (title, thumbnail, description ...), stored when "Include episode info" is on. */
@Serializable
data class OfflineEpisode(
    val number: String,
    val title: String? = null,
    val description: String? = null,
    val thumbnailUrl: String? = null,
    val filler: Boolean = false,
    val airDate: String? = null,
    val rating: String? = null,
)

@Serializable
data class OfflineEpisodeInfo(
    val mediaId: Int,
    val episodes: List<OfflineEpisode> = emptyList(),
    val cachedAt: Long = 0L,
)

/**
 * The manifest, saved as `entries.json`. Keyed by media id as a string (JSON map keys).
 * [version] is for future migrations. [lastSyncAt] is set by the network phase after a full run.
 */
@Serializable
data class OfflineIndex(
    val version: Int = 1,
    val lastSyncAt: Long = 0L,
    val entries: Map<String, OfflineEntry> = emptyMap(),
)

enum class OfflineJobStatus { IDLE, RUNNING, INTERRUPTED, COMPLETE }

/**
 * Saved as `job.json` so a run that was cut short can be continued. The real "where was I" lives
 * in the per-title fingerprints and in which image files exist; this only adds the run identity
 * and numbers for the UI. A new run starts when the last one is COMPLETE, otherwise it resumes.
 */
@Serializable
data class OfflineJobState(
    val runId: Long = 0L,
    val startedAt: Long = 0L,
    val updatedAt: Long = 0L,
    val status: OfflineJobStatus = OfflineJobStatus.IDLE,
    /** LISTS, DETAILS, EPISODES, IMAGES or CLEANUP. */
    val phase: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val resumeCount: Int = 0,
    val lastError: String? = null,
    val lastCompletedAt: Long = 0L,
    val lastTitleCount: Int = 0,
)

/** Sizes in bytes, for the settings screen. */
data class OfflineCacheUsage(
    val entriesBytes: Long,
    val detailsBytes: Long,
    val episodesBytes: Long,
    val imagesBytes: Long,
    val titleCount: Int,
) {
    val totalBytes: Long get() = entriesBytes + detailsBytes + episodesBytes + imagesBytes
}

fun Media.fingerprint(): String = listOf(
    status, anime?.totalEpisodes, manga?.totalChapters, manga?.totalVolumes,
    anime?.nextAiringEpisode, format, nameRomaji, name
).joinToString("|")

/**
 * Builds an [OfflineEntry] from the app's [Media] (what the list/home queries produce).
 * [categories] are the categories this title belongs to (the full set, not just the new one;
 * OfflineCache.upsert merges when a title shows up in several lists). [existing] keeps the details
 * and episode-info timestamps (and extra image URLs) when a title is refreshed.
 */
fun Media.toOfflineEntry(
    categories: Set<OfflineCacheCategory>,
    now: Long = System.currentTimeMillis(),
    existing: OfflineEntry? = null,
): OfflineEntry {
    val images = linkedSetOf<String>()
    fun add(url: String?) {
        if (!url.isNullOrBlank() && url.startsWith("http")) images.add(url)
    }
    characters?.forEach { c ->
        add(c.image)
        c.voiceActor?.forEach { add(it.image) }
    }
    staff?.forEach { add(it.image) }
    existing?.imageUrls?.let { images.addAll(it) }

    return OfflineEntry(
        mediaId = id,
        type = if (manga != null && anime == null) OfflineCacheCategory.MANGA else OfflineCacheCategory.ANIME,
        idMal = idMAL,
        titleRomaji = nameRomaji,
        titleEnglish = name,
        titleUserPreferred = userPreferredName,
        coverUrl = cover,
        bannerUrl = banner,
        clearLogoUrl = clearLogo ?: existing?.clearLogoUrl,
        isAdult = isAdult,
        format = format,
        mediaStatus = status,
        totalEpisodes = anime?.totalEpisodes,
        totalChapters = manga?.totalChapters,
        totalVolumes = manga?.totalVolumes,
        meanScore = meanScore,
        listId = userListId,
        listStatus = userStatus,
        progress = userProgress,
        progressVolumes = userProgressVolumes,
        score = userScore,
        repeat = userRepeat,
        notes = notes,
        isListPrivate = isListPrivate,
        startedAt = userStartedAt,
        completedAt = userCompletedAt,
        listUpdatedAt = userUpdatedAt,
        isFavourite = isFav,
        favouriteOrder = userFavOrder,
        genres = genres.toList().ifEmpty { existing?.genres.orEmpty() },
        tags = tags.toList().ifEmpty { existing?.tags.orEmpty() },
        tagsIsSpoiler = tagsIsSpoiler.toList().ifEmpty { existing?.tagsIsSpoiler.orEmpty() },
        nextAiringEpisode = anime?.nextAiringEpisode,
        startDate = startDate ?: existing?.startDate,
        categories = categories.map { it.key }.toSet(),
        imageUrls = images,
        episodeImageUrls = existing?.episodeImageUrls.orEmpty(),
        fingerprint = fingerprint(),
        detailsFingerprint = existing?.detailsFingerprint,
        episodeFingerprint = existing?.episodeFingerprint,
        cachedAt = now,
        detailsCachedAt = existing?.detailsCachedAt,
        episodeInfoCachedAt = existing?.episodeInfoCachedAt,
    )
}


/** Rebuilds the app's [Media] from a stored title, so existing screens can show it offline. */
fun OfflineEntry.toMedia(): Media = Media(
    anime = if (type == OfflineCacheCategory.ANIME) Anime(totalEpisodes = totalEpisodes, nextAiringEpisode = nextAiringEpisode) else null,
    manga = if (type == OfflineCacheCategory.MANGA) Manga(totalChapters = totalChapters, totalVolumes = totalVolumes) else null,
    id = mediaId,
    idMAL = idMal,
    name = titleEnglish,
    nameRomaji = titleRomaji,
    userPreferredName = titleUserPreferred,
    cover = coverUrl,
    banner = bannerUrl,
    clearLogo = clearLogoUrl,
    isAdult = isAdult,
    isFav = isFavourite,
    userListId = listId,
    isListPrivate = isListPrivate,
    notes = notes,
    userProgress = progress,
    userProgressVolumes = progressVolumes,
    userStatus = listStatus,
    userScore = score,
    userRepeat = repeat,
    userUpdatedAt = listUpdatedAt,
    userStartedAt = startedAt ?: FuzzyDate(),
    userCompletedAt = completedAt ?: FuzzyDate(),
    userFavOrder = favouriteOrder,
    status = mediaStatus,
    format = format,
    meanScore = meanScore,
    genres = ArrayList(genres),
    tags = ArrayList(tags),
    tagsIsSpoiler = ArrayList(tagsIsSpoiler),
    startDate = startDate,
)
