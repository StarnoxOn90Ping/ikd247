package ani.dantotsu.others

import ani.dantotsu.client
import ani.dantotsu.media.Media
import ani.dantotsu.util.Logger
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Loads the opening / ending theme songs of an anime for the Info tab.
 * Read-only metadata lookup through the entry's idMAL: it only fills media.anime.op / .ed
 * and has nothing to do with MAL accounts, login or the removed Rescue/MAL mode.
 *
 * 1. scrape the public MyAnimeList page, 2. fall back to the Jikan themes endpoint.
 */
object ThemeSongs {
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    // revisiting an entry should not hit the network again
    private val cache = HashMap<Int, Pair<List<String>, List<String>>>()

    suspend fun load(media: Media) {
        val anime = media.anime ?: return
        val malId = media.idMAL ?: return

        cache[malId]?.let { (ops, eds) ->
            if (ops.isNotEmpty()) anime.op = ArrayList(ops)
            if (eds.isNotEmpty()) anime.ed = ArrayList(eds)
            return
        }

        // 1. MyAnimeList page
        try {
            withTimeout(5000) {
                val res = client.get("https://myanimelist.net/anime/$malId", headers).document

                fun parseSongs(desktopSelector: String, mobileHeaderClass: String): ArrayList<String> {
                    val songs = arrayListOf<String>()
                    var rows = res.select("$desktopSelector table tr")
                    if (rows.isEmpty()) rows = res.select("$desktopSelector tr")
                    if (rows.isEmpty()) rows = res.select("#songs span.$mobileHeaderClass ~ div.di-b table tr")
                    if (rows.isEmpty()) rows = res.select("#songs span.$mobileHeaderClass ~ table tr")
                    rows.forEach {
                        val text = it.text().trim()
                        if (text.isNotBlank() &&
                            !text.contains("Help improve our database") &&
                            !text.contains("No opening themes have been added", ignoreCase = true) &&
                            !text.contains("No ending themes have been added", ignoreCase = true)
                        ) songs.add(text)
                    }
                    return songs
                }

                val ops = parseSongs(".opnening", "theme-songs-op")
                if (ops.isNotEmpty()) anime.op = ops
                val eds = parseSongs(".ending", "theme-songs-ed")
                if (eds.isNotEmpty()) anime.ed = eds
            }
        } catch (e: Exception) {
            Logger.log("ThemeSongs: MAL page failed for $malId: ${e.message}")
        }

        // 2. Jikan fallback
        if (anime.op.isEmpty() && anime.ed.isEmpty()) {
            try {
                withTimeout(5000) {
                    val data = client.get("https://api.jikan.moe/v4/anime/$malId/themes")
                        .parsed<ThemesResponse>().data
                    data?.openings?.let { if (it.isNotEmpty()) anime.op = ArrayList(it) }
                    data?.endings?.let { if (it.isNotEmpty()) anime.ed = ArrayList(it) }
                }
            } catch (e: Exception) {
                Logger.log("ThemeSongs: Jikan failed for $malId: ${e.message}")
            }
        }

        if (anime.op.isNotEmpty() || anime.ed.isNotEmpty()) {
            cache[malId] = anime.op.toList() to anime.ed.toList()
        }
    }

    @Serializable
    data class ThemesResponse(val data: Themes? = null)

    @Serializable
    data class Themes(
        @SerialName("openings") val openings: List<String>? = null,
        @SerialName("endings") val endings: List<String>? = null
    )
}
