package ani.dantotsu.home.streak

import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import com.google.gson.Gson
import java.time.LocalDateTime

/**
 * Watch / read streak, stored on the device only.
 *
 * Rules
 *  - Finishing an episode (80% watched) or a chapter counts for "today", at most once per day.
 *  - A day runs 04:00 -> 04:00, so late-night sessions still belong to the previous day.
 *  - One missed day per week is forgiven (the "grace day"): the streak carries on, it just does
 *    not grow for the skipped day.
 *  - The data is a JSON string in [PrefName.StreakData] (Location.General), so it is part of the
 *    normal backup / restore.
 */
object StreakManager {

    /** Streak at which the Anime / Manga list buttons catch fire. */
    const val BUTTON_FIRE_STREAK = 30

    data class Record(
        var streak: Int = 0,
        var best: Int = 0,
        var lastDay: Long = -1L,
        var graceWeek: Long = -1L,
        var days: MutableList<Long> = mutableListOf(),
        var graceDays: MutableList<Long> = mutableListOf()
    )

    enum class Status {
        /** Never had a streak, or it was too short to mourn. */
        NONE,

        /** Something was finished today. */
        ACTIVE,

        /** Streak is alive but nothing has been finished today yet. */
        AT_RISK,

        /** Yesterday was missed: finishing something today uses up the weekly grace day. */
        GRACE,

        /** A streak of 3+ days just broke. */
        LOST
    }

    enum class Dot { EMPTY, LIT, GRACE }

    data class Snapshot(
        val count: Int,
        val best: Int,
        val status: Status,
        val lostStreak: Int,
        val doneToday: Boolean,
        /** Last 7 days, oldest first. The last entry is today. */
        val week: List<Dot>
    )

    private val gson = Gson()
    private var doneDay = -1L // in-memory shortcut so repeated calls the same day are free

    // ---- time ---------------------------------------------------------------------------------

    fun today(): Long = LocalDateTime.now().minusHours(4).toLocalDate().toEpochDay()

    /** Monday-based week index of an epoch day (epoch day 4 = Monday 5 Jan 1970). */
    private fun weekOf(day: Long): Long = Math.floorDiv(day - 4L, 7L)

    // ---- storage ------------------------------------------------------------------------------

    private fun load(): Record = try {
        val raw = PrefManager.getVal<String>(PrefName.StreakData)
        if (raw.isBlank()) Record() else gson.fromJson(raw, Record::class.java) ?: Record()
    } catch (e: Exception) {
        Logger.log("Streak: could not read data, starting fresh")
        Record()
    }

    private fun save(r: Record) {
        // keep only what the week dots need
        val cutoff = today() - 30
        r.days = r.days.filter { it >= cutoff }.toMutableList()
        r.graceDays = r.graceDays.filter { it >= cutoff }.toMutableList()
        PrefManager.setVal(PrefName.StreakData, gson.toJson(r))
    }

    // ---- recording ----------------------------------------------------------------------------

    /**
     * Call when an episode reaches 80% or a chapter is finished. Safe to call as often as you
     * like: only the first call of each day does anything.
     * @return true if this call grew the streak
     */
    @Synchronized
    fun recordActivity(): Boolean {
        val today = today()
        if (doneDay == today) return false

        val r = load()
        if (r.lastDay == today) {
            doneDay = today
            return false
        }

        val gap = if (r.lastDay < 0) Long.MAX_VALUE else today - r.lastDay
        when {
            gap < 0 -> return false // the clock went backwards, ignore
            r.lastDay < 0 -> r.streak = 1
            gap == 1L -> r.streak += 1
            gap == 2L && graceAvailable(r, r.lastDay + 1) -> {
                val missed = r.lastDay + 1
                r.graceWeek = weekOf(missed)
                r.graceDays.add(missed)
                r.streak += 1
            }

            else -> r.streak = 1
        }
        r.lastDay = today
        if (!r.days.contains(today)) r.days.add(today)
        if (r.streak > r.best) r.best = r.streak
        save(r)
        doneDay = today
        return true
    }

    private fun graceAvailable(r: Record, missedDay: Long) = r.graceWeek != weekOf(missedDay)

    // ---- reading ------------------------------------------------------------------------------

    /** What the UI should show right now. Never modifies stored data. */
    fun snapshot(): Snapshot {
        val r = load()
        val today = today()
        val gap = if (r.lastDay < 0) Long.MAX_VALUE else today - r.lastDay

        var count = 0
        var status = Status.NONE
        var lost = 0
        when {
            r.lastDay < 0 || r.streak <= 0 -> Unit
            gap <= 0L -> { count = r.streak; status = Status.ACTIVE }
            gap == 1L -> { count = r.streak; status = Status.AT_RISK }
            gap == 2L && graceAvailable(r, r.lastDay + 1) -> { count = r.streak; status = Status.GRACE }
            else -> if (r.streak >= 3) { status = Status.LOST; lost = r.streak }
        }

        val week = (6 downTo 0).map { back ->
            val d = today - back
            when {
                r.days.contains(d) -> Dot.LIT
                r.graceDays.contains(d) -> Dot.GRACE
                else -> Dot.EMPTY
            }
        }
        return Snapshot(count, r.best, status, lost, status == Status.ACTIVE, week)
    }

    /** 0..1: how hard the fire burns. */
    fun fireLevel(streak: Int): Float = when {
        streak <= 0 -> 0f
        streak < 3 -> 0.2f
        streak < 7 -> 0.35f
        streak < 14 -> 0.5f
        streak < 30 -> 0.7f
        streak < 60 -> 0.85f
        else -> 1f
    }
}
