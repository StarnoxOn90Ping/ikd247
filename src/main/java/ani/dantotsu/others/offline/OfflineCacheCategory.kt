package ani.dantotsu.others.offline

import androidx.annotation.StringRes
import ani.dantotsu.R

/**
 * The lists the user can choose to preload for offline use ("Preload Cache for Offline Mode").
 *
 * Only these eight categories exist. Recommended, Trending and Missing Sequels are deliberately
 * NOT categories: they are discovery content, they change constantly and are not "yours".
 * [OfflineCacheCategory.EXCLUDED_SOURCES] documents that, and [fromKey] / [selected] drop any
 * unknown key, so a bad or hand-edited preference can never switch an excluded list on.
 *
 * [key] is what is stored in the preference, so never rename one.
 *
 * AniList mapping: [types] are AniList media types, [listStatuses] are AniList MediaListStatus
 * values (CURRENT also covers REPEATING). [isFavourites] categories come from the user's
 * favourites instead of a list status.
 */
enum class OfflineCacheCategory(
    val key: String,
    @StringRes val labelRes: Int,
    val types: Set<String>,
    val listStatuses: Set<String> = emptySet(),
    val isFavourites: Boolean = false,
) {
    CONTINUE_WATCHING("continue_watching", R.string.offline_cat_continue_watching, setOf(ANIME), setOf("CURRENT", "REPEATING")),
    PLANNED_ANIME("planned_anime", R.string.offline_cat_planned_anime, setOf(ANIME), setOf("PLANNING")),
    CONTINUE_READING("continue_reading", R.string.offline_cat_continue_reading, setOf(MANGA), setOf("CURRENT", "REPEATING")),
    PLANNED_MANGA("planned_manga", R.string.offline_cat_planned_manga, setOf(MANGA), setOf("PLANNING")),
    FAVORITES("favorites", R.string.offline_cat_favorites, setOf(ANIME, MANGA), isFavourites = true),
    COMPLETED("completed", R.string.offline_cat_completed, setOf(ANIME, MANGA), setOf("COMPLETED")),
    PAUSED("paused", R.string.offline_cat_paused, setOf(ANIME, MANGA), setOf("PAUSED")),
    DROPPED("dropped", R.string.offline_cat_dropped, setOf(ANIME, MANGA), setOf("DROPPED"));

    companion object {
        const val ANIME = "ANIME"
        const val MANGA = "MANGA"

        /** Home/discovery sources that must never be preloaded. Not categories, on purpose. */
        val EXCLUDED_SOURCES: Set<String> = setOf("recommendations", "trending", "missingSequels")

        /** Everything on by default once the feature is turned on. */
        val DEFAULT_KEYS: Set<String> = entries.map { it.key }.toSet()

        fun fromKey(key: String): OfflineCacheCategory? = entries.firstOrNull { it.key == key }

        /** Turns the stored key set into categories, ignoring anything unknown. */
        fun selected(keys: Set<String>): Set<OfflineCacheCategory> =
            keys.mapNotNull { fromKey(it) }.toSet()
    }
}
