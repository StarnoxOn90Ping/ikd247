package ani.dantotsu.home.pill

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import ani.dantotsu.home.streak.StreakManager
import ani.dantotsu.media.manga.mangareader.MangaReaderActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import org.json.JSONObject

/**
 * Time spent watching anime and reading manga, kept per day on the device only.
 *
 *  - Anime: [tickAnime] is called by the player's progress loop (every ~2.5 s). Only time during
 *    which the video is actually playing counts (wall-clock time, not scaled by playback speed).
 *  - Manga: counted while the reader is on screen (see [install]); one stretch is capped so a
 *    reader left open on the table does not add hours.
 *
 * Days use [StreakManager.today] (so the 04:00 rollover matches the streak). The data is a JSON
 * string in [PrefName.WatchTimeData], so it is part of the normal backup / restore.
 * Nothing is recorded in Incognito.
 *
 * The Home pill reads it through [todayMinutes] only, so this is the one place to adapt if the
 * per-day minutes ever live somewhere else.
 */
object WatchTimeTracker {

    data class Minutes(val anime: Int, val manga: Int) {
        val total get() = anime + manga
    }

    private const val KEEP_DAYS = 60
    private const val FLUSH_AFTER_MS = 15_000L
    private const val MAX_ANIME_GAP_MS = 6_000L
    private const val MAX_MANGA_STRETCH_MS = 45 * 60_000L

    // not yet written to the preferences
    private var pendingAnimeMs = 0L
    private var pendingMangaMs = 0L
    private var pendingDay = -1L

    private var lastAnimeTick = 0L
    private var mangaStart = 0L
    private var installed = false

    // ---- recording ----------------------------------------------------------------------------

    /** Call on every player progress tick. [playing] = the video is currently playing. */
    @Synchronized
    fun tickAnime(playing: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (playing && lastAnimeTick > 0L && !incognito()) {
            addPending(anime = (now - lastAnimeTick).coerceIn(0L, MAX_ANIME_GAP_MS), manga = 0L)
        }
        lastAnimeTick = if (playing) now else 0L
    }

    /** The player stopped tracking (left the player). */
    @Synchronized
    fun stopAnime() {
        lastAnimeTick = 0L
        flush()
    }

    @Synchronized
    private fun addPending(anime: Long, manga: Long) {
        if (anime <= 0L && manga <= 0L) return
        val day = StreakManager.today()
        if (pendingDay != -1L && pendingDay != day) flush() // day rolled over: bank yesterday first
        pendingDay = day
        pendingAnimeMs += anime
        pendingMangaMs += manga
        if (pendingAnimeMs + pendingMangaMs >= FLUSH_AFTER_MS) flush()
    }

    /** Writes whatever is waiting to the preferences. */
    @Synchronized
    fun flush() {
        if (pendingDay == -1L || (pendingAnimeMs <= 0L && pendingMangaMs <= 0L)) return
        try {
            val root = readRoot()
            val key = pendingDay.toString()
            val old = root.optJSONArray(key)
            val a = (old?.optLong(0) ?: 0L) + pendingAnimeMs / 1000
            val m = (old?.optLong(1) ?: 0L) + pendingMangaMs / 1000
            // keep the sub-second remainder so short ticks never get lost
            pendingAnimeMs %= 1000
            pendingMangaMs %= 1000
            root.put(key, org.json.JSONArray().put(a).put(m))
            val cutoff = StreakManager.today() - KEEP_DAYS
            root.keys().asSequence().toList().forEach { k ->
                if ((k.toLongOrNull() ?: 0L) < cutoff) root.remove(k)
            }
            PrefManager.setVal(PrefName.WatchTimeData, root.toString())
        } catch (e: Exception) {
            Logger.log(e)
        }
    }

    /**
     * Hooks the manga reader through the activity lifecycle, so the reader itself needs no changes.
     * Safe to call more than once. Call from Application.onCreate.
     */
    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is MangaReaderActivity) mangaStart = SystemClock.elapsedRealtime()
            }

            override fun onActivityPaused(activity: Activity) {
                if (activity is MangaReaderActivity) endMangaStretch()
            }

            override fun onActivityCreated(a: Activity, s: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })
    }

    @Synchronized
    private fun endMangaStretch() {
        val start = mangaStart
        mangaStart = 0L
        if (start <= 0L || incognito()) return
        val ms = (SystemClock.elapsedRealtime() - start).coerceIn(0L, MAX_MANGA_STRETCH_MS)
        addPending(anime = 0L, manga = ms)
        flush()
    }

    private fun incognito(): Boolean = try {
        PrefManager.getVal<Boolean>(PrefName.Incognito)
    } catch (e: Exception) {
        false
    }

    // ---- reading ------------------------------------------------------------------------------

    /** Whole minutes watched / read today (includes what has not been written yet). */
    @Synchronized
    fun todayMinutes(): Minutes {
        val today = StreakManager.today()
        var a = 0L
        var m = 0L
        try {
            val stored = readRoot().optJSONArray(today.toString())
            a = stored?.optLong(0) ?: 0L
            m = stored?.optLong(1) ?: 0L
        } catch (e: Exception) {
            Logger.log(e)
        }
        if (pendingDay == today) {
            a += pendingAnimeMs / 1000
            m += pendingMangaMs / 1000
        }
        // a stretch that is still running counts too
        if (mangaStart > 0L && !incognito()) {
            m += (SystemClock.elapsedRealtime() - mangaStart).coerceIn(0L, MAX_MANGA_STRETCH_MS) / 1000
        }
        return Minutes((a / 60).toInt(), (m / 60).toInt())
    }

    private fun readRoot(): JSONObject {
        val raw = PrefManager.getVal<String>(PrefName.WatchTimeData)
        return if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }
}
