package ani.dantotsu.others.offline

import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.FuzzyDate
import ani.dantotsu.currContext
import ani.dantotsu.isOnline
import ani.dantotsu.media.Media
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.FileUrl
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName

/**
 * The READ side of "Preload Cache for Offline Mode". Everything the existing screens need to work
 * without a connection is rebuilt here from [OfflineCache], in the same shapes the network code
 * returns, so the screens themselves barely change:
 *
 *  - [buildHomePage]  the map `Anilist.query.initHomePage()` returns (continue / favourites / planned)
 *  - [buildLists]     the map `Anilist.query.getMediaLists()` returns (Watching, Planning ... All)
 *  - [episodeMap]     Kitsu-style episode map for the watch page
 *  - [applyEdit] etc. keep stored titles in step with changes the user makes while online
 *
 * The rule everywhere: when the device is offline, or the network call failed, serve this; when
 * online, the network still wins and refreshes what is stored.
 */
object OfflineLibrary {

    /** True when the device has no usable connection right now. */
    fun isOffline(): Boolean {
        val c = currContext() ?: return false
        return !isOnline(c)
    }

    /** True once the preload holds something worth showing. */
    fun hasData(): Boolean = OfflineCache.titleCount() > 0

    /** Offline lists are only for the signed-in user's own lists. */
    fun isOwnUser(userId: Int): Boolean {
        val own = Anilist.userid
            ?: runCatching { PrefManager.getVal<String>(PrefName.AnilistUserId).toIntOrNull() }.getOrNull()
        return own == null || own == 0 || userId == 0 || userId == own
    }

    // ---- Home -------------------------------------------------------------------------------

    /** Same keys and meaning as the online home page. Recommended / Missing Sequels are never preloaded. */
    fun buildHomePage(): Map<String, ArrayList<Media>> {
        if (!hasData()) return emptyMap()
        val toShow = runCatching { PrefManager.getVal<List<Boolean>>(PrefName.HomeLayout) }.getOrDefault(emptyList())
        fun show(i: Int) = toShow.getOrNull(i) != false
        val removed = PrefManager.getCustomVal<Set<String>>("removeList", emptySet()).mapNotNull { it.toIntOrNull() }.toSet()
        val hidePrivate = runCatching { PrefManager.getVal<Boolean>(PrefName.HidePrivate) }.getOrDefault(false)
        val all = OfflineCache.allEntries().filter { it.mediaId !in removed && (!hidePrivate || !it.isListPrivate) }

        fun continuing(type: String) = all
            .filter { it.type == type && it.listStatus in setOf("CURRENT", "REPEATING") }
            .sortedByDescending { it.listUpdatedAt ?: 0L }
            .map { it.toMedia().apply { cameFromContinue = true } }.toCollection(ArrayList())

        fun planned(type: String) = all
            .filter { it.type == type && it.listStatus == "PLANNING" }
            .sortedByDescending { it.listUpdatedAt ?: 0L }
            .map { it.toMedia() }.toCollection(ArrayList())

        fun favourites(type: String) = all
            .filter { it.type == type && it.isFavourite }
            .sortedBy { it.favouriteOrder ?: Int.MAX_VALUE }
            .map { it.toMedia().apply { isFav = true } }.toCollection(ArrayList())

        val out = mutableMapOf<String, ArrayList<Media>>()
        if (show(0)) out["currentAnime"] = continuing(OfflineCacheCategory.ANIME)
        if (show(1)) out["favoriteAnime"] = favourites(OfflineCacheCategory.ANIME)
        if (show(2)) out["currentAnimePlanned"] = planned(OfflineCacheCategory.ANIME)
        if (show(3)) out["currentManga"] = continuing(OfflineCacheCategory.MANGA)
        if (show(4)) out["favoriteManga"] = favourites(OfflineCacheCategory.MANGA)
        if (show(5)) out["currentMangaPlanned"] = planned(OfflineCacheCategory.MANGA)
        return out
    }

    // ---- Anime / Manga list screens ---------------------------------------------------------

