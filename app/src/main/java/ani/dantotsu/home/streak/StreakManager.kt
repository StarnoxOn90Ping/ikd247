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

    /**
     * Fire stages. The stage only depends on the streak number:
     *
     *  0 -> 0 days      a bed of logs with cold coals, no fire
     *  1 -> 1-2 days    a small fire
     *  2 -> 3-6 days    a bigger fire
     *  3 -> 7-13 days   bigger still, and the Anime / Manga list buttons catch fire
     *  4 -> 14-29 days  more intensity on the icon and on the list buttons
     *  5 -> 30+ days    final stage, nothing changes after this
     */
    val STAGE_STARTS = intArrayOf(0, 1, 3, 7, 14, 30)
    const val MAX_STAGE = 5

    /** First stage where the Anime / Manga list buttons burn. */
    const val BUTTON_FIRE_STAGE = 3

    fun stageOf(streak: Int): Int {
        var stage = 0
        for (i in STAGE_STARTS.indices) if (streak >= STAGE_STARTS[i]) stage = i
        return stage
    }

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

    fun today(): Long = LocalDateTime.now().minusHours(dayStartHour().toLong()).toLocalDate().toEpochDay()

    /** Monday-based week index of an epoch day (epoch day 4 = Monday 5 Jan 1970). */
    private fun weekOf(day: Long): Long = Math.floorDiv(day - 4L, 7L)

    // ---- settings (Settings > Streak) ---------------------------------------------------------

    fun dayStartHour(): Int = PrefManager.getVal<Int>(PrefName.StreakDayStart).coerceIn(0, 8)

    fun graceEnabled(): Boolean = PrefManager.getVal(PrefName.StreakGraceDay)

    /** 0.5..1.0: how much of an episode has to be watched for it to count. */
    fun episodeThreshold(): Float =
        PrefManager.getVal<Int>(PrefName.StreakThreshold).coerceIn(50, 100) / 100f

    /** Fire intensity is fixed at 100%. */
    fun intensityBoost(): Float = 1f

    // ---- debug --------------------------------------------------------------------------------

    fun debugEnabled(): Boolean = PrefManager.getVal(PrefName.StreakDebug)

    /** Index into [DEBUG_STATUSES]: 0 = real status. */
    val DEBUG_STATUSES = listOf(null, Status.ACTIVE, Status.AT_RISK, Status.GRACE, Status.LOST)

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

    private fun graceAvailable(r: Record, missedDay: Long) =
        graceEnabled() && r.graceWeek != weekOf(missedDay)

    /** Wipes the real streak (used by the Reset button). */
    @Synchronized
    fun reset() {
        PrefManager.setVal(PrefName.StreakData, "")
        doneDay = -1L
    }

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
        val real = Snapshot(count, r.best, status, lost, status == Status.ACTIVE, week)
        return if (debugEnabled()) debugSnapshot(real) else real
    }

    /** The Home header shows the debug number / status instead of the real ones. Real data is untouched. */
    private fun debugSnapshot(real: Snapshot): Snapshot {
        val n = PrefManager.getVal<Int>(PrefName.StreakDebugCount).coerceAtLeast(0)
        val forced = DEBUG_STATUSES.getOrNull(PrefManager.getVal<Int>(PrefName.StreakDebugStatus))
        val status = forced ?: if (n > 0) Status.ACTIVE else Status.NONE
        return when (status) {
            Status.LOST -> real.copy(count = 0, status = status, lostStreak = maxOf(n, 3), doneToday = false)
            Status.NONE -> real.copy(count = 0, status = status, lostStreak = 0, doneToday = false)
            else -> real.copy(
                count = n, best = maxOf(real.best, n), status = status, lostStreak = 0,
                doneToday = status == Status.ACTIVE
            )
        }
    }
}
