package ani.dantotsu.download.hub

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.PathInterpolator

/**
 * "Conveyor" animation for the download arrow: the arrow drops out of the bottom of its clipped box
 * while an identical arrow glides in from the top, easing in and out each cycle so it ticks
 * rhythmically instead of just fading. Used by the home button and the Downloads banner so both match.
 */
class DownloadArrowAnimator(
    private val icon: View,
    private val ghost: View,
    private val travelPx: Float
) {
    private var animator: ValueAnimator? = null

    val isRunning: Boolean get() = animator != null

    fun start() {
        if (animator != null) return
        ghost.visibility = View.VISIBLE
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1250
            repeatCount = ValueAnimator.INFINITE
            interpolator = PathInterpolator(0.65f, 0f, 0.25f, 1f)
            addUpdateListener {
                val f = it.animatedValue as Float
                icon.translationY = f * travelPx
                ghost.translationY = (f - 1f) * travelPx
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
        icon.translationY = 0f
        ghost.translationY = 0f
        ghost.visibility = View.GONE
    }
}
