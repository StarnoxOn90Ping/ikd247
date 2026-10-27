package ani.dantotsu.others.offline

import android.os.SystemClock
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-host request spacing for the offline preload. EVERY request the preload makes (AniList,
 * Kitsu, Jikan, TMDB, the ID mapper and all image downloads) calls [acquire] first, so the
 * limits below hold no matter how many coroutines run at once.
 *
 * Each host gets a "next allowed time". [acquire] reserves the next slot immediately (so
 * parallel callers queue up one interval apart) and then sleeps until that slot arrives.
 * The intervals sit comfortably below the published limits:
 *
 *  - AniList: 90 requests/min  -> one per 1.1 s  (about 54/min)
 *  - Jikan:   3/s and 60/min   -> one per 1.1 s  (about 54/min, also under 3/s)
 *  - Kitsu:   no hard limit    -> one per 0.7 s
 *  - TMDB:    ~40/s            -> one per 0.3 s
 *  - ID mapper (hf.space)      -> one per 0.7 s
 *  - image CDNs / anything else-> one per 0.15-0.2 s per host
 *
 * When a server answers 429/503, [penalize] pushes that host's next slot back by the
 * Retry-After time so nothing else goes out to it in the meantime.
 */
object OfflineRateLimiter {
    const val ANILIST_HOST = "graphql.anilist.co"
    const val JIKAN_HOST = "api.jikan.moe"
    const val KITSU_HOST = "kitsu.io"
    const val TMDB_HOST = "api.themoviedb.org"
    const val MAPPER_HOST = "isagi2025-idmapper.hf.space"

    private class Slot(val intervalMs: Long) {
        var nextAt = 0L
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    fun intervalFor(host: String): Long = when {
        host == ANILIST_HOST -> 1100L
        host == JIKAN_HOST -> 1100L
        host.endsWith("kitsu.io") || host.endsWith("kitsu.app") -> 700L
        host == TMDB_HOST -> 300L
        host.endsWith("hf.space") -> 700L
        host == "image.tmdb.org" -> 150L
        else -> 200L
    }

    private fun slot(host: String) = slots.getOrPut(host.lowercase()) { Slot(intervalFor(host.lowercase())) }

    /** Suspends until it is this caller's turn to send one request to [host]. */
    suspend fun acquire(host: String) {
        val s = slot(host)
        val wait = synchronized(s) {
            val now = SystemClock.elapsedRealtime()
            val start = maxOf(now, s.nextAt)
            s.nextAt = start + s.intervalMs
            start - now
        }
        if (wait > 0) delay(wait)
    }

    /** Blocks [host] for [millis] more (the server told us to back off). */
    fun penalize(host: String, millis: Long) {
        val s = slot(host)
        synchronized(s) {
            s.nextAt = maxOf(s.nextAt, SystemClock.elapsedRealtime() + millis)
        }
    }
}
