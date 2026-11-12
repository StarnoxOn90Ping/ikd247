package ani.kiwami.others

import ani.kiwami.client
import ani.kiwami.media.anime.Episode
import ani.kiwami.tryWithSuspend
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Jikan (unofficial MyAnimeList REST API) - used only as a read-only metadata source for the
 * filler flag and episode air dates of an AniList entry (looked up through its idMAL).
 * It has nothing to do with MAL accounts, login or the removed Rescue/MAL mode.
 */
object Jikan {

    suspend fun getEpisodes(malId: Int): Map<String, Episode> {
        return tryWithSuspend(snackbar = false) {
            val episodes = mutableMapOf<String, Episode>()
            var page = 1
            var hasNextPage = true
            while (hasNextPage) {
                val res = client.get(
                    "https://api.jikan.moe/v4/anime/$malId/episodes?page=$page"
                ).parsed<EpisodeResponse>()

                res.data?.forEach { ep ->
                    val number = ep.malId?.toString() ?: return@forEach
                    episodes[number] = Episode(
                        number = number,
                        title = ep.title,
                        filler = ep.filler == true,
                        date = ep.aired?.substringBefore("T")
                    )
                }

                hasNextPage = res.pagination?.hasNextPage == true
                page++
                // Jikan rate limits aggressively (3 requests/second)
                if (hasNextPage) delay(400)
            }
            episodes
        } ?: emptyMap()
    }

    @Serializable
    data class EpisodeResponse(
        val pagination: Pagination? = null,
        val data: List<EpisodeData>? = null
    )

    @Serializable
    data class Pagination(
        @SerialName("has_next_page") val hasNextPage: Boolean? = null
    )

    @Serializable
    data class EpisodeData(
        @SerialName("mal_id") val malId: Int? = null,
        val title: String? = null,
        val aired: String? = null,
        val filler: Boolean? = null,
        val recap: Boolean? = null
    )
}
