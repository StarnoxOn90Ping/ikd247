package nox.kiwami.calendar

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import nox.kiwami.R
import nox.kiwami.connections.anilist.Anilist
import nox.kiwami.databinding.ActivityAiringCalendarBinding
import nox.kiwami.databinding.DialogCalendarCustomizeBinding
import nox.kiwami.databinding.ItemCalendarDayBinding
import nox.kiwami.getThemeColor
import nox.kiwami.initActivity
import nox.kiwami.media.Media
import nox.kiwami.media.MediaDetailsActivity
import nox.kiwami.navBarHeight
import nox.kiwami.statusBarHeight
import nox.kiwami.themes.ThemeManager
import nox.kiwami.util.fadeInDown
import nox.kiwami.util.setGradientText
import nox.kiwami.util.setPressBounce
import nox.kiwami.util.Logger
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Personal airing calendar.
 *
 * Shows when the anime on your AniList (watching / planning / completed …) air, from a few days back
 * to a month ahead, as a swipeable day strip on top of an agenda. Everything about it can be tuned
 * from the customise sheet (see [CalendarPrefs]).
 */
class AiringCalendarActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAiringCalendarBinding
    private val adapter = CalendarAdapter { openMedia(it.media) }

    /** everything fetched for [fetchKey], before the local filters are applied */
    private var raw: List<CalendarEntry> = emptyList()
    private var fetchKey: String? = null
    private var loadJob: Job? = null
    private var libraryCache: List<Media>? = null

    private var loadState = LoadState.LOADING
    private enum class LoadState { LOADING, READY, NEEDS_LOGIN, ERROR }

    private var dayStarts: List<Long> = emptyList()
    private var dayCells: List<ItemCalendarDayBinding> = emptyList()
    private var dayCounts: Map<Long, Int> = emptyMap()
    private var headerPositions: Map<Long, Int> = emptyMap()
    private var selectedDay = -1
    private var programmaticScroll = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityAiringCalendarBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initActivity(this)
        binding.calendarRoot.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
        }
        binding.calendarList.setPadding(0, 0, 0, navBarHeight + (24 * resources.displayMetrics.density).toInt())

        // fancy heading: gradient text that drops in, plus springy buttons
        binding.calendarTitle.setGradientText(
            getThemeColor(androidx.appcompat.R.attr.colorPrimary),
            getThemeColor(com.google.android.material.R.attr.colorTertiary)
        )
        binding.calendarTitle.fadeInDown()
        binding.calendarMonth.fadeInDown(120)
        binding.calendarToday.fadeInDown(160)
        binding.calendarBack.setPressBounce()
        binding.calendarCustomize.setPressBounce()
        binding.calendarToday.setPressBounce(0.9f)

        binding.calendarBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.calendarCustomize.setOnClickListener { showCustomizeSheet() }
        binding.calendarToday.setOnClickListener {
            val today = startOfDay(System.currentTimeMillis())
            val idx = dayStarts.indexOf(today)
            if (idx >= 0) selectDay(idx, scroll = true)
        }

        binding.calendarList.layoutManager = LinearLayoutManager(this)
        binding.calendarList.adapter = adapter
        binding.calendarList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) programmaticScroll = false
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (programmaticScroll || dy == 0) return
                val lm = recyclerView.layoutManager as LinearLayoutManager
                val pos = lm.findFirstVisibleItemPosition()
                val day = dayOfRow(adapter.rows.getOrNull(pos)) ?: return
                val idx = dayStarts.indexOf(day)
                if (idx >= 0 && idx != selectedDay) selectDay(idx, scroll = false)
            }
        })

        load()

        // keep countdowns + the summary fresh
        lifecycleScope.launch {
            while (true) {
                delay(30_000)
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && loadState == LoadState.READY) {
                    adapter.tick()
                    updateSummary()
                }
            }
        }
    }

    // region loading

    private fun load(force: Boolean = false) {
        val key = "${CalendarPrefs.source}|${CalendarPrefs.aheadDays}|${CalendarPrefs.pastDays}"
        if (!force && key == fetchKey && loadState == LoadState.READY) {
            applyFilters()
            return
        }
        loadJob?.cancel()
        loadState = LoadState.LOADING
        showLoading()
        loadJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { fetch() }
                raw = result
                fetchKey = key
                loadState = LoadState.READY
            } catch (e: NeedsLogin) {
                raw = emptyList()
                loadState = LoadState.NEEDS_LOGIN
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.log(e)
                raw = emptyList()
                loadState = LoadState.ERROR
            }
            applyFilters()
        }
    }

    private class NeedsLogin : Exception()

    private suspend fun fetch(): List<CalendarEntry> {
        val source = CalendarPrefs.source
        val ahead = CalendarPrefs.aheadDays
        val past = CalendarPrefs.pastDays

        val fromCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -past) }
        val toCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, ahead + 1) }
        val from = startOfDay(fromCal.timeInMillis) / 1000
        val to = startOfDay(toCal.timeInMillis) / 1000

        if (source == CalendarPrefs.SOURCE_ALL) {
            // the global schedule is big – keep it to a week ahead
            val cappedTo = minOf(to, startOfDay(Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 8)
            }.timeInMillis) / 1000)
            return Anilist.query.recentlyUpdated(from, cappedTo).mapNotNull { m ->
                val parts = m.relation?.split(",") ?: return@mapNotNull null
                val ep = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val at = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                CalendarEntry(m, ep, at, m.userStatus, m.userProgress)
            }.sortedBy { it.airingAt }
        }

        val userId = Anilist.userid ?: throw NeedsLogin()
        val library = libraryCache ?: (Anilist.query.getMediaLists(true, userId)["All"] ?: arrayListOf())
            .also { libraryCache = it }
        val byId = library.associateBy { it.id }

        // only shows that can still air are worth asking the schedule about
        val ids = library.filter {
            val st = it.status?.uppercase() ?: return@filter true
            st.contains("RELEASING") || st.contains("NOT")
        }.map { it.id }
        if (ids.isEmpty()) return emptyList()

        return Anilist.query.airingScheduleFor(ids, from, to).map { (media, ep, at) ->
            val own = byId[media.id]
            CalendarEntry(media, ep, at, own?.userStatus ?: media.userStatus, own?.userProgress ?: media.userProgress)
        }
    }

    // endregion

    // region presenting

    private fun showLoading() {
        binding.calendarProgress.visibility = View.VISIBLE
        binding.calendarEmpty.visibility = View.GONE
        binding.calendarSummaryCard.visibility = View.GONE
        adapter.submit(emptyList(), CalendarPrefs.compact, use24h())
    }

    private fun use24h(): Boolean = when (CalendarPrefs.timeFormat) {
        1 -> false
        2 -> true
        else -> DateFormat.is24HourFormat(this)
    }

    private fun applyFilters() {
        binding.calendarProgress.visibility = View.GONE

        val nowSec = System.currentTimeMillis() / 1000
        val mask = CalendarPrefs.statusMask
        val mine = CalendarPrefs.source == CalendarPrefs.SOURCE_MINE
        val entries = raw.filter { e ->
            val st = ListStatus.from(e.listStatus)
            val statusOk = !mine || (st != null && (mask and st.bit) != 0)
            val airedOk = !CalendarPrefs.hideAired || e.airingAt > nowSec
            statusOk && airedOk
        }.sortedBy { it.airingAt }

        buildStrip(entries)

        // group by local day
        val byDay = entries.groupBy { startOfDay(it.airingAt * 1000) }
        val rows = mutableListOf<CalendarRow>()
        val positions = mutableMapOf<Long, Int>()
        val dateFmt = SimpleDateFormat("MMM d", Locale.getDefault())
        byDay.keys.sorted().forEach { day ->
            val list = byDay.getValue(day)
            positions[day] = rows.size
            val count = if (list.size == 1) getString(R.string.cal_episode_count_one)
            else getString(R.string.cal_episodes_count, list.size)
            rows.add(CalendarRow.Header(day, dayTitle(day), "${dateFmt.format(day)} · $count"))
            list.forEach { rows.add(CalendarRow.Item(it)) }
        }
        headerPositions = positions
        adapter.submit(rows, CalendarPrefs.compact, use24h())

        if (rows.isEmpty()) showEmpty() else {
            binding.calendarEmpty.visibility = View.GONE
            binding.calendarList.scrollToPosition(0)
        }
        updateSummary()

        // land on today (or the first day that has something)
        val today = startOfDay(System.currentTimeMillis())
        val target = when {
            dayStarts.contains(today) && (byDay.containsKey(today) || byDay.isEmpty()) -> dayStarts.indexOf(today)
            else -> dayStarts.indexOfFirst { it >= today && byDay.containsKey(it) }.takeIf { it >= 0 }
                ?: dayStarts.indexOf(today)
        }
        if (target >= 0) selectDay(target, scroll = rows.isNotEmpty())
    }

    private fun showEmpty() {
        val title: Int
        val text: Int
        var action = R.string.cal_customize
        var onAction: () -> Unit = { showCustomizeSheet() }
        when (loadState) {
            LoadState.NEEDS_LOGIN -> {
                title = R.string.cal_login_title
                text = R.string.cal_login_text
                action = R.string.cal_source_all
                onAction = {
                    CalendarPrefs.source = CalendarPrefs.SOURCE_ALL
                    load()
                }
            }
            LoadState.ERROR -> {
                title = R.string.cal_error_title
                text = R.string.cal_error_text
                action = R.string.cal_retry
                onAction = { load(force = true) }
            }
            else -> {
                title = R.string.cal_empty_title
                text = R.string.cal_empty_text
            }
        }
        binding.calendarEmptyTitle.setText(title)
        binding.calendarEmptyText.setText(text)
        binding.calendarEmptyAction.setText(action)
        binding.calendarEmptyAction.setPressBounce(0.92f)
        binding.calendarEmptyAction.setOnClickListener { onAction() }
        binding.calendarEmpty.visibility = View.VISIBLE
    }

    private fun updateSummary() {
        if (loadState != LoadState.READY) {
            binding.calendarSummaryCard.visibility = View.GONE
            return
        }
        val nowSec = System.currentTimeMillis() / 1000
        val today = startOfDay(System.currentTimeMillis())
        val entries = adapter.rows.filterIsInstance<CalendarRow.Item>().map { it.entry }
        if (entries.isEmpty()) {
            binding.calendarSummaryCard.visibility = View.GONE
            return
        }
        val todayCount = entries.count { startOfDay(it.airingAt * 1000) == today }
        binding.calendarSummaryTitle.text = if (todayCount > 0) {
            getString(
                R.string.cal_summary_today,
                if (todayCount == 1) getString(R.string.cal_episode_count_one)
                else getString(R.string.cal_episodes_count, todayCount)
            )
        } else getString(R.string.cal_summary_nothing_today)

        val next = entries.firstOrNull { it.airingAt > nowSec }
        binding.calendarSummarySub.text = if (next != null) {
            getString(
                R.string.cal_summary_next,
                next.media.userPreferredName,
                next.episode,
                getString(R.string.cal_in_time, formatDuration(next.airingAt - nowSec))
            )
        } else getString(R.string.cal_summary_next_none)
        binding.calendarSummaryCard.visibility = View.VISIBLE
    }

    private fun dayTitle(dayStart: Long): String {
        val today = startOfDay(System.currentTimeMillis())
        val tomorrow = startOfDay(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis)
        val yesterday = startOfDay(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis)
        return when (dayStart) {
            today -> getString(R.string.cal_today)
            tomorrow -> getString(R.string.cal_tomorrow)
            yesterday -> getString(R.string.cal_yesterday)
            else -> SimpleDateFormat("EEEE", Locale.getDefault()).format(dayStart)
        }
    }

    private fun dayOfRow(row: CalendarRow?): Long? = when (row) {
        is CalendarRow.Header -> row.dayStart
        is CalendarRow.Item -> startOfDay(row.entry.airingAt * 1000)
        null -> null
    }

    // endregion

    // region day strip

    private fun buildStrip(entries: List<CalendarEntry>) {
        val past = CalendarPrefs.pastDays
        val ahead = if (CalendarPrefs.source == CalendarPrefs.SOURCE_ALL) minOf(CalendarPrefs.aheadDays, 7)
        else CalendarPrefs.aheadDays

        val starts = (-past..ahead).map { offset ->
            startOfDay(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }.timeInMillis)
        }
        dayStarts = starts
        dayCounts = entries.groupingBy { startOfDay(it.airingAt * 1000) }.eachCount()

        val strip = binding.calendarStrip
        strip.removeAllViews()
        val weekdayFmt = SimpleDateFormat("EEE", Locale.getDefault())
        val numberFmt = SimpleDateFormat("d", Locale.getDefault())
        val inflater = LayoutInflater.from(this)
        dayCells = starts.mapIndexed { index, day ->
            val cell = ItemCalendarDayBinding.inflate(inflater, strip, false)
            cell.dayWeekday.text = weekdayFmt.format(day)
            cell.dayNumber.text = numberFmt.format(day)
            val n = dayCounts[day] ?: 0
            cell.dayDots.text = "•".repeat(n.coerceAtMost(3))
            cell.root.setPressBounce(0.88f)
            cell.root.setOnClickListener { selectDay(index, scroll = true) }
            strip.addView(cell.root)
            cell
        }
        selectedDay = -1
    }

    private fun selectDay(index: Int, scroll: Boolean) {
        if (index !in dayStarts.indices) return
        val today = startOfDay(System.currentTimeMillis())
        val onPrimary = getThemeColor(com.google.android.material.R.attr.colorOnPrimary)
        val onSurface = getThemeColor(com.google.android.material.R.attr.colorOnSurface)
        val primary = getThemeColor(androidx.appcompat.R.attr.colorPrimary)

        dayCells.forEachIndexed { i, cell ->
            val selected = i == index
            val isToday = dayStarts[i] == today
            cell.root.setBackgroundResource(if (selected) R.drawable.bg_day_cell_selected else R.drawable.bg_day_cell)
            // lift the chosen day instead of scaling it, so nothing is ever clipped
            cell.root.animate().setStartDelay(0)
                .translationZ(if (selected) 8f * resources.displayMetrics.density else 0f)
                .setDuration(180).start()
            cell.dayWeekday.setTextColor(if (selected) onPrimary else onSurface)
            cell.dayNumber.setTextColor(if (selected) onPrimary else if (isToday) primary else onSurface)
            cell.dayDots.setTextColor(if (selected) onPrimary else primary)
            cell.dayWeekday.alpha = if (selected) 0.9f else 0.6f
        }
        selectedDay = index

        binding.calendarMonth.text =
            SimpleDateFormat("LLLL yyyy", Locale.getDefault()).format(dayStarts[index])

        // keep the chosen cell centred in the strip
        val cell = dayCells[index].root
        binding.calendarStripScroll.post {
            val target = cell.left - (binding.calendarStripScroll.width - cell.width) / 2
            binding.calendarStripScroll.smoothScrollTo(target.coerceAtLeast(0), 0)
        }

        if (scroll) scrollListToDay(dayStarts[index])
    }

    private fun scrollListToDay(day: Long) {
        // an empty day jumps to the next day that has something
        val pos = headerPositions[day]
            ?: headerPositions.filterKeys { it > day }.minByOrNull { it.key }?.value
            ?: return
        programmaticScroll = true
        val scroller = object : LinearSmoothScroller(this) {
            override fun getVerticalSnapPreference() = SNAP_TO_START
        }
        scroller.targetPosition = pos
        binding.calendarList.layoutManager?.startSmoothScroll(scroller)
    }

    // endregion

    // region customise sheet

    private fun showCustomizeSheet() {
        val sheet = BottomSheetDialog(this)
        val b = DialogCalendarCustomizeBinding.inflate(layoutInflater)
        sheet.setContentView(b.root)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true

        fun chip(group: ChipGroup, label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
            val chip = layoutInflater.inflate(R.layout.item_calendar_chip, group, false) as Chip
            chip.text = label
            chip.isChecked = checked
            chip.setOnCheckedChangeListener { _, isChecked -> onChecked(isChecked) }
            group.addView(chip)
        }

        fun refreshStatusVisibility() {
            val vis = if (CalendarPrefs.source == CalendarPrefs.SOURCE_MINE) View.VISIBLE else View.GONE
            b.calStatusLabel.visibility = vis
            b.calStatusGroup.visibility = vis
        }

        fun populate() {
            b.calSourceGroup.removeAllViews()
            b.calStatusGroup.removeAllViews()
            b.calAheadGroup.removeAllViews()
            b.calPastGroup.removeAllViews()

            chip(b.calSourceGroup, getString(R.string.cal_source_mine), CalendarPrefs.source == CalendarPrefs.SOURCE_MINE) {
                if (it) {
                    CalendarPrefs.source = CalendarPrefs.SOURCE_MINE
                    refreshStatusVisibility()
                    load()
                }
            }
            chip(b.calSourceGroup, getString(R.string.cal_source_all), CalendarPrefs.source == CalendarPrefs.SOURCE_ALL) {
                if (it) {
                    CalendarPrefs.source = CalendarPrefs.SOURCE_ALL
                    refreshStatusVisibility()
                    load()
                }
            }

            ListStatus.values().forEach { st ->
                chip(b.calStatusGroup, getString(CalendarAdapter.statusLabel(st)), (CalendarPrefs.statusMask and st.bit) != 0) { on ->
                    CalendarPrefs.statusMask =
                        if (on) CalendarPrefs.statusMask or st.bit else CalendarPrefs.statusMask and st.bit.inv()
                    load()
                }
            }

            CalendarPrefs.AHEAD_OPTIONS.forEach { d ->
                chip(b.calAheadGroup, getString(R.string.cal_days, d), CalendarPrefs.aheadDays == d) {
                    if (it) {
                        CalendarPrefs.aheadDays = d
                        load()
                    }
                }
            }
            CalendarPrefs.PAST_OPTIONS.forEach { d ->
                val label = when (d) {
                    0 -> getString(R.string.cal_off)
                    1 -> getString(R.string.cal_day, d)
                    else -> getString(R.string.cal_days, d)
                }
                chip(b.calPastGroup, label, CalendarPrefs.pastDays == d) {
                    if (it) {
                        CalendarPrefs.pastDays = d
                        load()
                    }
                }
            }
            refreshStatusVisibility()
        }
        populate()

        b.calHideAired.isChecked = CalendarPrefs.hideAired
        b.calHideAired.setOnCheckedChangeListener { _, on ->
            CalendarPrefs.hideAired = on
            load()
        }
        b.calCompact.isChecked = CalendarPrefs.compact
        b.calCompact.setOnCheckedChangeListener { _, on ->
            CalendarPrefs.compact = on
            load()
        }
        b.cal24h.isChecked = use24h()
        b.cal24h.setOnCheckedChangeListener { _, on ->
            CalendarPrefs.timeFormat = if (on) 2 else 1
            load()
        }
        b.calReset.setOnClickListener {
            CalendarPrefs.reset()
            sheet.dismiss()
            load()
        }
        sheet.show()
    }

    // endregion

    private fun openMedia(media: Media) {
        startActivity(
            Intent(this, MediaDetailsActivity::class.java).putExtra("media", media as Serializable)
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::binding.isInitialized) binding.calendarList.adapter = null
    }
}
