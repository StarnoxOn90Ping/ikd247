package ani.dantotsu.calendar

import ani.dantotsu.media.Media
import ani.dantotsu.settings.saving.PrefManager

/** One episode that will air (or has aired) inside the calendar window. */
data class CalendarEntry(
    val media: Media,
    val episode: Int,
    /** epoch seconds */
    val airingAt: Long,
    /** AniList list status: CURRENT, PLANNING, REPEATING, PAUSED, COMPLETED, DROPPED – or null when not listed */
    val listStatus: String?,
    /** episodes the user has watched, when known */
    val progress: Int?
)

enum class ListStatus(val key: String, val bit: Int, val color: Int) {
    CURRENT("CURRENT", 1, 0xFF3DB46D.toInt()),
    PLANNING("PLANNING", 2, 0xFFF2A100.toInt()),
    REPEATING("REPEATING", 4, 0xFF1FA6A0.toInt()),
    PAUSED("PAUSED", 8, 0xFF8E8E99.toInt()),
    COMPLETED("COMPLETED", 16, 0xFF5C6BC0.toInt()),
    DROPPED("DROPPED", 32, 0xFFE0443E.toInt());

    companion object {
        fun from(raw: String?): ListStatus? {
            val s = raw?.uppercase()?.trim() ?: return null
            return values().firstOrNull { s == it.key }
        }

        const val DEFAULT_MASK = 1 or 2 or 4 or 8 or 16
    }
}

/** Persisted calendar customisation. */
object CalendarPrefs {
    const val SOURCE_MINE = 0
    const val SOURCE_ALL = 1

    val AHEAD_OPTIONS = listOf(3, 7, 14, 30)
    val PAST_OPTIONS = listOf(0, 1, 3, 7)

    private const val K_SOURCE = "cal_source"
    private const val K_MASK = "cal_status_mask"
    private const val K_AHEAD = "cal_ahead_days"
    private const val K_PAST = "cal_past_days"
    private const val K_HIDE_AIRED = "cal_hide_aired"
    private const val K_COMPACT = "cal_compact"
    private const val K_24H = "cal_24h"

    var source: Int
        get() = PrefManager.getCustomVal(K_SOURCE, SOURCE_MINE)
        set(v) = PrefManager.setCustomVal(K_SOURCE, v)

    var statusMask: Int
        get() = PrefManager.getCustomVal(K_MASK, ListStatus.DEFAULT_MASK)
        set(v) = PrefManager.setCustomVal(K_MASK, v)

    var aheadDays: Int
        get() = PrefManager.getCustomVal(K_AHEAD, 14)
        set(v) = PrefManager.setCustomVal(K_AHEAD, v)

    var pastDays: Int
        get() = PrefManager.getCustomVal(K_PAST, 1)
        set(v) = PrefManager.setCustomVal(K_PAST, v)

    var hideAired: Boolean
        get() = PrefManager.getCustomVal(K_HIDE_AIRED, false)
        set(v) = PrefManager.setCustomVal(K_HIDE_AIRED, v)

    var compact: Boolean
        get() = PrefManager.getCustomVal(K_COMPACT, false)
        set(v) = PrefManager.setCustomVal(K_COMPACT, v)

    /** 0 = follow the system setting, 1 = 12h, 2 = 24h */
    var timeFormat: Int
        get() = PrefManager.getCustomVal(K_24H, 0)
        set(v) = PrefManager.setCustomVal(K_24H, v)

    fun reset() {
        source = SOURCE_MINE
        statusMask = ListStatus.DEFAULT_MASK
        aheadDays = 14
        pastDays = 1
        hideAired = false
        compact = false
        timeFormat = 0
    }
}
