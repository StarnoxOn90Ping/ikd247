package nox.kiwami.others

import nox.kiwami.client
import nox.kiwami.media.Media
import nox.kiwami.util.Logger
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

    /** Why the last AnimeThemes lookup found nothing (shown to help track problems down). */
    @Volatile
    var lastError: String? = null

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Picks the AnimeThemes entry that belongs to a line of the plain-text list (by title, then by number). */
    fun match(songs: List<ThemeSong>, type: String, index: Int, title: String): ThemeSong? {
        val group = songs.filter { it.type.equals(type, ignoreCase = true) }
        if (group.isEmpty()) return null
        val t = norm(title)
        if (t.isNotEmpty()) {
            group.firstOrNull {
                val n = norm(it.title)
                n.isNotEmpty() && (n == t || n.contains(t) || t.contains(n))
            }?.let { return it }
        }
        group.firstOrNull { (order(it.slug).takeIf { n -> n > 0 } ?: 1) == index }?.let { return it }
        return group.getOrNull(index - 1)
    }

    private fun order(slug: String) = Regex("\\d+").find(slug)?.value?.toIntOrNull() ?: 0

    /**
     * Openings and endings with playable links from AnimeThemes.
     * Looks the anime up by AniList id, then by MAL id, then by its title.
     */
    suspend fun loadAnimeThemes(media: Media): List<ThemeSong> {
        themesCache[media.id]?.let { return it }
        lastError = null
        var songs: List<ThemeSong> = emptyList()
        if (media.id != 0) {
            songs = fetchThemes(mapOf("filter[site]" to "AniList", "filter[external_id]" to "${media.id}"), null)
        }
        if (songs.isEmpty() && media.idMAL != null) {
            songs = fetchThemes(
                mapOf("filter[site]" to "MyAnimeList", "filter[external_id]" to "${media.idMAL}"),
                null
            )
        }
        if (songs.isEmpty()) {
            val names = listOfNotNull(media.name, media.nameRomaji) + media.synonyms
            for (name in listOfNotNull(media.nameRomaji, media.name)) {
                if (name.isBlank()) continue
                songs = fetchThemes(mapOf("q" to name), names.map { norm(it) }.filter { it.isNotEmpty() })
                if (songs.isNotEmpty()) break
            }
        }
        if (songs.isEmpty() && lastError == null) lastError = "no themes listed"
        if (songs.isNotEmpty()) themesCache[media.id] = songs
        return songs
    }

    private suspend fun fetchThemes(query: Map<String, String>, matchNames: List<String>?): List<ThemeSong> {
        val params = mutableMapOf(
            "include" to "animethemes.animethemeentries.videos.audio,animethemes.song.artists",
            "fields[video]" to "id,basename,link,tags,resolution,nc,source",
            "fields[audio]" to "id,basename,link"
        )
        if (matchNames == null) params["filter[has]"] = "resources"
        params.putAll(query)
        val songs = ArrayList<ThemeSong>()
        try {
            withTimeout(10000) {
                val res = client.get(
                    "https://api.animethemes.moe/anime",
                    headers = mapOf("Accept" to "application/json"),
                    params = params,
                    cacheTime = 0
                )
                if (res.code !in 200..299) {
                    lastError = "AnimeThemes HTTP ${res.code}"
                    return@withTimeout
                }
                val list = Json.parseToJsonElement(res.text).jsonObject["anime"] as? JsonArray
                val anime = (if (matchNames == null) list?.firstOrNull()
                else list?.firstOrNull {
                    val n = norm((it as? JsonObject)?.get("name")?.str() ?: "")
                    n.isNotEmpty() && matchNames.contains(n)
                }) as? JsonObject
                if (anime == null) return@withTimeout
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
                                    (if (v["nc"]?.bool() == true) 5 else 0)
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
            lastError = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
            Logger.log("ThemeSongs: AnimeThemes failed: $lastError")
            return emptyList()
        }
        songs.sortWith(compareBy({ !it.isOpening }, { order(it.slug) }))
        return songs
    }

    /** First YouTube video id for a search query (used when AnimeThemes has no video). */
    suspend fun findYoutubeId(query: String): String? {
        return try {
            withTimeout(8000) {
                val html = client.get(
                    "https://www.youtube.com/results",
                    headers = mapOf(
                        "User-Agent" to headers["User-Agent"]!!,
                        "Accept-Language" to "en-US,en;q=0.9"
                    ),
                    params = mapOf("search_query" to query, "sp" to "EgIQAQ=="),
                    cookies = mapOf("CONSENT" to "YES+1", "SOCS" to "CAI"),
                    cacheTime = 0
                ).text
                Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"").find(html)?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            Logger.log("ThemeSongs: YouTube lookup failed: ${e.message}")
            null
        }
    }

    private fun JsonElement.str(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonElement.int(): Int? = (this as? JsonPrimitive)?.intOrNull
    private fun JsonElement.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
}
