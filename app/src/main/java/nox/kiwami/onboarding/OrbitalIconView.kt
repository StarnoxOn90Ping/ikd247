package nox.kiwami.onboarding

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The animated halo behind every onboarding icon: expanding ripples, a rotating dotted ring,
 * counter-rotating arcs, orbiting glow dots and a glassy core. Everything is drawn inside a
 * circle that fits the view, so nothing is ever clipped into a square.
 */
class OrbitalIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.6f); color = 0xFFFFFFFF.toInt()
    }
    private val dottedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(3f); strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt(); alpha = 120
        pathEffect = DashPathEffect(floatArrayOf(0.1f, dp(13f)), 0f)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(3.5f); strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val coreStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.5f); color = 0xFFFFFFFF.toInt(); alpha = 130
    }
    private val arcRect = RectF()

    /** 0..1, one full loop = 9 seconds */
    private var t = 0f
    private var animator: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f
        glowPaint.shader = RadialGradient(
            cx, cy, r * 0.85f,
            intArrayOf(0x55FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP
        )
        corePaint.shader = RadialGradient(
            cx - r * 0.1f, cy - r * 0.16f, r * 0.55f,
            intArrayOf(0x73FFFFFF, 0x14FFFFFF), null, Shader.TileMode.CLAMP
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 9000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - dp(2f)

        canvas.drawCircle(cx, cy, r, glowPaint)

        // three expanding ripples, 3s each, evenly phased
        for (k in 0 until 3) {
            val phase = (t * 3f + k / 3f) % 1f
            ripplePaint.alpha = ((1f - phase) * (1f - phase) * 150).toInt()
            canvas.drawCircle(cx, cy, r * (0.5f + 0.5f * phase), ripplePaint)
        }

        // dotted ring, slow clockwise
        canvas.save()
        canvas.rotate(t * 360f, cx, cy)
        canvas.drawCircle(cx, cy, r * 0.74f, dottedPaint)
        canvas.restore()

        // two arcs spinning the other way
        val arcR = r * 0.62f
        arcRect.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
        canvas.save()
        canvas.rotate(-t * 720f, cx, cy)
        arcPaint.alpha = 230
        canvas.drawArc(arcRect, 0f, 62f, false, arcPaint)
        arcPaint.alpha = 120
        canvas.drawArc(arcRect, 180f, 34f, false, arcPaint)
        canvas.restore()

        // three glowing dots orbiting on the outer lane
        val orbit = r * 0.88f
        for (k in 0 until 3) {
            val angle = Math.toRadians((t * 720f + k * 120f).toDouble())
            val x = cx + orbit * cos(angle).toFloat()
            val y = cy + orbit * sin(angle).toFloat()
            val size = if (k == 0) dp(4.5f) else dp(3.2f)
            dotPaint.alpha = 50
            canvas.drawCircle(x, y, size * 2.6f, dotPaint)
            dotPaint.alpha = 255
            canvas.drawCircle(x, y, size, dotPaint)
        }

        // glass core
        val coreR = r * 0.46f
        canvas.drawCircle(cx, cy, coreR, corePaint)
        canvas.drawCircle(cx, cy, coreR, coreStroke)
    }
}