    /** Same shape as `getMediaLists`: list name -> titles, plus "Favourites" and "All". Null = nothing stored. */
    fun buildLists(anime: Boolean, sortOrder: String? = null): MutableMap<String, ArrayList<Media>>? {
        val type = if (anime) OfflineCacheCategory.ANIME else OfflineCacheCategory.MANGA
        val entries = OfflineCache.allEntries().filter { it.type == type }
        if (entries.isEmpty()) return null

        val groups = listOf(
            (if (anime) "Watching" else "Reading") to setOf("CURRENT"),
            (if (anime) "Rewatching" else "Rereading") to setOf("REPEATING"),
            "Completed" to setOf("COMPLETED"),
            "Paused" to setOf("PAUSED"),
            "Dropped" to setOf("DROPPED"),
            "Planning" to setOf("PLANNING"),
        )
        val result = linkedMapOf<String, ArrayList<Media>>()
        for ((name, statuses) in groups) {
            val list = entries.filter { it.listStatus in statuses }.map { it.toMedia() }.toCollection(ArrayList())
            if (list.isNotEmpty()) result[name] = list
        }
        result["Favourites"] = entries.filter { it.isFavourite }
            .sortedBy { it.favouriteOrder ?: Int.MAX_VALUE }
            .map { it.toMedia().apply { isFav = true } }.toCollection(ArrayList())
        result["All"] = entries.filter { it.listStatus != null }.map { it.toMedia() }.toCollection(ArrayList())

        val saved: String? = runCatching {
            PrefManager.getVal<String>(if (anime) PrefName.AnimeListSortOrder else PrefName.MangaListSortOrder)
        }.getOrNull()
        val sort = saved?.ifEmpty { null } ?: sortOrder
        for ((name, list) in result) {
            if (name == "Favourites") continue // keeps favourite order, like online
            when (sort) {
                "score" -> list.sortWith { b, a -> compareValuesBy(a, b, { it.userScore }, { it.meanScore }) }
                "title" -> list.sortWith(compareBy { it.userPreferredName })
                "updatedAt" -> list.sortWith(compareByDescending { it.userUpdatedAt })
                "release" -> list.sortWith(compareByDescending { it.startDate })
                "id" -> list.sortWith(compareBy { it.id })
            }
        }
        return result
    }

    // ---- Episode info -----------------------------------------------------------------------

    /** Stored episode info in the map shape the watch page already uses (key = episode number). */
    fun episodeMap(mediaId: Int): Map<String, Episode>? {
        val info = OfflineCache.getEpisodeInfo(mediaId)?.takeIf { it.episodes.isNotEmpty() } ?: return null
        return info.episodes.associate { e ->
            e.number to Episode(
                number = e.number,
                title = e.title,
                desc = e.description,
                thumb = e.thumbnailUrl?.let { FileUrl(it) },
                filler = e.filler,
                date = e.airDate,
            )
        }
    }

    /** Only the episodes flagged as filler (what the watch page's filler source expects). */
    fun fillerMap(mediaId: Int): Map<String, Episode>? =
        episodeMap(mediaId)?.filterValues { it.filler }?.takeIf { it.isNotEmpty() }

    // ---- Keeping stored titles in step with edits made while online -------------------------

    private fun categoriesFor(type: String, status: String?, isFav: Boolean): Set<String> =
        OfflineCache.enabledCategories().filter { c ->
            type in c.types && ((status != null && status in c.listStatuses) || (c.isFavourites && isFav))
        }.map { it.key }.toSet()

    /** Applies a list edit to the stored copy (progress, score, status ...) so offline matches what you just did. */
    fun applyEdit(
        mediaId: Int,
        progress: Int?, progressVolumes: Int?, score: Int?, repeat: Int?, notes: String?,
        status: String?, private: Boolean?, startedAt: FuzzyDate?, completedAt: FuzzyDate?,
    ) {
        val e = OfflineCache.getEntry(mediaId) ?: return
        val newStatus = status?.replace(" ", "_")?.uppercase() ?: e.listStatus
        val cats = categoriesFor(e.type, newStatus, e.isFavourite)
        if (cats.isEmpty()) {
            OfflineCache.removeEntry(mediaId)
            return
        }
        OfflineCache.updateEntry(mediaId) {
            it.copy(
                progress = progress ?: it.progress,
                progressVolumes = progressVolumes ?: it.progressVolumes,
                score = score ?: it.score,
                repeat = repeat ?: it.repeat,
                notes = notes ?: it.notes,
                listStatus = newStatus,
                isListPrivate = private ?: it.isListPrivate,
                startedAt = startedAt ?: it.startedAt,
                completedAt = completedAt ?: it.completedAt,
                listUpdatedAt = System.currentTimeMillis() / 1000,
                categories = cats,
            )
        }
    }

    fun applyFavourite(mediaId: Int, isFavourite: Boolean) {
        val e = OfflineCache.getEntry(mediaId) ?: return
        val cats = categoriesFor(e.type, e.listStatus, isFavourite)
        if (cats.isEmpty()) OfflineCache.removeEntry(mediaId)
        else OfflineCache.updateEntry(mediaId) { it.copy(isFavourite = isFavourite, categories = cats) }
    }

    /** A title was removed from the user's list. */
    fun applyListDeleted(listId: Int) {
        val e = OfflineCache.allEntries().firstOrNull { it.listId == listId } ?: return
        val cats = categoriesFor(e.type, null, e.isFavourite)
        if (cats.isEmpty()) OfflineCache.removeEntry(e.mediaId)
        else OfflineCache.updateEntry(e.mediaId) {
            it.copy(listStatus = null, listId = null, progress = null, progressVolumes = null, score = 0, categories = cats)
        }
    }

    /** Stores a freshly fetched details response for a title that is already preloaded. */
    fun refreshDetails(mediaId: Int, response: ani.dantotsu.connections.anilist.api.Query.Media) {
        val e = OfflineCache.getEntry(mediaId) ?: return
        if (response.data?.media == null) return
        OfflineCache.putDetails(mediaId, response, imageUrls = e.imageUrls)
    }
}
