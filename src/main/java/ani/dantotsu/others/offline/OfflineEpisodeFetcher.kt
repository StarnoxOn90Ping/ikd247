package ani.dantotsu.others.offline

import ani.dantotsu.Mapper
import ani.dantotsu.others.IdMappers
import ani.dantotsu.others.TmdbService
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Collects episode titles, descriptions, thumbnails (plus filler flag and air date) for one anime
 * from the same external APIs the watch page uses, and merges them into an [OfflineEpisodeInfo].
 *
 *  1. Kitsu (GraphQL, by AniList id): title, description, thumbnail.   1 request
 *  2. Jikan (by MAL id, 100 episodes/page): filler flag, air date, title fallback.   1+ requests
 *  3. TMDB, only if Kitsu had nothing (same fallback rule as the watch page): title, overview,
 *     still image, air date. Needs the TMDB id from the ID mapper.   2 requests
 *
 * Every request is throttled by [OfflineRateLimiter]. A source that fails (network error, 404) is
 * skipped; if EVERY source failed the result is null so the title is retried on the next run,
 * while "sources answered but have no episodes" gives an empty info (stamped, not retried until
 * the title changes or it is old).
 */
object OfflineEpisodeFetcher {
    private const val JIKAN_MAX_PAGES = 30

    private data class Ep(
        val number: String,
        var title: String? = null,
        var desc: String? = null,
        var thumb: String? = null,
        var filler: Boolean = false,
        var date: String? = null,
    )

    suspend fun fetch(entry: OfflineEntry): OfflineEpisodeInfo? {
        val merged = sortedMapOf<Int, Ep>()
        var anySourceAnswered = false

        fun slot(number: String): Ep? {
            val n = number.removeSuffix(".0").toIntOrNull() ?: return null
            return merged.getOrPut(n) { Ep(n.toString()) }
        }

        // 1. Kitsu
        var kitsuHadEpisodes = false
        runSource("Kitsu") {
            val nodes = kitsu(entry.mediaId)
            anySourceAnswered = true
            for (n in nodes) {
                val ep = slot(n.number) ?: continue
                ep.title = ep.title ?: n.title
                ep.desc = ep.desc ?: n.desc
                ep.thumb = ep.thumb ?: n.thumb
                kitsuHadEpisodes = true
            }
        }

        // 2. Jikan
        val malId = entry.idMal
        if (malId != null && malId > 0) {
            runSource("Jikan") {
                val list = jikan(malId)
                anySourceAnswered = true
                for (n in list) {
                    val ep = slot(n.number) ?: continue
                    ep.title = ep.title ?: n.title
                    if (n.filler) ep.filler = true
                    ep.date = ep.date ?: n.date
                }
            }
        }

        // 3. TMDB fallback
        if (!kitsuHadEpisodes) {
            runSource("TMDB") {
                OfflineRateLimiter.acquire(OfflineRateLimiter.MAPPER_HOST)
                val tmdbId = IdMappers.getIds(entry.mediaId)?.tmdbId
                if (tmdbId != null && tmdbId > 0) {
                    OfflineRateLimiter.acquire(OfflineRateLimiter.TMDB_HOST)
                    val eps = TmdbService.getEpisodeDetails(tmdbId)
                    anySourceAnswered = true
                    eps?.values?.forEach { e ->
                        val ep = slot(e.number) ?: return@forEach
                        ep.title = ep.title ?: e.title
                        ep.desc = ep.desc ?: e.desc
                        ep.thumb = ep.thumb ?: e.thumb?.url
                        ep.date = ep.date ?: e.extra?.get("airDate")
                    }
                }
            }
        }

        if (!anySourceAnswered) return null
        return OfflineEpisodeInfo(
            mediaId = entry.mediaId,
            episodes = merged.values.map {
                OfflineEpisode(
                    number = it.number,
                    title = it.title,
                    description = it.desc,
                    thumbnailUrl = it.thumb,
                    filler = it.filler,
                    airDate = it.date,
                )
            },
            cachedAt = System.currentTimeMillis(),
        )
    }

    /** Runs one source; rate-limit/network pauses and cancellation propagate, anything else is skipped. */
    private suspend fun runSource(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: OfflineRateLimitedException) {
            throw e
        } catch (e: OfflineNetworkException) {
            throw e
        } catch (e: Exception) {
            Logger.log("OfflineEpisodeFetcher: $name failed: ${e.message}")
        }
    }

    private data class Node(
        val number: String,
        val title: String? = null,
        val desc: String? = null,
        val thumb: String? = null,
        val filler: Boolean = false,
        val date: String? = null,
    )

    private suspend fun kitsu(anilistId: Int): List<Node> {
        val query = """
            query {
              lookupMapping(externalId: $anilistId, externalSite: ANILIST_ANIME) {
                __typename
                ... on Anime {
                  id
                  episodes(first: 2000) {
                    nodes {
                      number
                      titles { canonical }
                      description(locales: ["en", "en-us"])
                      thumbnail { original { url } }
                    }
                  }
                }
              }
            }
        """.trimIndent()
        val payload = buildJsonObject { put("query", query) }.toString()
        val res = OfflineHttp.postJson(
            "https://kitsu.io/api/graphql",
            payload,
            mapOf("Accept" to "application/json")
        ) ?: return emptyList()
        if (!res.ok) return emptyList()
        val root = Mapper.json.parseToJsonElement(res.body).jsonObject
        val nodes = root["data"]?.obj()?.get("lookupMapping")?.obj()
            ?.get("episodes")?.obj()?.get("nodes") as? kotlinx.serialization.json.JsonArray
            ?: return emptyList()
        return nodes.mapNotNull { el ->
            val o = el.obj() ?: return@mapNotNull null
            val number = o["number"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val desc = o["description"]?.obj()?.let { d ->
                d["en"]?.jsonPrimitive?.contentOrNull ?: d["en-us"]?.jsonPrimitive?.contentOrNull
            }
            Node(
                number = number,
                title = o["titles"]?.obj()?.get("canonical")?.jsonPrimitive?.contentOrNull,
                desc = desc?.replace(Regex("\\(Source:.*\\)"), "")?.trim()?.ifBlank { null },
                thumb = o["thumbnail"]?.obj()?.get("original")?.obj()?.get("url")?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    private suspend fun jikan(malId: Int): List<Node> {
        val out = mutableListOf<Node>()
        var page = 1
        while (page <= JIKAN_MAX_PAGES) {
            val res = OfflineHttp.get("https://api.jikan.moe/v4/anime/$malId/episodes?page=$page")
                ?: break
            if (!res.ok) break
            val root = Mapper.json.parseToJsonElement(res.body).jsonObject
            (root["data"] as? kotlinx.serialization.json.JsonArray)?.forEach { el ->
                val o = el.obj() ?: return@forEach
                val number = o["mal_id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                out += Node(
                    number = number,
                    title = o["title"]?.jsonPrimitive?.contentOrNull,
                    filler = o["filler"]?.jsonPrimitive?.booleanOrNull == true,
                    date = o["aired"]?.jsonPrimitive?.contentOrNull?.substringBefore("T"),
                )
            }
            val hasNext = root["pagination"]?.obj()?.get("has_next_page")?.jsonPrimitive?.booleanOrNull == true
            if (!hasNext) break
            page++
        }
        return out
    }

    private fun JsonElement.obj(): JsonObject? = this as? JsonObject
}
