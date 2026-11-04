package ani.dantotsu.home.streak

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * A procedurally animated fire, drawn with canvas paths (no assets).
 *
 *  - [Mode.ICON]: a little campfire (two logs, flames, glow, sparks). This is the streak icon.
 *  - [Mode.EDGE]: a row of tongues along the bottom edge that fades out upwards, laid over the
 *    Anime / Manga list cards.
 *  - [Mode.AURA]: flames licking up from the top edge of a card and out from its two sides, so
 *    the whole card looks like it caught fire. The view has the exact size of the card and draws
 *    OUTSIDE its own bounds (its parents must have clipChildren=false). Upwards it never goes
 *    further than [setHeadroom], so it can't touch anything above the card.
 *
 * What it looks like is set by [setStage] (0..5, see [StreakManager.stageOf]):
 *
 *   stage 0   no fire. Just the logs and a few cold, faintly pulsing coals
 *   stage 1   one small flame with two little ones next to it
 *   stage 2   five flames
 *   stage 3   bigger, five flames, more sparks
 *   stage 4   seven flames, taller, hotter, bigger glow
 *   stage 5   the same shape at full strength. Last stage
 *
 * The flames are teardrops whose tips lead and whose bodies lag behind, which is what makes them
 * lick and flicker rather than just wobble. Three layers (red, orange, white-hot yellow) sit on
 * top of each other, with a soft glow behind and sparks drifting up.
 *
 * [setDim] shows the "not done yet today" look. [flare] gives a short burst on streak growth.
 */
