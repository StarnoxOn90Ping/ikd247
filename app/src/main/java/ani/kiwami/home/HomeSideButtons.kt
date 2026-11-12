package ani.kiwami.home

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import ani.kiwami.calendar.AiringCalendarActivity
import ani.kiwami.databinding.ItemNavbarBinding
import ani.kiwami.download.hub.DownloadActivityState
import ani.kiwami.download.hub.DownloadArrowAnimator
import ani.kiwami.download.hub.DownloadsActivity
import ani.kiwami.settings.saving.PrefManager
import ani.kiwami.settings.saving.PrefName
import ani.kiwami.util.setPressBounce
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Drives the two circles that flank the bottom navigation pill:
 *  - the calendar button (left)
 *  - the downloads button (right), which animates + shows a badge while anything is downloading
 */
class HomeSideButtons(
    private val activity: AppCompatActivity,
    private val nav: ItemNavbarBinding
) {
    private val density = activity.resources.displayMetrics.density

    private val arrow = DownloadArrowAnimator(nav.downloadsIcon, 32f * density) // (40dp clip box + 24dp arrow) / 2
    private var wasActive = false
    private var started = false

    fun attach() {
        // springy press feedback on both circles
        nav.calendarButton.setPressBounce(0.82f)
        nav.downloadsButton.setPressBounce(0.82f)
        nav.calendarButton.setOnClickListener { openCalendar() }
        nav.downloadsButton.setOnClickListener {
            activity.startActivity(Intent(activity, DownloadsActivity::class.java))
        }
        adaptToScreenWidth()
        // poll the downloader singletons – cheap, and only does real work while the screen is visible
        activity.lifecycleScope.launch {
            while (true) {
                if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    render(DownloadActivityState.snapshot())
                }
                delay(if (wasActive) 450L else 1000L)
            }
        }
    }

    private fun openCalendar() {
        activity.startActivity(Intent(activity, AiringCalendarActivity::class.java))
    }

    /** Shrinks the circles a little on very narrow phones so the gaps never get cramped. */
    private fun adaptToScreenWidth() {
        val widthDp = activity.resources.configuration.screenWidthDp
        val size = ((if (widthDp in 1..399) 48 else 56) * density).toInt()
        listOf(nav.calendarButton, nav.downloadsButton).forEach {
            it.layoutParams = it.layoutParams.apply { width = size; height = size }
        }
    }

    /** Pops both circles in – called when the pill itself becomes visible. */
    fun reveal() {
        if (started) return
        started = true
        listOf(nav.calendarButton, nav.downloadsButton).forEachIndexed { i, v ->
            v.visibility = View.VISIBLE
            v.scaleX = 0f
            v.scaleY = 0f
            v.alpha = 0f
            v.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setStartDelay(120L + i * 70L)
                .setDuration(380)
                .setInterpolator(OvershootInterpolator(1.6f))
                .withEndAction { v.animate().setStartDelay(0) }
                .start()
        }
    }

    /** Keeps the circles on the same baseline as the pill when its bottom margin changes. */
    fun syncBottomMargin(bottomPx: Int) {
        listOf(nav.calendarButton, nav.downloadsButton).forEach {
            val lp = it.layoutParams as ViewGroup.MarginLayoutParams
            lp.bottomMargin = bottomPx
            it.layoutParams = lp
        }
    }

    private fun render(state: DownloadActivityState) {
        if (state.isActive) {
            startDownloading()
            val p = if (state.percent < 0) 4 else state.percent.coerceAtLeast(4)
            nav.downloadsProgress.setProgressCompat(p, true)
        } else {
            stopDownloading()
        }
        wasActive = state.isActive
    }

    private fun startDownloading() {
        if (arrow.isRunning) return
        nav.downloadsProgress.animate().alpha(1f).setDuration(250).start()
        arrow.start()

        // the red dot just appears and then stays put
        val badge = nav.downloadsBadge
        badge.visibility = View.VISIBLE
        badge.animate().setListener(null).cancel()
        badge.animate().scaleX(1f).scaleY(1f).setDuration(220)
            .setInterpolator(OvershootInterpolator(2f)).start()
    }

    private fun stopDownloading() {
        if (!arrow.isRunning) return
        arrow.stop()

        nav.downloadsProgress.animate().alpha(0f).setDuration(300).start()
        val badge = nav.downloadsBadge
        badge.animate().setListener(null).cancel()
        badge.animate().scaleX(0f).scaleY(0f).setDuration(200)
            .withEndAction { badge.visibility = View.GONE }
            .start()

        // little "all done" bounce on the whole circle
        if (wasActive) {
            nav.downloadsButton.animate().setStartDelay(0).scaleX(1.14f).scaleY(1.14f).setDuration(140)
                .withEndAction {
                    nav.downloadsButton.animate().setStartDelay(0).scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(OvershootInterpolator(2f)).start()
                }.start()
        }
    }
}
