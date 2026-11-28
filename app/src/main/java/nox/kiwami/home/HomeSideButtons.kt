package nox.kiwami.home

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import nox.kiwami.calendar.AiringCalendarActivity
import nox.kiwami.databinding.ItemNavbarBinding
import nox.kiwami.download.hub.DownloadActivityState
import nox.kiwami.download.hub.DownloadArrowAnimator
import nox.kiwami.download.hub.DownloadRingAnimator
import nox.kiwami.download.hub.DownloadsActivity
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import nox.kiwami.util.ThemeSurface
import nox.kiwami.util.setPressBounce
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class HomeSideButtons(
    private val activity: AppCompatActivity,
    private val nav: ItemNavbarBinding
) {
    private val density = activity.resources.displayMetrics.density

    private val arrow = DownloadArrowAnimator(nav.downloadsIcon, 32f * density)
    private val ring = DownloadRingAnimator(nav.downloadsProgress)
    private var wasActive = false
    private var started = false

    fun attach() {
        if (ThemeSurface.isOled(activity)) {
            OledNavStyle.circle(nav.calendarButton)
            OledNavStyle.circle(nav.downloadsButton)
            NavGlow.wrap(nav.calendarButton, GradientDrawable.OVAL, 0f)
            NavGlow.wrap(nav.downloadsButton, GradientDrawable.OVAL, 0f)
        }
        nav.calendarButton.setPressBounce(0.82f)
        nav.downloadsButton.setPressBounce(0.82f)
        nav.calendarButton.setOnClickListener { openCalendar() }
        nav.downloadsButton.setOnClickListener {
            activity.startActivity(Intent(activity, DownloadsActivity::class.java))
        }
        adaptToScreenWidth()
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    render(DownloadActivityState.snapshot())
                    delay(if (wasActive) 450L else 1000L)
                }
            }
        }
    }

    private fun openCalendar() {
        activity.startActivity(Intent(activity, AiringCalendarActivity::class.java))
    }

    private fun adaptToScreenWidth() {
        val widthDp = activity.resources.configuration.screenWidthDp
        val size = ((if (widthDp in 1..399) 48 else 56) * density).toInt()
        listOf(nav.calendarButton, nav.downloadsButton).forEach {
            it.layoutParams = it.layoutParams.apply { width = size; height = size }
        }
    }

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
            ring.setPercent(p)
        } else {
            stopDownloading()
        }
        wasActive = state.isActive
    }

    private fun startDownloading() {
        if (arrow.isRunning) return
        nav.downloadsProgress.animate().cancel()
        nav.downloadsProgress.animate().setStartDelay(0).alpha(1f).setDuration(250).start()
        ring.start()
        arrow.start()

        val badge = nav.downloadsBadge
        badge.visibility = View.VISIBLE
        badge.animate().setListener(null).cancel()
        badge.animate().scaleX(1f).scaleY(1f).setDuration(220)
            .setInterpolator(OvershootInterpolator(2f)).start()
    }

    private fun stopDownloading() {
        if (!arrow.isRunning) return
        arrow.stop()

        ring.finish {
            nav.downloadsProgress.animate().alpha(0f).setStartDelay(120).setDuration(320)
                .withEndAction { ring.reset() }
                .start()
        }
        val badge = nav.downloadsBadge
        badge.animate().setListener(null).cancel()
        badge.animate().scaleX(0f).scaleY(0f).setDuration(200)
            .withEndAction { badge.visibility = View.GONE }
            .start()

        if (wasActive) {
            nav.downloadsButton.animate().setStartDelay(0).scaleX(1.14f).scaleY(1.14f).setDuration(140)
                .withEndAction {
                    nav.downloadsButton.animate().setStartDelay(0).scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(OvershootInterpolator(2f)).start()
                }.start()
        }
    }
}
