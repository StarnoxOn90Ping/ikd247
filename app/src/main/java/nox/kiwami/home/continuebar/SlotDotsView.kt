package nox.kiwami.home.continuebar

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.floor
import kotlin.math.sin

class SlotDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val dotRadius = DOT_RADIUS_DP * density
    private val pillRadius = PILL_RADIUS_DP * density
    private val step = STEP_DP * density
    private val padH = PAD_H_DP * density
    private val padV = step + PAD_EXTRA_DP * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var activeColor = Color.WHITE
    private var idleColor = Color.GRAY
    private var base = 0
    private var pos = 0f
    private var target = 0
    private var dragging = false
    private var animator: ValueAnimator? = null

    fun setColors(active: Int, idle: Int) {
        activeColor = active
        idleColor = idle
        invalidate()
    }

    fun drag(page: Int, dir: Float, progress: Float) {
        if (animator != null) {
            animator?.cancel()
            animator = null
            base = target
        }
        dragging = true
        align(page)
        pos = base - dir * progress
        invalidate()
    }

    fun release(page: Int, dir: Float, commit: Boolean) {
        if (animator != null) {
            animator?.cancel()
            animator = null
            base = target
        }
        if (!dragging) pos = base.toFloat()
        dragging = false
        align(page)
        val end = if (commit) base - dir.toInt() else base
        target = end
        val from = pos
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (commit) SETTLE_MS else RETURN_MS
            interpolator = if (commit) OvershootInterpolator(OVERSHOOT) else DecelerateInterpolator(1.6f)
            addUpdateListener {
                pos = from + (end - from) * (it.animatedValue as Float)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    animator = null
                    base = end
                    pos = end.toFloat()
                    invalidate()
                }
            })
            start()
        }
    }

    fun snapTo(page: Int) {
        if (dragging || animator != null) return
        align(page)
        pos = base.toFloat()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            (2 * padH + 2 * pillRadius).toInt(),
            (2 * padV + step + 2 * pillRadius).toInt()
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val first = padV + pillRadius
        paint.color = idleColor
        canvas.drawCircle(cx, first, dotRadius, paint)
        canvas.drawCircle(cx, first + step, dotRadius, paint)
        val m = ((pos % 2f) + 2f) % 2f
        val f = m - floor(m)
        val stretch = sin(Math.PI * f).toFloat() * step * STRETCH
        if (m <= 1f) {
            drawPill(canvas, cx, first + m * step, stretch, 1f)
        } else {
            val handoff = ease(m - 1f)
            drawPill(canvas, cx, first + m * step, stretch, 1f - handoff)
            drawPill(canvas, cx, first + (m - 2f) * step, stretch, handoff)
        }
    }

    private fun drawPill(canvas: Canvas, cx: Float, cy: Float, stretch: Float, alpha: Float) {
        if (alpha <= 0f) return
        paint.color = activeColor
        paint.alpha = (Color.alpha(activeColor) * alpha).toInt()
        val half = pillRadius + stretch / 2f
        rect.set(cx - pillRadius, cy - half, cx + pillRadius, cy + half)
        canvas.drawRoundRect(rect, pillRadius, pillRadius, paint)
    }

    private fun align(page: Int) {
        val current = ((base % 2) + 2) % 2
        base += page - current
    }

    private fun ease(t: Float): Float = t * t * (3f - 2f * t)

    private companion object {
        const val DOT_RADIUS_DP = 2.5f
        const val PILL_RADIUS_DP = 3f
        const val STEP_DP = 11f
        const val PAD_H_DP = 8f
        const val PAD_EXTRA_DP = 2f
        const val STRETCH = 0.7f
        const val OVERSHOOT = 2.2f
        const val SETTLE_MS = 420L
        const val RETURN_MS = 220L
    }
}