class FireView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { ICON, EDGE, AURA }

    private val d = resources.displayMetrics.density
    private var mode = Mode.ICON
    private var stage = 3
    private var intensity = ICON_INTENSITY[3]
    private var boost = 1f // fixed at 1 (100%)
    private var dim = 0f // 0 = fully lit, 1 = out (grey)
    private var flareUntil = 0L
    private var running = false
    private var animated = true
    private var sparksOn = true
    private var active = activeTongues(3) // indices into TONGUES, small flames first

    private val outer = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ember = Paint(Paint.ANTI_ALIAS_FLAG)
    private val logPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val coalPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val aura = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shaderM = Matrix()
    private var headroom = 36f * resources.displayMetrics.density
    private var follow: View? = null

    private class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, var size: Float)

    private val sparks = ArrayList<Spark>()
    private val rnd = Random(7)
    private var startNanos = System.nanoTime()
    private var lastNanos = 0L
    private var spawnDebt = 0f

    /** One flame of the icon: x offset (fraction of width), height, width, lean. */
    private class Tongue(val dx: Float, val h: Float, val w: Float, val lean: Float, val minStage: Int)

    // ---- configuration -----------------------------------------------------------------------

    fun setMode(m: Mode) {
        if (m == mode) return
        mode = m
        intensity = defaultIntensity()
        buildShaders()
        invalidate()
    }

    /**
     * @param value 0..5
     * @param intensityOverride 0..1, only when this view should burn differently from the default
     * for its [Mode] (the flames behind the number use this).
     */
    fun setStage(value: Int, intensityOverride: Float? = null) {
        stage = value.coerceIn(0, StreakManager.MAX_STAGE)
        active = activeTongues(stage)
        intensity = (intensityOverride ?: defaultIntensity()).coerceIn(0f, 1f)
        buildShaders()
        invalidate()
    }

    private fun defaultIntensity() =
        if (mode == Mode.ICON) ICON_INTENSITY[stage] else EDGE_INTENSITY[stage]

    /** @param value 0f = burning, 1f = grey and nearly out */
    fun setDim(value: Float) {
        dim = value.coerceIn(0f, 1f)
        val sat = 1f - dim * 0.9f
        val filter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(sat) })
        outer.colorFilter = filter; mid.colorFilter = filter; inner.colorFilter = filter
        glow.colorFilter = filter; ember.colorFilter = filter; aura.colorFilter = filter
        invalidate()
    }

    /** 1 = normal. (The Streak settings no longer expose this: it is always 1.) */
    fun setBoost(value: Float) {
        boost = value.coerceIn(0.5f, 1.5f)
        invalidate()
    }

    /** false = a still picture of the fire, no flicker, no sparks. */
    fun setAnimated(value: Boolean) {
        if (animated == value) return
        animated = value
        lastNanos = 0L
        updateRunning()
        invalidate()
    }

    fun setSparks(value: Boolean) {
        sparksOn = value
        if (!value) sparks.clear()
        invalidate()
    }

    /** [Mode.AURA]: how far above the view the flames may reach, in px. */
    fun setHeadroom(px: Float) {
        headroom = px.coerceIn(0f, 80f * d)
        invalidate()
    }

    /** [Mode.AURA]: only draw while [card] is visible (it starts invisible and fades in). */
    fun setFollow(card: View?) {
        follow = card
    }

    fun flare(durationMs: Long = 1400L) {
        flareUntil = System.currentTimeMillis() + durationMs
        invalidate()
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    private fun updateRunning() {
        val shouldRun = animated && isShown && isAttachedToWindow
        if (shouldRun && !running) {
            running = true
            lastNanos = 0L
            postInvalidateOnAnimation()
        } else if (!shouldRun) {
            running = false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateRunning()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateRunning()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildShaders()
    }

    // ---- geometry ----------------------------------------------------------------------------

    private val baseY get() = height * 0.86f

    /** Tallest a flame of the icon can get at intensity [i]. */
    private fun iconMaxH(i: Float) = height * (0.30f + 0.52f * i.coerceAtMost(1f))

    private fun buildShaders() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (h <= 0f || w <= 0f) return

        if (mode == Mode.ICON) {
            // Colours run base (hot) -> tip (cool, see-through). The gradient spans exactly the
            // tallest flame of this stage, so small fires fade at their own tips.
            val bottom = baseY + h * 0.01f
            val top = (baseY - iconMaxH(intensity)).coerceAtLeast(0f)
            val pos = floatArrayOf(0f, 0.45f, 1f)
            fun grad(c0: Int, c1: Int, c2: Int) =
                LinearGradient(0f, top, 0f, bottom, intArrayOf(c0, c1, c2), pos, Shader.TileMode.CLAMP)
            outer.shader = grad(0x8CB71C1C.toInt(), 0xF2FF3D00.toInt(), 0xFFFF8F00.toInt())
            mid.shader = grad(0xBFFF6D00.toInt(), 0xF2FF9100.toInt(), 0xFFFFC107.toInt())
            inner.shader = grad(0xBFFFEB3B.toInt(), 0xF2FFE082.toInt(), 0xFFFFFDE7.toInt())

            // soft halo behind the flames; kept inside the view so it never shows a hard edge
            val r = min(w * 0.47f, h * 0.43f)
            glow.shader = RadialGradient(
                w / 2f, baseY - h * 0.28f, r,
                intArrayOf(0xFFFF7A00.toInt(), 0x00FF5500), null, Shader.TileMode.CLAMP
            )
        } else if (mode == Mode.AURA) {
            // Flame-local gradient: y = 0 is the root, y = -100 the tip. Every flame scales it to
            // its own height (see [rooted]), so tips always fade out completely.
            val pos = floatArrayOf(0f, 0.55f, 1f)
            fun grad(c0: Int, c1: Int, c2: Int) =
                LinearGradient(0f, 0f, 0f, -100f, intArrayOf(c0, c1, c2), pos, Shader.TileMode.CLAMP)
            outer.shader = grad(0xFFFF8F00.toInt(), 0xE6FF3D00.toInt(), 0x00FF3D00)
            mid.shader = grad(0xFFFFC107.toInt(), 0xE6FF9100.toInt(), 0x00FF9100)
            inner.shader = grad(0xFFFFFDE7.toInt(), 0xE6FFE082.toInt(), 0x00FFE082)
        } else {
            // tips fade to transparent so the fire melts into the card instead of ending in a shape
            val pos = floatArrayOf(0f, 0.4f, 1f)
            fun grad(c0: Int, c1: Int, c2: Int) =
                LinearGradient(0f, 0f, 0f, h, intArrayOf(c0, c1, c2), pos, Shader.TileMode.CLAMP)
            outer.shader = grad(0x00FF3D00, 0xE6FF3D00.toInt(), 0xFFFF8F00.toInt())
            mid.shader = grad(0x00FF9100, 0xE6FF9100.toInt(), 0xFFFFC107.toInt())
            inner.shader = grad(0x00FFE082, 0xE6FFE082.toInt(), 0xFFFFFDE7.toInt())

            // warm light spilling up from the bottom edge
            glow.shader = LinearGradient(
                0f, 0f, 0f, h, 0x00FF5500, 0xFFFF6D00.toInt(), Shader.TileMode.CLAMP
            )
        }
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
        val dt = if (!animated || lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceAtMost(0.05f)
        lastNanos = now
        // a still picture uses a fixed moment that looks good
        val time = if (animated) (now - startNanos) / 1e9f else 2.6f

        val flaring = animated && System.currentTimeMillis() < flareUntil
        // the intensity that is actually drawn: user setting, a lit/dim flame, a short burst
        val i = (intensity * boost * (1f - 0.45f * dim) * (if (flaring) 1.18f else 1f)).coerceIn(0f, 1.2f)
        val t = time * (1f - 0.4f * dim)

        // the AURA of a card that is not showing (yet) draws nothing but keeps ticking
        if (mode == Mode.AURA && follow != null && follow?.visibility != VISIBLE) {
            if (running) postInvalidateOnAnimation()
            return
        }

        if (mode == Mode.AURA) {
            canvas.save()
            // out to the sides and up to the headroom, never below the card
            canvas.clipRect(-SIDE_OUT * d, -headroom, w + SIDE_OUT * d, h)
        }
        when (mode) {
            Mode.ICON -> drawIcon(canvas, w, h, t, i)
            Mode.EDGE -> drawEdge(canvas, w, h, t, i)
            Mode.AURA -> drawAura(canvas, w, h, t, i)
        }
        if (animated && sparksOn) updateSparks(canvas, w, h, dt, i)
        if (mode == Mode.AURA) canvas.restore()

        if (running) postInvalidateOnAnimation()
    }

    /** A teardrop whose tip leads and whose body lags, so it licks instead of just swaying. */
    private fun flame(canvas: Canvas, cx: Float, base: Float, w: Float, h: Float, t: Float, ph: Float, lean: Float, paint: Paint) {
        val s1 = noise(t * 1.5f, ph) * w * 0.14f
        val s2 = noise(t * 1.5f - 0.45f, ph + 1.3f) * w * 0.55f
        val tx = cx + lean * h * 0.16f + s2
        val ty = base - h * (0.88f + 0.12f * noise(t * 2.1f, ph + 2f))
        path.reset()
        path.moveTo(cx - w / 2f, base)
        path.cubicTo(cx - w * 0.62f + s1, base - h * 0.30f, tx - w * 0.30f + s2 * 0.15f, base - h * 0.62f, tx, ty)
        path.cubicTo(tx + w * 0.30f + s2 * 0.15f, base - h * 0.62f, cx + w * 0.62f + s1, base - h * 0.30f, cx + w / 2f, base)
        path.quadTo(cx, base + w * 0.3f, cx - w / 2f, base)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawLogs(canvas: Canvas, w: Float, h: Float, i: Float) {
        val lw = w * 0.74f
        val lh = h * 0.12f
        val glowAlpha = (0.25f + 0.5f * i.coerceAtMost(1f))
        for (angle in floatArrayOf(-14f, 14f)) {
            canvas.save()
            canvas.translate(w / 2f, baseY)
            canvas.rotate(angle)
            logPaint.color = 0xFF4E342E.toInt()
            rect.set(-lw / 2f, -lh / 2f, lw / 2f, lh / 2f)
            canvas.drawRoundRect(rect, lh / 2f, lh / 2f, logPaint)
            logPaint.color = 0xFF6D4C41.toInt()
            rect.set(-lw / 2f, -lh / 2f, lw / 2f, -lh / 2f + lh * 0.45f)
            canvas.drawRoundRect(rect, lh / 2f, lh / 2f, logPaint)
            // the cut ends glow when there is a fire
            logPaint.color = ((glowAlpha * 255f).toInt().coerceIn(0, 255) shl 24) or 0xFF6D00
            canvas.drawCircle(-lw / 2f + lh * 0.5f, 0f, lh * 0.32f, logPaint)
            canvas.drawCircle(lw / 2f - lh * 0.5f, 0f, lh * 0.32f, logPaint)
            canvas.restore()
        }
    }

    private fun drawIcon(canvas: Canvas, w: Float, h: Float, t: Float, i: Float) {
        val base = baseY

        if (stage > 0) {
            val pulse = 0.85f + 0.15f * sin(t * 5f)
            glow.alpha = (((0.18f + 0.35f * i.coerceAtMost(1f)) * pulse).coerceIn(0f, 1f) * 255f).toInt()
            canvas.drawRect(0f, 0f, w, h, glow)
        }

        drawLogs(canvas, w, h, if (stage > 0) i else 0f)

        if (stage == 0) {
            // cold coals: a few dull red dots that breathe very slowly
            for (k in 0 until 5) {
                val x = w / 2f + (k - 2) * w * 0.11f
                val y = base - h * 0.02f - (k % 2) * h * 0.03f
                val a = (0.35f + 0.3f * sin(t * 1.6f + k * 1.9f)).coerceIn(0f, 1f)
                coalPaint.color = ((a * 255f).toInt() shl 24) or 0xFF5722
                canvas.drawCircle(x, y, h * 0.028f, coalPaint)
            }
            return
        }

        val maxH = iconMaxH(i)
        for (layer in 0 until 3) {
            val paint = if (layer == 0) outer else if (layer == 1) mid else inner
            val sc = if (layer == 0) 1f else if (layer == 1) 0.74f else 0.46f
            for (k in active) {
                val tg = TONGUES[k]
                val fh = maxH * tg.h * (0.82f + 0.18f * (0.5f + 0.5f * noise(t * 1.4f, k * 2.1f)))
                val fw = w * tg.w * sc * (0.9f + 0.1f * i.coerceAtMost(1f))
                val cx = w / 2f + tg.dx * w * (0.9f + 0.3f * i.coerceAtMost(1f))
                val layerH = if (sc == 1f) fh else fh * (sc * 1.02f + 0.02f)
                flame(canvas, cx, base + h * 0.01f, fw, layerH, t, k * 1.7f + sc, tg.lean, paint)
            }
        }
    }

    private fun drawEdge(canvas: Canvas, w: Float, h: Float, t: Float, i: Float) {
        if (i <= 0f) return
        glow.alpha = (((0.28f + 0.4f * i.coerceAtMost(1f))).coerceIn(0f, 1f) * 255f).toInt()
        canvas.drawRect(0f, 0f, w, h, glow)

        val count = max(4, (w / (13f * d)).toInt())
        val maxH = h * (0.35f + 0.6f * min(i, 1f))
        for (layer in 0 until 3) {
            val paint = if (layer == 0) outer else if (layer == 1) mid else inner
            val sc = if (layer == 0) 1f else if (layer == 1) 0.66f else 0.36f
            for (k in 0 until count) {
                val cx = (k + 0.5f) * (w / count)
                val n01 = 0.5f + 0.5f * noise(t * 1.3f, k * 2.37f)
                val fh = maxH * (0.35f + 0.65f * n01) * (if (sc == 1f) 1f else sc * 1.05f)
                val fw = w / count * 1.9f * sc
                flame(canvas, cx, h * 1.03f, fw, fh, t * 1.1f, k * 2.37f + sc * 3f, noise(t * 0.5f, k.toFloat()) * 0.6f, paint)
            }
        }
    }

    /** One flame rooted at ([x], [y]) pointing [angle] degrees away from straight up. */
    private fun rooted(
        canvas: Canvas, x: Float, y: Float, angle: Float, w: Float, h: Float,
        t: Float, ph: Float, lean: Float, paint: Paint
    ) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(angle)
        shaderM.setScale(1f, h / 100f)
        paint.shader?.setLocalMatrix(shaderM)
        flame(canvas, 0f, 0f, w, h, t, ph, lean, paint)
        canvas.restore()
    }

    private fun drawAura(canvas: Canvas, w: Float, h: Float, t: Float, i: Float) {
        if (i <= 0f) return
        val ii = min(i, 1f)
        val inset = 5f * d // flames start a little inside the card, so they grow out of its edge
        val strength = 0.55f + 0.45f * ii

        // soft heat around the card edge
        val pulse = 0.85f + 0.15f * sin(t * 4.2f)
        rect.set(0f, 0f, w, h)
        for ((sw, a) in listOf(16f to 0.07f, 9f to 0.12f, 3.5f to 0.22f)) {
            aura.strokeWidth = sw * d
            aura.color = (((a * strength * pulse).coerceIn(0f, 1f) * 255f).toInt() shl 24) or 0xFF6D00
            canvas.drawRoundRect(rect, 16f * d, 16f * d, aura)
        }

        val topMax = (headroom + inset) * strength
        val sideMax = ((SIDE_OUT * d + inset) / 0.88f) * strength
        val topCount = max(5, (w / (12f * d)).toInt())
        val sideCount = max(3, (h / (15f * d)).toInt())
        for (layer in 0 until 3) {
            val paint = if (layer == 0) outer else if (layer == 1) mid else inner
            val sc = if (layer == 0) 1f else if (layer == 1) 0.66f else 0.36f
            val grow = if (sc == 1f) 1f else sc * 1.05f
            // top edge: straight up
            for (k in 0 until topCount) {
                val cx = (k + 0.5f) * (w / topCount)
                val n01 = 0.5f + 0.5f * noise(t * 1.3f, k * 2.37f)
                val fh = topMax * (0.4f + 0.6f * n01) * grow
                rooted(canvas, cx, inset, 0f, w / topCount * 2f * sc, fh, t * 1.1f, k * 2.37f + sc * 3f, 0f, paint)
            }
            // sides: out and up
            for (k in 0 until sideCount) {
                val cy = (k + 0.5f) * (h / sideCount)
                val n01 = 0.5f + 0.5f * noise(t * 1.2f, 40f + k * 1.91f)
                val fh = sideMax * (0.4f + 0.6f * n01) * grow
                val fw = h / sideCount * 1.8f * sc
                rooted(canvas, inset, cy, -62f, fw, fh, t * 1.05f, 40f + k * 1.91f + sc * 3f, 0f, paint)
                val n02 = 0.5f + 0.5f * noise(t * 1.2f, 90f + k * 2.13f)
                val fh2 = sideMax * (0.4f + 0.6f * n02) * grow
                rooted(canvas, w - inset, cy, 62f, fw, fh2, t * 1.05f, 90f + k * 2.13f + sc * 3f, 0f, paint)
            }
        }
    }

    // ---- sparks ------------------------------------------------------------------------------

    private fun updateSparks(canvas: Canvas, w: Float, h: Float, dt: Float, i: Float) {
        val rate = when {
            dim > 0.85f -> 0f
            mode == Mode.ICON -> if (stage == 0) 0f else 1.5f + 14f * min(i, 1f)
            else -> 2f + 12f * min(i, 1f)
        }
        spawnDebt += rate * dt
        while (spawnDebt >= 1f && sparks.size < 22) {
            spawnDebt -= 1f
            val x = if (mode == Mode.ICON) w * (0.32f + 0.36f * rnd.nextFloat()) else w * rnd.nextFloat()
            val y = when (mode) {
                Mode.ICON -> baseY - h * (0.1f + 0.3f * min(i, 1f))
                Mode.AURA -> 2f * d
                else -> h * 0.85f
            }
            sparks += Spark(
                x, y,
                (rnd.nextFloat() - 0.5f) * 14f * d,
                -(22f + 30f * rnd.nextFloat()) * d,
                1f,
                (0.7f + 1.3f * rnd.nextFloat()) * d
            )
        }
        val it = sparks.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.life -= dt * 1.0f
            if (s.life <= 0f) { it.remove(); continue }
            s.x += s.vx * dt + sin((s.life * 9f) + s.size) * 0.3f * d
            s.y += s.vy * dt * 0.8f
            val a = (s.life * 255f).toInt().coerceIn(0, 255)
            ember.color = (a shl 24) or (if (s.life > 0.55f) 0xFFC107 else 0xFF6D00)
            canvas.drawCircle(s.x, s.y, s.size * (0.5f + 0.5f * s.life), ember)
        }
    }

    private companion object {
        fun activeTongues(stage: Int): IntArray =
            TONGUES.indices.filter { stage >= TONGUES[it].minStage }.sortedBy { TONGUES[it].h }.toIntArray()

        /** How fierce the icon fire is per stage (0..1). Stage 5 is the maximum. */
        val ICON_INTENSITY = floatArrayOf(0f, 0.2f, 0.42f, 0.6f, 0.8f, 1f)

        /** The same for the list buttons: they only burn from stage 3 on. */
        val EDGE_INTENSITY = floatArrayOf(0f, 0f, 0f, 0.45f, 0.72f, 1f)

        /** How far (dp) the flames of a card's AURA reach out to its sides. */
        const val SIDE_OUT = 22f

        /** Flames of the icon, tallest first. Side flames lean outwards. */
        val TONGUES = listOf(
            Tongue(0f, 1f, 0.46f, 0f, 1),
            Tongue(-0.17f, 0.55f, 0.26f, -0.5f, 1),
            Tongue(0.17f, 0.50f, 0.25f, 0.5f, 1),
            Tongue(-0.30f, 0.36f, 0.20f, -0.9f, 2),
            Tongue(0.30f, 0.34f, 0.20f, 0.9f, 2),
            Tongue(-0.09f, 0.82f, 0.30f, -0.25f, 4),
            Tongue(0.10f, 0.78f, 0.30f, 0.3f, 4)
        )
    }
}
