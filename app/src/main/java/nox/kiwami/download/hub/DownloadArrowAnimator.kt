package nox.kiwami.download.hub

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Download arrow animation: a single arrow accelerates down and out of the bottom of its clipped
 * box, a short beat of nothing, then the same arrow glides in from the top, overshoots a touch and
 * settles before the next drop. Only one arrow is ever visible. Shared by the home download button
 * and the Downloads banner so both match.
 */
class DownloadArrowAnimator(
    private val icon: View,
    private val travelPx: Float
) {
    private var animator: ValueAnimator? = null

    val isRunning: Boolean get() = animator != null

    fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { icon.translationY = offsetFor(it.animatedValue as Float) }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
        icon.translationY = 0f
    }

    private fun offsetFor(f: Float): Float = when {
        // 1) drop out of the bottom, accelerating
        f < 0.38f -> {
            val t = f / 0.38f
            travelPx * t * t
        }
        // 2) a beat with the arrow hidden
        f < 0.5f -> -travelPx
        // 3) glide in from the top with a small overshoot
        f < 0.92f -> {
            val t = (f - 0.5f) / 0.42f
            -travelPx * (1f - easeOutBack(t))
        }
        // 4) rest
        else -> 0f
    }

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.4f
        val c3 = c1 + 1f
        val u = t - 1f
        return 1f + c3 * u * u * u + c1 * u * u
    }
}
