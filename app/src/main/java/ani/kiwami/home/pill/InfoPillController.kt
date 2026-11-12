package ani.kiwami.home.pill

import android.content.Intent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import ani.kiwami.R
import ani.kiwami.calendar.AiringCalendarActivity
import ani.kiwami.connections.anilist.Anilist
import ani.kiwami.connections.anilist.AnilistHomeViewModel
import ani.kiwami.databinding.FragmentHomeBinding
import ani.kiwami.home.pill.InfoPillStore.AiringItem
import ani.kiwami.isOnline
import ani.kiwami.media.Media
import ani.kiwami.media.MediaDetailsActivity
import ani.kiwami.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable
import java.util.Calendar

/**
 * Drives the slim info pill under the Anime / Manga list buttons (see [InfoPillView]).
 *
 * Cards, most relevant first (a card without data is simply not in the cycle):
 *  - Up next        next episode from your list, live countdown. Tap the text -> airing calendar
 *  - Airing today   how many episodes from your list air today
 *  - Time today     watch + reading time today ([WatchTimeTracker])
 *  - Backlog        planned list size, and how many were not touched for 30+ days
 *  - Feeling lucky  tap the text -> info page of a random planned show (needs network)
 *
 * Tapping the bar only cycles to the next card; swiping goes forward / back. The two cards with
 * an action (Up next -> calendar, Feeling lucky -> random pick) show a small round button on the
 * left of the bar, and only that button runs the action.
 *
 * Offline / Pre-cached: everything comes from on-device data (airing cache, airing alarms,
 * cached Home lists, local watch time). Only the lucky card needs the network and is skipped.
 */
