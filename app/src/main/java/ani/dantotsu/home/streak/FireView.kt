package ani.dantotsu.home.streak

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * A procedurally animated flame, drawn with canvas paths (no assets).
 *
 *  - [Mode.ICON]: one flame cluster in the middle, used as the streak icon.
 *  - [Mode.EDGE]: a row of tongues along the bottom edge that fades out upwards, laid over the
 *    Anime / Manga list cards once the streak is high enough.
 *
 * [setLevel] controls how fierce it is (0..1). [setDim] shows the "not done yet today" look.
 * [flare] gives a short burst, used when the streak goes up.
 */
class FireView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { ICON, EDGE }

    private val d = resources.displayMetrics.density
    private var mode = Mode.ICON
    private var level = 0.5f
    private var dim = 0f // 0 = fully lit, 1 = out (grey embers)
    private var flareUntil = 0L
    private var running = false

    private val outer = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ember = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, var size: Float)

    private val sparks = ArrayList<Spark>()
    private val rnd = Random(7)
    private var lastNanos = 0L
    private var spawnDebt = 0f

    fun setMode(m: Mode) {
        if (m == mode) return
        mode = m
        buildShaders()
        invalidate()
    }

    fun setLevel(value: Float) {
        level = value.coerceIn(0f, 1f)
        invalidate()
    }

    /** @param value 0f = burning, 1f = grey and nearly out */
    fun setDim(value: Float) {
        dim = value.coerceIn(0f, 1f)
        val sat = 1f - dim * 0.9f
        val cm = ColorMatrix().apply { setSaturation(sat) }
        val filter = ColorMatrixColorFilter(cm)
        outer.colorFilter = filter; mid.colorFilter = filter; inner.colorFilter = filter
        ember.colorFilter = filter
        invalidate()
    }

    fun flare(durationMs: Long = 1400L) {
        flareUntil = System.currentTimeMillis() + durationMs
        invalidate()
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        lastNanos = 0L
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        running = isVisible && isAttachedToWindow
        if (running) {
            lastNanos = 0L
            postInvalidateOnAnimation()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildShaders()
    }

    private fun buildShaders() {
        val h = height.toFloat()
        if (h <= 0f) return
        // Gradients run bottom (hot) -> top (cool). In EDGE mode the tips fade to transparent so
        // the fire melts into the card instead of ending in a hard shape.
        val tipAlpha = if (mode == Mode.EDGE) 0 else 235
        fun grad(top: Int, bottom: Int) =
            LinearGradient(0f, 0f, 0f, h, top, bottom, Shader.TileMode.CLAMP)
        outer.shader = grad((tipAlpha shl 24) or 0xFF3D00, 0xFFFF8F00.toInt())
        mid.shader = grad(((tipAlpha + 20).coerceAtMost(255) shl 24) or 0xFF9100, 0xFFFFC107.toInt())
        inner.shader = grad(((tipAlpha + 20).coerceAtMost(255) shl 24) or 0xFFD54F, 0xFFFFF59D.toInt())
    }

    // ---- drawing -----------------------------------------------------------------------------

    /** Smooth pseudo-noise in -1..1. */
    private fun noise(t: Float, phase: Float): Float =
        (sin(t * 2.3f + phase) + 0.6f * sin(t * 3.9f + phase * 1.7f) + 0.35f * sin(t * 6.1f + phase * 0.6f)) / 1.95f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceAtMost(0.05f)
        lastNanos = now
        val t = now / 1e9f

        val flaring = System.currentTimeMillis() < flareUntil
        val boost = if (flaring) 1.35f else 1f
        // a dim/out flame is smaller and flickers lazily
        val vigor = (0.25f + 0.75f * level) * (1f - 0.55f * dim) * boost
        val speed = 1f - 0.5f * dim

        if (mode == Mode.ICON) drawIcon(canvas, w, h, t * speed, vigor) else drawEdge(canvas, w, h, t * speed, vigor)
        updateSparks(canvas, w, h, dt, vigor)

        if (running) postInvalidateOnAnimation()
    }

    private fun tongue(canvas: Canvas, cx: Float, base: Float, bw: Float, th: Float, sway: Float, paint: Paint) {
        path.reset()
        path.moveTo(cx - bw / 2f, base)
        path.cubicTo(cx - bw / 2f, base - th * 0.45f, cx - bw * 0.15f + sway * 0.5f, base - th * 0.72f, cx + sway, base - th)
        path.cubicTo(cx + bw * 0.15f + sway * 0.5f, base - th * 0.72f, cx + bw / 2f, base - th * 0.45f, cx + bw / 2f, base)
        path.quadTo(cx, base + bw * 0.28f, cx - bw / 2f, base)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawIcon(canvas: Canvas, w: Float, h: Float, t: Float, vigor: Float) {
        val base = h * 0.9f
        val count = (3 + level * 3f).toInt().coerceIn(3, 6)
        val maxH = h * (0.55f + 0.4f * vigor.coerceAtMost(1.2f))
        val spread = w * (0.12f + 0.16f * level)
        // side tongues first so the centre one sits on top
        for (i in count - 1 downTo 0) {
            val side = if (i == 0) 0f else ((i + 1) / 2) * (if (i % 2 == 1) -1f else 1f)
            val cx = w / 2f + side * spread * 0.55f
            val falloff = 1f - 0.22f * kotlin.math.abs(side)
            val n = noise(t, i * 1.9f)
            val th = maxH * falloff * (0.78f + 0.22f * n) * if (i == 0) 1f else 0.78f
            val bw = w * (0.46f - 0.05f * kotlin.math.abs(side)).coerceAtLeast(0.2f)
            val sway = n * w * 0.07f + side * w * 0.015f
            tongue(canvas, cx, base, bw, th, sway, outer)
            tongue(canvas, cx, base, bw * 0.68f, th * 0.74f, sway * 0.8f, mid)
            tongue(canvas, cx, base, bw * 0.4f, th * 0.46f, sway * 0.6f, inner)
        }
    }

    private fun drawEdge(canvas: Canvas, w: Float, h: Float, t: Float, vigor: Float) {
        val base = h * 1.02f
        val step = 15f * d
        val count = max(3, (w / step).toInt())
        val bw = w / count * 1.9f
        val maxH = h * (0.5f + 0.5f * min(vigor, 1f))
        for (i in 0 until count) {
            val cx = (i + 0.5f) * (w / count)
            val n = noise(t * 1.2f, i * 2.37f)
            val n01 = 0.5f + 0.5f * n
            val th = maxH * (0.4f + 0.6f * n01)
            val sway = noise(t * 0.9f, i * 1.3f + 4f) * bw * 0.35f
            tongue(canvas, cx, base, bw, th, sway, outer)
            tongue(canvas, cx, base, bw * 0.6f, th * 0.7f, sway * 0.8f, mid)
            tongue(canvas, cx, base, bw * 0.3f, th * 0.42f, sway * 0.6f, inner)
        }
    }

    // ---- embers ------------------------------------------------------------------------------

    private fun updateSparks(canvas: Canvas, w: Float, h: Float, dt: Float, vigor: Float) {
        val rate = if (dim > 0.85f) 0f else (2f + 16f * level) * vigor // sparks per second
        spawnDebt += rate * dt
        while (spawnDebt >= 1f && sparks.size < 18) {
            spawnDebt -= 1f
            val x = if (mode == Mode.ICON) w * (0.3f + 0.4f * rnd.nextFloat()) else w * rnd.nextFloat()
            val y = if (mode == Mode.ICON) h * 0.55f else h * 0.85f
            sparks += Spark(
                x, y,
                (rnd.nextFloat() - 0.5f) * 14f * d,
                -(22f + 30f * rnd.nextFloat()) * d,
                1f,
                (1f + 1.4f * rnd.nextFloat()) * d
            )
        }
        val it = sparks.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.life -= dt * 1.1f
            if (s.life <= 0f) { it.remove(); continue }
            s.x += s.vx * dt + sin((s.life * 9f) + s.size) * 0.25f * d
            s.y += s.vy * dt
            val a = (s.life * 255f).toInt().coerceIn(0, 255)
            ember.color = (a shl 24) or (if (s.life > 0.55f) 0xFFC107 else 0xFF6D00)
            canvas.drawCircle(s.x, s.y, s.size * (0.5f + 0.5f * s.life), ember)
        }
    }
}
