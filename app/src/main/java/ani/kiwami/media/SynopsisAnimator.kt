package ani.kiwami.media

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import ani.kiwami.util.SpringInterpolator
import kotlin.math.PI
import kotlin.math.sin

/**
 * Synopsis expand / collapse.
 *
 * The text box physically grows and shrinks (a real height animation, not a line-by-line jump):
 *  - expanding rides a soft spring, so it swells open and settles with a tiny overshoot
 *  - collapsing glides shut with a gentle overshoot
 *  - while it moves, the faded lower edge widens into a curtain that sweeps the text in or out
 *  - the "Read more / Read less" label drops out and springs back in with the new wording
 */
object SynopsisAnimator {

    private const val REST_FADE_SPAN = 0.85f
    private const val CURTAIN_LINES = 2.4f

    fun toggle(
        tv: FadingTextView,
        expand: Boolean,
        collapsedLines: Int,
        readMore: TextView,
        newLabel: String
    ) {
        tv.heightAnimator?.cancel()
        tv.fadeAnimator?.cancel()
        swapLabel(readMore, newLabel)

        val width = tv.width
        if (width <= 0) { // not laid out yet, nothing to animate
            tv.maxLines = if (expand) Int.MAX_VALUE else collapsedLines
            tv.fadeAmount = if (expand) 0f else 1f
            return
        }

        val startH = tv.height
        // where we are heading
        tv.maxLines = if (expand) Int.MAX_VALUE else collapsedLines
        tv.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val endH = tv.measuredHeight
        if (endH == startH) {
            tv.fadeAmount = if (expand) 0f else 1f
            tv.layoutParams = tv.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
            return
        }

        // every line stays laid out while the box moves; the box height clips them
        tv.maxLines = Int.MAX_VALUE
        tv.layoutParams = tv.layoutParams.apply { height = startH }

        val shape = if (expand) SpringInterpolator(4.8f, 1.1f) else OvershootInterpolator(0.9f)
        val fadeFrom = tv.fadeAmount
        val fadeTo = if (expand) 0f else 1f

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (expand) 780 else 520
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedFraction
                val v = shape.getInterpolation(t)
                val lp = tv.layoutParams
                lp.height = (startH + (endH - startH) * v).toInt().coerceAtLeast(1)
                tv.layoutParams = lp
                // curtain: the soft edge widens mid-motion, then tightens back up
                val p = v.coerceIn(0f, 1f)
                tv.fadeSpan = REST_FADE_SPAN + (CURTAIN_LINES - REST_FADE_SPAN) * sin(PI * t).toFloat()
                tv.fadeAmount = fadeFrom + (fadeTo - fadeFrom) * p
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    tv.maxLines = if (expand) Int.MAX_VALUE else collapsedLines
                    tv.layoutParams = tv.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
                    tv.fadeSpan = REST_FADE_SPAN
                    tv.fadeAmount = fadeTo
                }
            })
        }
        tv.heightAnimator = animator
        animator.start()
    }

    private fun swapLabel(label: TextView, text: String) {
        val d = label.resources.displayMetrics.density
        label.animate().cancel()
        label.animate()
            .alpha(0f).translationY(6 * d)
            .setDuration(110)
            .withEndAction {
                label.text = text
                label.translationY = -6 * d
                label.animate()
                    .alpha(1f).translationY(0f)
                    .setDuration(420)
                    .setInterpolator(OvershootInterpolator(2.2f))
                    .start()
            }
            .start()
    }
}