class InfoPillController(
    private val b: FragmentHomeBinding,
    private val owner: LifecycleOwner,
    private val model: AnilistHomeViewModel
) {

    private val ctx = b.root.context
    private val d = ctx.resources.displayMetrics.density
    private val pill = InfoPillView(ctx)
    private val cat = SleepyCatView(ctx)

    // Holds the pill and the cat. It is the view that sits in the Home layout (so all the spacing
    // maths below works on it); it does not clip, so the cat can lie on the pill's top edge in the
    // gap above it without changing that gap.
    private val host = FrameLayout(ctx).apply {
        clipChildren = false
        clipToPadding = false
    }

    // ---- cards --------------------------------------------------------------------------------

    private sealed interface Card {
        val key: String

        data class UpNext(val item: AiringItem) : Card { override val key = "next" }
        data class AiringToday(val total: Int, val aired: Int) : Card { override val key = "airing" }
        data class TimeToday(val minutes: WatchTimeTracker.Minutes) : Card { override val key = "time" }
        data class Backlog(val total: Int, val stale: Int, val reliable: Boolean) : Card { override val key = "backlog" }
        object Lucky : Card { override val key = "lucky" }
    }

    private var cards: List<Card> = emptyList()
    private var index = 0

    private var cachedAiring: List<AiringItem> = emptyList()
    private var planned: InfoPillStore.PlannedSnapshot? = null
    private var refreshingAiring = false
    private var lastLuckyId = -1

    private var released = false
    private var shownOnce = false
    private var bannerExtra = 0
    private var ticks = 0

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (released) return
            tick()
            pill.postDelayed(this, 1000L)
        }
    }

    // ---- setup --------------------------------------------------------------------------------

    fun start() {
        val parent = b.homeTopContainer
        val at = parent.indexOfChild(b.homeListContainer) + 1
        host.visibility = View.GONE
        host.addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        host.addView(
            cat,
            FrameLayout.LayoutParams(dp(CAT_W_DP), dp(CAT_H_DP), Gravity.TOP or Gravity.END).apply {
                // the cat's feet end 2dp below the top of the pill (it lies on the edge)
                topMargin = -(dp(CAT_H_DP) - dp(2))
                marginEnd = dp(10)
            }
        )
        parent.addView(
            host, at,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(PILL_HEIGHT_DP)).apply {
                // lines up with the outer edges of the two list buttons (24dp padding + 8dp margin)
                marginStart = dp(32)
                marginEnd = dp(32)
                topMargin = 0 // set by alignGap() once the list buttons are measured
                bottomMargin = dp(16)
            }
        )
        pill.onTap = { cat.nudge(); next() }
        pill.onAction = { runAction() }
        pill.onSwipe = { forward -> cat.nudge(); if (forward) next() else previous() }
        pill.onResized = { if (!released) rebuild() }
        // keep the gap under the list buttons equal to the gap between the two buttons (16dp)
        b.homeListContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> pill.post { alignGap() } }

        cachedAiring = InfoPillStore.loadAiring()?.items ?: emptyList()
        planned = InfoPillStore.loadPlanned()

        model.getAnimeContinue().observe(owner) { maybeRefreshAiring(); rebuild() }
        model.getAnimePlanned().observe(owner) { onPlannedChanged(); maybeRefreshAiring(); rebuild() }
        model.getMangaPlanned().observe(owner) { onPlannedChanged(); rebuild() }

        rebuild()
        pill.postDelayed(tickRunnable, 1000L)
    }

    fun release() {
        released = true
        pill.removeCallbacks(tickRunnable)
        pill.onTap = null
        pill.onAction = null
        pill.onSwipe = null
        pill.onResized = null
    }

    /** Home came back to the front (from a media page, the player, the calendar...). */
    fun onReturn() {
        if (released) return
        maybeRefreshAiring()
        rebuild()
    }

    private fun dp(v: Int) = (v * d).toInt()

    // ---- data ---------------------------------------------------------------------------------

    private fun updatedSec(m: Media): Long {
        val v = m.userUpdatedAt ?: 0L
        return if (v > 100_000_000_000L) v / 1000 else v // seconds or milliseconds
    }

    private fun onPlannedChanged() {
        val a = model.getAnimePlanned().value
        val m = model.getMangaPlanned().value
        if (a == null && m == null) return
        val old = planned ?: InfoPillStore.loadPlanned()
        val snap = InfoPillStore.PlannedSnapshot(
            System.currentTimeMillis(),
            a?.filter { it.id > 0 }?.map { updatedSec(it) } ?: old?.anime ?: emptyList(),
            m?.filter { it.id > 0 }?.map { updatedSec(it) } ?: old?.manga ?: emptyList()
        )
        planned = snap
        InfoPillStore.savePlanned(snap)
    }

    /** Asks AniList for the airing times of the shows on the list. Cached; skipped offline. */
    private fun maybeRefreshAiring() {
        if (released || refreshingAiring || !isOnline(ctx)) return
        val ids = (model.getAnimeContinue().value.orEmpty() + model.getAnimePlanned().value.orEmpty())
            .filter {
                if (it.id <= 0) return@filter false
                val st = it.status?.uppercase() ?: return@filter true
                st.contains("RELEASING") || st.contains("NOT")
            }
            .map { it.id }.distinct().sorted()
        if (ids.isEmpty()) return

        val key = ids.hashCode()
        val now = System.currentTimeMillis()
        val cache = InfoPillStore.loadAiring()
        if (cache != null && cache.idsKey == key && now - cache.fetchedAtMs < AIRING_REFRESH_MS) return

        refreshingAiring = true
        owner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val from = startOfToday() / 1000 - 1
                val to = now / 1000 + 7 * 24 * 3600
                val items = Anilist.query.airingScheduleFor(ids, from, to)
                    .map { (m, ep, at) -> AiringItem(m.id, m.userPreferredName, ep, at) }
                // a failed request comes back empty: do not wipe good data because of it
                val keepOld = items.isEmpty() &&
                        cache?.items?.any { it.airingAtSec * 1000 > now } == true
                if (!keepOld) {
                    InfoPillStore.saveAiring(InfoPillStore.AiringCache(now, key, items))
                    withContext(Dispatchers.Main) {
                        cachedAiring = items
                        rebuild()
                    }
                }
            } catch (e: Exception) {
                Logger.log(e)
            } finally {
                refreshingAiring = false
            }
        }
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun luckyPool(): List<Media> {
        val anime = model.getAnimePlanned().value.orEmpty().filter { it.id > 0 }
        return anime.ifEmpty { model.getMangaPlanned().value.orEmpty().filter { it.id > 0 } }
    }

    // ---- building the cycle -------------------------------------------------------------------

    private fun buildCards(): List<Card> {
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000
        val airing = InfoPillStore.allAiring(cachedAiring)

        val upNext = airing.filter { it.airingAtSec > nowSec }.minByOrNull { it.airingAtSec }
            ?.let { Card.UpNext(it) }
        val soon = upNext != null && upNext.item.airingAtSec - nowSec <= SOON_SECONDS

        val dayStart = startOfToday() / 1000
        val today = airing.filter { it.airingAtSec in dayStart until dayStart + 24 * 3600 }
        val airingToday = if (today.isEmpty()) null
        else Card.AiringToday(today.size, today.count { it.airingAtSec <= nowSec })

        val minutes = WatchTimeTracker.todayMinutes()
        val time = if (minutes.total >= 1) Card.TimeToday(minutes) else null

        val backlog = planned?.let { p ->
            val total = p.anime.size + p.manga.size
            if (total == 0) null else {
                val all = p.anime + p.manga
                val known = all.count { it > 0 }
                // "untouched" = no change to the list entry for 30 days. It is only trusted when
                // AniList gave us the update time for most of the entries.
                val reliable = known * 2 >= total
                val limit = nowSec - 30L * 24 * 3600
                Card.Backlog(total, all.count { it in 1..limit }, reliable)
            }
        }

        val lucky = if (isOnline(ctx) && luckyPool().isNotEmpty()) Card.Lucky else null

        return listOfNotNull(
            upNext.takeIf { soon },
            airingToday,
            time,
            upNext.takeIf { !soon },
            backlog,
            lucky
        )
    }

    private fun rebuild() {
        if (released) return
        val previousKey = cards.getOrNull(index)?.key
        cards = buildCards()
        index = cards.indexOfFirst { it.key == previousKey }.takeIf { it >= 0 } ?: 0
        val cardChanged = cards.getOrNull(index)?.key != previousKey
        applyVisibility()
        val card = cards.getOrNull(index) ?: return
        pill.setPages(cards.size, index, animate = false)
        syncAction()
        val text = render(card)
        pill.setReadableText(text)
        if (cardChanged) pill.showText(text, animate = shownOnce && host.isVisible) else pill.updateText(text)
    }

    /** Visible only when there is something to show and the list buttons are already on screen. */
    private fun applyVisibility() {
        val shouldShow = cards.isNotEmpty() && b.homeAnimeList.isVisible
        if (shouldShow == host.isVisible) return
        if (shouldShow) {
            val card = cards.getOrNull(index)
            if (card != null) pill.showText(render(card), animate = false)
            host.visibility = View.VISIBLE
            setBannerExtra(bannerNeeded())
            if (!shownOnce) {
                shownOnce = true
                host.alpha = 0f
                host.translationY = 8f * d
                host.animate().alpha(1f).translationY(0f).setStartDelay(260).setDuration(380)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
        } else {
            host.visibility = View.GONE
            setBannerExtra(0)
        }
    }

    /** Space between the list buttons and the pill = space between the two list buttons. */
    private fun alignGap() {
        if (released) return
        val list = b.homeListContainer
        if (list.height <= 0) return
        val lowest = maxOf(b.homeAnimeList.bottom, b.homeMangaList.bottom)
        val wanted = dp(GAP_DP) - (list.height - lowest) // the container already has some room below
        val lp = host.layoutParams as LinearLayout.LayoutParams
        if (lp.topMargin == wanted) return
        lp.topMargin = wanted
        host.layoutParams = lp
        if (host.isVisible) setBannerExtra(bannerNeeded())
    }

    private fun bannerNeeded(): Int {
        val lp = host.layoutParams as LinearLayout.LayoutParams
        return lp.height + lp.topMargin + lp.bottomMargin
    }

    /** The banner behind the header is a fixed height: grow it by the pill's height. */
    private fun setBannerExtra(extra: Int) {
        val delta = extra - bannerExtra
        if (delta == 0) return
        bannerExtra = extra
        b.homeUserBg.updateLayoutParams { height += delta }
        b.homeUserBgNoKen.updateLayoutParams { height += delta }
    }

    // ---- text ---------------------------------------------------------------------------------

    private fun render(card: Card): CharSequence {
        val r = ctx.resources
        return when (card) {
            is Card.UpNext -> {
                val left = card.item.airingAtSec - System.currentTimeMillis() / 1000
                pill.fitTitle(
                    card.item.name,
                    ctx.getString(R.string.pill_up_next_suffix, card.item.episode, countdown(left))
                )
            }

            is Card.AiringToday -> when {
                card.aired == 0 -> r.getQuantityString(R.plurals.pill_airing_today, card.total, card.total)
                card.aired >= card.total -> r.getQuantityString(R.plurals.pill_airing_today_done, card.total, card.total)
                else -> ctx.getString(R.string.pill_airing_today_partial, card.total, card.aired)
            }

            is Card.TimeToday -> {
                val m = card.minutes
                when {
                    m.manga == 0 -> ctx.getString(R.string.pill_time_watched, minutesText(m.anime))
                    m.anime == 0 -> ctx.getString(R.string.pill_time_read, minutesText(m.manga))
                    else -> ctx.getString(R.string.pill_time_both, minutesText(m.total))
                }
            }

            is Card.Backlog -> when {
                !card.reliable -> ctx.getString(R.string.pill_backlog_plain, card.total)
                card.stale > 0 -> ctx.getString(R.string.pill_backlog_stale, card.total, card.stale)
                else -> ctx.getString(R.string.pill_backlog_fresh, card.total)
            }

            Card.Lucky -> ctx.getString(R.string.pill_lucky)
        }
    }

    private fun minutesText(min: Int): String = when {
        min < 60 -> "${min}m"
        min % 60 == 0 -> "${min / 60}h"
        else -> "${min / 60}h ${min % 60}m"
    }

    private fun countdown(sec: Long): String {
        val s = sec.coerceAtLeast(0)
        val days = s / 86_400
        val hours = (s % 86_400) / 3600
        val mins = (s % 3600) / 60
        return when {
            days >= 1 -> "${days}d ${hours}h"
            hours >= 1 -> "${hours}h ${mins}m"
            s >= 120 -> "${mins}m"
            s >= 60 -> "${mins}m ${s % 60}s"
            else -> "${s}s"
        }
    }

    // ---- live updates -------------------------------------------------------------------------

    private fun tick() {
        if (!pill.isAttachedToWindow || pill.windowVisibility != View.VISIBLE) return
        ticks++
        val card = cards.getOrNull(index)

        if (!host.isVisible) {
            // waiting for the list buttons to appear (cheap), or for data (slower)
            if (cards.isNotEmpty()) applyVisibility() else if (ticks % 5 == 0) rebuild()
            return
        }

        if (card is Card.UpNext) {
            val nowSec = System.currentTimeMillis() / 1000
            if (card.item.airingAtSec <= nowSec) rebuild() // it just aired: move on to the next one
            else pill.updateText(render(card))
        }
        if (ticks % 30 == 0) rebuild()
    }

    // ---- taps ---------------------------------------------------------------------------------

    private fun next() {
        if (cards.size < 2) return
        index = (index + 1) % cards.size
        pill.setPages(cards.size, index, animate = true)
        syncAction()
        val text = render(cards[index])
        pill.setReadableText(text)
        pill.showText(text, animate = true, forward = true)
    }

    private fun previous() {
        if (cards.size < 2) return
        index = (index - 1 + cards.size) % cards.size
        pill.setPages(cards.size, index, animate = true)
        syncAction()
        val text = render(cards[index])
        pill.setReadableText(text)
        pill.showText(text, animate = true, forward = false)
    }

    private fun runAction() {
        when (cards.getOrNull(index)) {
            is Card.UpNext -> ctx.startActivity(Intent(ctx, AiringCalendarActivity::class.java))
            Card.Lucky -> pickRandom()
            else -> Unit
        }
    }

    /** Shows the round button only on the cards that have an action. */
    private fun syncAction() {
        pill.setAction(
            when (cards.getOrNull(index)) {
                is Card.UpNext -> InfoPillView.Action.CALENDAR
                Card.Lucky -> InfoPillView.Action.DICE
                else -> null
            }
        )
    }

    private fun pickRandom() {
        if (!isOnline(ctx)) {
            rebuild() // went offline in the meantime: the card drops out of the cycle
            return
        }
        val pool = luckyPool()
        if (pool.isEmpty()) return
        val choices = if (pool.size > 1) pool.filter { it.id != lastLuckyId } else pool
        val media = choices.random()
        lastLuckyId = media.id
        pill.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        ctx.startActivity(
            Intent(ctx, MediaDetailsActivity::class.java).putExtra("media", media as Serializable)
        )
    }

    private companion object {
        const val PILL_HEIGHT_DP = 24
        const val CAT_W_DP = 56
        const val CAT_H_DP = 32
        const val GAP_DP = 16
        const val SOON_SECONDS = 3 * 3600L
        const val AIRING_REFRESH_MS = 30 * 60_000L
    }
}
