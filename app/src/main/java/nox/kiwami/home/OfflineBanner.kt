package nox.kiwami.home

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.core.widget.ImageViewCompat
import nox.kiwami.R
import nox.kiwami.databinding.ViewOfflineBannerBinding
import nox.kiwami.statusBarHeight

/**
 * The "You're offline" banner floating at the top of Home in Offline mode.
 * Shimmering title + pulsing icon; turns green ("Back online") when the connection returns.
 */
class OfflineBanner(
    private val view: View,
    private val b: ViewOfflineBannerBinding
) {
    private val ctx = view.context
    private val density = ctx.resources.displayMetrics.density
    private val offlineColor = ContextCompat.getColor(ctx, R.color.offline_accent)
    private val onlineColor = ContextCompat.getColor(ctx, R.color.online_accent)

    private var shimmer: ValueAnimator? = null
    private var pulse: ObjectAnimator? = null
    private var recovered = false

    fun show() {
        b.root.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
            topMargin = statusBarHeight + (12 * density).toInt()
        }
        b.root.visibility = View.VISIBLE
        b.root.alpha = 0f
        b.root.translationY = -30f * density
        b.root.animate()
            .alpha(1f).translationY(0f)
            .setStartDelay(250L).setDuration(520L)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()
        startAnimations()
    }

    fun setRecovered(value: Boolean) {
        if (value == recovered) return
        recovered = value
        val color = if (value) onlineColor else offlineColor
        b.root.setBackgroundResource(if (value) R.drawable.bg_online_pill else R.drawable.bg_offline_pill)
        b.offlineBannerPulse.setBackgroundResource(
            if (value) R.drawable.bg_online_pulse else R.drawable.bg_offline_pulse
        )
        b.offlineBannerIcon.setImageResource(if (value) R.drawable.ic_wifi_on_24 else R.drawable.ic_wifi_off_24)
        ImageViewCompat.setImageTintList(b.offlineBannerIcon, ColorStateList.valueOf(color))
        b.offlineBannerTitle.setText(if (value) R.string.offline_banner_back_title else R.string.offline_banner_title)
        b.offlineBannerSubtitle.setText(
            if (value) R.string.offline_banner_back_subtitle else R.string.offline_banner_subtitle
        )
        startAnimations()
    }

    fun release() {
        shimmer?.cancel(); shimmer = null
        pulse?.cancel(); pulse = null
        b.root.animate().cancel()
    }

    private fun startAnimations() {
        shimmer?.cancel()
        pulse?.cancel()
        val accent = if (recovered) onlineColor else offlineColor

        pulse = ObjectAnimator.ofPropertyValuesHolder(
            b.offlineBannerPulse,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.85f, 1.25f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.85f, 1.25f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.7f, 0f)
        ).apply {
            duration = 1800L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            start()
        }

        // light sweeping across the title text
        val title = b.offlineBannerTitle
        title.post {
            val w = title.width.toFloat().coerceAtLeast(1f)
            val shader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(accent, accent, 0xFFFFFFFF.toInt(), accent, accent),
                floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
            val m = Matrix()
            title.paint.shader = shader
            shimmer = ValueAnimator.ofFloat(-w, w).apply {
                duration = 2400L
                repeatCount = ValueAnimator.INFINITE
                startDelay = 600L
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    m.setTranslate(it.animatedValue as Float, 0f)
                    shader.setLocalMatrix(m)
                    title.invalidate()
                }
                start()
            }
        }
    }
}
