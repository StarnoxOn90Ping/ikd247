package ani.dantotsu.others

import ani.dantotsu.client
import ani.dantotsu.media.Media
import ani.dantotsu.util.Logger
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Loads the opening / ending theme songs of an anime for the Info tab.
 * Read-only metadata lookup through the entry's idMAL: it only fills media.anime.op / .ed
 * and has nothing to do with MAL accounts, login or the removed Rescue/MAL mode.
 *
 * 1. scrape the public MyAnimeList page, 2. fall back to the Jikan themes endpoint.
 */
/** One opening / ending, as listed on AnimeThemes (playable audio / video links). */
data class ThemeSong(
    val type: String,
    val slug: String,
    val title: String,
    val artists: List<String>,
    val episodes: String?,
    val audioUrl: String?,
    val videoUrl: String?
) : java.io.Serializable {
    val isOpening get() = type.equals("OP", ignoreCase = true)
}

object ThemeSongs {
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    private val streamingWords =
        Regex("(Spotify|Apple Music|Amazon Music|YouTube Music|Youtube Music)", RegexOption.IGNORE_CASE)

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
                        // drop the streaming-service links (Spotify, Apple Music, ...) from the row text
                        val row = it.clone()
                        row.select("a, input, script, .theme-song-links, .js-theme-song-link, .spoiler_toggle")
                            .remove()
                        val text = row.text().replace(streamingWords, "").replace(Regex("\\s{2,}"), " ").trim()
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

    private val themesCache = HashMap<Int, List<ThemeSong>>()

    private fun order(slug: String) = Regex("\\d+").find(slug)?.value?.toIntOrNull() ?: 0

    /** Openings and endings with playable links, looked up by AniList id on AnimeThemes. */
    suspend fun loadAnimeThemes(anilistId: Int): List<ThemeSong> {
        if (anilistId == 0) return emptyList()
        themesCache[anilistId]?.let { return it }
        val url = "https://api.animethemes.moe/anime" +
                "?filter%5Bhas%5D=resources&filter%5Bsite%5D=AniList&filter%5Bexternal_id%5D=$anilistId" +
                "&include=animethemes.animethemeentries.videos.audio%2Canimethemes.song.artists" +
                "&fields%5Bvideo%5D=id%2Cbasename%2Clink%2Ctags%2Cresolution%2Cnc%2Csource" +
                "&fields%5Baudio%5D=id%2Cbasename%2Clink"
        val songs = ArrayList<ThemeSong>()
        try {
            withTimeout(8000) {
                val text = client.get(url, headers).text
                val anime = (Json.parseToJsonElement(text).jsonObject["anime"] as? JsonArray)
                    ?.firstOrNull() as? JsonObject ?: return@withTimeout
                (anime["animethemes"] as? JsonArray)?.forEach { themeEl ->
                    val theme = themeEl as? JsonObject ?: return@forEach
                    val song = theme["song"] as? JsonObject
                    val artists = (song?.get("artists") as? JsonArray)?.mapNotNull {
                        (it as? JsonObject)?.get("name")?.str()
                    } ?: emptyList()
                    var episodes: String? = null
                    var audio: String? = null
                    var bestVideo: String? = null
                    var bestRank = -1
                    (theme["animethemeentries"] as? JsonArray)?.forEach { entryEl ->
                        val entry = entryEl as? JsonObject ?: return@forEach
                        if (episodes.isNullOrBlank()) episodes = entry["episodes"]?.str()
                        (entry["videos"] as? JsonArray)?.forEach { vEl ->
                            val v = vEl as? JsonObject ?: return@forEach
                            val link = v["link"]?.str() ?: return@forEach
                            val rank = (v["resolution"]?.int() ?: 0) +
                                    if (v["nc"]?.bool() == true) 5 else 0
                            if (rank > bestRank) {
                                bestRank = rank
                                bestVideo = link
                            }
                            if (audio == null) {
                                audio = (v["audio"] as? JsonObject)?.get("link")?.str()
                            }
                        }
                    }
                    if (audio == null && bestVideo == null) return@forEach
                    songs.add(
                        ThemeSong(
                            type = theme["type"]?.str() ?: "",
                            slug = theme["slug"]?.str() ?: "",
                            title = song?.get("title")?.str() ?: "",
                            artists = artists,
                            episodes = episodes?.takeIf { it.isNotBlank() },
                            audioUrl = audio,
                            videoUrl = bestVideo
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Logger.log("ThemeSongs: AnimeThemes failed for $anilistId: ${e.message}")
            return emptyList()
        }
        songs.sortWith(compareBy({ !it.isOpening }, { order(it.slug) }))
        themesCache[anilistId] = songs
        return songs
    }

    private fun JsonElement.str(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonElement.int(): Int? = (this as? JsonPrimitive)?.intOrNull
    private fun JsonElement.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
}
