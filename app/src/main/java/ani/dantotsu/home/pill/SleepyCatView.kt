package ani.dantotsu.home.pill

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * A tiny Nyan cat that sleeps on the top edge of the info pill (see [InfoPillController]).
 *
 *  - asleep: slow breathing, the rainbow lies almost still, a little "z" drifts up now and then
 *  - [nudge] (the pill was tapped / swiped): an ear flick, the eyes open, the head lifts
 *  - nudged 4 times within about 1.5 s: it is startled ("!", fur puffs up, a small hop, the
 *    rainbow flashes), then glares for a moment, yawns and goes back to sleep
 *  - a few seconds without a nudge: yawn, back to sleep
 *
 * Everything is drawn with the canvas (no assets). The view is 56 x 32dp; the cat stands on the
 * bottom of it, the rest is headroom for the hop, the "!" and the "z"s. It never takes touches.
 * The clock only runs while the view is on screen and animations are enabled; otherwise the cat
 * just sleeps, still.
 */
class SleepyCatView(context: Context) : View(context) {

    private enum class Mood { SLEEP, AWAKE, STARTLED, GRUMPY, SETTLE }

    private val d = resources.displayMetrics.density

    // classic Nyan colours
    private val cOutline = 0xFF2B2B2B.toInt()
    private val cFur = 0xFF9E9E9E.toInt()
    private val cTartEdge = 0xFFFFCC99.toInt()
    private val cTart = 0xFFFF99FF.toInt()
    private val cSprinkle = 0xFFFF3399.toInt()
    private val cCheek = 0xFFFF9999.toInt()
    private val cBang = 0xFFFF3B30.toInt()
    private val rainbow = intArrayOf(
        0xFFFF0000.toInt(), 0xFFFF9900.toInt(), 0xFFFFFF00.toInt(),
        0xFF33FF00.toInt(), 0xFF0099FF.toInt(), 0xFF6633FF.toInt()
    )

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = cOutline
        strokeWidth = 0.9f * d
    }
    private val zPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
        color = 0xFFFFFFFF.toInt()
        setShadowLayer(1.2f * d, 0f, 0.5f * d, 0x66000000)
    }
    private val rect = RectF()
    private val path = Path()
    private val overshoot = OvershootInterpolator(3f)

    // ---- state --------------------------------------------------------------------------------

    private var mood = Mood.SLEEP
    private var moodSince = 0L
    private var lastNudge = 0L
    private var startledAt = 0L
    private val nudges = ArrayDeque<Long>()

    // smoothed look values
    private var lid = 0f      // 0 closed ... 1 open ... >1 wide
    private var lift = 0f     // head stretched up
    private var brow = 0f     // grumpy eyebrows
    private var mouth = 0f    // yawn / gasp
    private var amp = 0.25f * d
    private var phase = 0f    // rainbow wave
    private var breath = 0f
    private var earFlick = 0f

    private val zs = ArrayList<Long>()
    private var zLast = 0L

    private var clock: ValueAnimator? = null
    private var lastFrame = 0L

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // drawn above the pill, whose elevation would otherwise put its glow over the cat
        elevation = 6f * d
    }

    // ---- api ----------------------------------------------------------------------------------

    /** The pill was cycled (or poked). Wakes the cat; too many in a row startle it. */
    fun nudge() {
        if (clock == null) return // off screen, or animations are off: stay asleep
        val now = SystemClock.uptimeMillis()
        lastNudge = now
        earFlick = 1f
        while (nudges.isNotEmpty() && now - nudges.first() > STARTLE_WINDOW_MS) nudges.removeFirst()
        nudges.addLast(now)
        when (mood) {
            Mood.STARTLED -> Unit
            Mood.GRUMPY -> {
                if (nudges.size >= 3 && now - startledAt > 1200L) startle(now) else moodSince = now
            }
            else -> {
                if (nudges.size >= STARTLE_COUNT) startle(now)
                else if (mood != Mood.AWAKE) go(Mood.AWAKE, now)
            }
        }
    }

    private fun startle(now: Long) {
        nudges.clear()
        startledAt = now
        go(Mood.STARTLED, now)
    }

    private fun go(m: Mood, now: Long) {
        mood = m
        moodSince = now
    }

    // ---- clock --------------------------------------------------------------------------------

    private fun startClock() {
        if (clock != null) return
        if (!ValueAnimator.areAnimatorsEnabled()) return
        if (!isAttachedToWindow || windowVisibility != VISIBLE || !isShown) return
        lastFrame = SystemClock.uptimeMillis()
        clock = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { frame() }
            start()
        }
    }

    private fun stopClock() {
        clock?.cancel()
        clock = null
        // back to a still, sleeping pose
        mood = Mood.SLEEP
        lid = 0f; lift = 0f; brow = 0f; mouth = 0f; earFlick = 0f
        amp = 0.25f * d
        zs.clear()
        nudges.clear()
        invalidate()
    }

    private fun frame() {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastFrame).coerceIn(0L, 64L) / 1000f
        lastFrame = now

        when (mood) {
            Mood.AWAKE -> if (now - lastNudge > AWAKE_MS) go(Mood.SETTLE, now)
            Mood.STARTLED -> if (now - moodSince > 750L) go(Mood.GRUMPY, now)
            Mood.GRUMPY -> if (now - moodSince > 2600L) go(Mood.SETTLE, now)
            Mood.SETTLE -> if (now - moodSince > 1100L) go(Mood.SLEEP, now)
            Mood.SLEEP -> Unit
        }

        var tLid = 0f
        var tLift = 0f
        var tBrow = 0f
        var tMouth = 0f
        var tAmp = 0.25f * d
        var speed = 1.2f
        when (mood) {
            Mood.SLEEP -> Unit
            Mood.AWAKE -> { tLid = 1f; tLift = 1f; tAmp = 1.0f * d; speed = 7f }
            Mood.STARTLED -> { tLid = 1.5f; tLift = 1.4f; tMouth = 0.55f; tAmp = 2.0f * d; speed = 16f }
            Mood.GRUMPY -> { tLid = 0.45f; tLift = 0.7f; tBrow = 1f; tAmp = 0.8f * d; speed = 3f }
            Mood.SETTLE -> {
                // a yawn: the mouth opens and closes once while the eyes squeeze shut
                val t = ((now - moodSince) / 650f).coerceIn(0f, 1f)
                tMouth = sin(PI.toFloat() * t)
                tLid = 0.25f * (1f - t)
                tLift = 0.35f
                tAmp = 0.5f * d
                speed = 2f
            }
        }

        val k = 1f - exp(-dt * 14f)
        lid += (tLid - lid) * k
        lift += (tLift - lift) * k
        brow += (tBrow - brow) * k
        mouth += (tMouth - mouth) * (1f - exp(-dt * 22f))
        amp += (tAmp - amp) * (1f - exp(-dt * 6f))
        phase += dt * speed
        breath += dt * 2.1f // about a 3 s breath
        earFlick = (earFlick - dt * 4f).coerceAtLeast(0f)

        // a "z" every few seconds while asleep
        zs.removeAll { now - it > Z_LIFE_MS }
        if (mood == Mood.SLEEP && now - zLast > 2800L) {
            zs.add(now)
            zLast = now
        }
        invalidate()
    }

    // ---- drawing ------------------------------------------------------------------------------
    // Facing left. x grows to the right, g is the ground (the top edge of the pill).

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val g = height - 1f * d
        val breathe = sin(breath) * (1f - 0.6f * lift.coerceIn(0f, 1f))

        val ts = (now - startledAt) / 1000f // huge when never startled, so everything below is off
        val hop = if (ts < 0.42f) sin(PI.toFloat() * ts / 0.42f) * 5f * d else 0f
        val poof = exp(-ts * 3.2f)
        val s = 1f + 0.10f * poof

        canvas.save()
        canvas.translate(0f, -hop)
        canvas.scale(s, s, 22f * d, g)
        drawTrail(canvas, g)
        drawFeet(canvas, g)
        drawBody(canvas, g, breathe)
        drawHead(canvas, g, breathe, now)
        canvas.restore()

        drawZs(canvas, g, now)
        drawBang(canvas, g, ts)
    }

    private fun drawTrail(c: Canvas, g: Float) {
        val n = 12
        val sw = 2f * d
        val sh = 1.6f * d
        val top = g - 9.8f * d
        for (i in 0 until n) {
            val off = amp * sin(phase - i * 0.7f)
            val a = (255 * (1f - 0.55f * i / (n - 1))).toInt()
            val x = 29f * d + i * sw
            for (k in rainbow.indices) {
                fill.color = rainbow[k]
                fill.alpha = a
                rect.set(x, top + k * sh + off, x + sw + 0.4f * d, top + (k + 1) * sh + off)
                c.drawRect(rect, fill)
            }
        }
        fill.alpha = 255
    }

    private fun drawFeet(c: Canvas, g: Float) {
        fill.color = cFur
        for (fx in floatArrayOf(12.5f, 16.5f, 25f, 29f)) {
            rect.set(fx * d, g - 2.6f * d, (fx + 3f) * d, g)
            c.drawRoundRect(rect, 1f * d, 1f * d, fill)
            c.drawRoundRect(rect, 1f * d, 1f * d, line)
        }
    }

    private fun drawBody(c: Canvas, g: Float, breathe: Float) {
        val l = 11f * d
        val r = 31f * d
        val b = g - 1.2f * d
        val t = b - (10f + breathe * 0.45f) * d
        fill.color = cTartEdge
        rect.set(l, t, r, b)
        c.drawRoundRect(rect, 3f * d, 3f * d, fill)

        val inset = 1.5f * d
        fill.color = cTart
        rect.set(l + inset, t + inset, r - inset, b - inset)
        c.drawRoundRect(rect, 2f * d, 2f * d, fill)

        fill.color = cSprinkle
        val w = r - l
        val h = b - t
        for (p in SPRINKLES) {
            c.drawCircle(l + w * p[0], t + h * p[1], 0.55f * d, fill)
        }

        rect.set(l, t, r, b)
        c.drawRoundRect(rect, 3f * d, 3f * d, line)
    }

    private fun drawHead(c: Canvas, g: Float, breathe: Float, now: Long) {
        val hl = 1.5f * d
        val hr = 13.5f * d
        val hb = g - 0.4f * d
        val ht = hb - (8.6f + 1.8f * lift + breathe * 0.2f) * d
        val hw = hr - hl
        val hh = hb - ht

        // ears (the right one flicks when the cat is nudged)
        val tipUp = (3.2f + 0.8f * lift) * d
        val flick = earFlick * 1.6f * d * abs(sin(now / 45f))
        fill.color = cFur
        path.reset()
        path.moveTo(hl + 1.2f * d, ht + 0.8f * d)
        path.lineTo(hl + 2.3f * d, ht - tipUp)
        path.lineTo(hl + 5.2f * d, ht + 0.8f * d)
        path.close()
        c.drawPath(path, fill)
        c.drawPath(path, line)
        path.reset()
        path.moveTo(hr - 5.2f * d, ht + 0.8f * d)
        path.lineTo(hr - 2.3f * d + flick * 0.5f, ht - tipUp + flick)
        path.lineTo(hr - 1.2f * d, ht + 0.8f * d)
        path.close()
        c.drawPath(path, fill)
        c.drawPath(path, line)

        // head
        rect.set(hl, ht, hr, hb)
        c.drawRoundRect(rect, 3.2f * d, 3.2f * d, fill)
        c.drawRoundRect(rect, 3.2f * d, 3.2f * d, line)

        // cheeks
        val ey = ht + hh * 0.50f
        fill.color = cCheek
        fill.alpha = 200
        c.drawCircle(hl + 1.9f * d, ey + 1.9f * d, 1.0f * d, fill)
        c.drawCircle(hr - 1.9f * d, ey + 1.9f * d, 1.0f * d, fill)
        fill.alpha = 255

        // eyes
        val ex1 = hl + hw * 0.30f
        val ex2 = hl + hw * 0.72f
        drawEye(c, ex1, ey)
        drawEye(c, ex2, ey)

        // grumpy eyebrows, slanting down towards the middle
        if (brow > 0.05f) {
            line.alpha = (255 * brow).toInt()
            c.drawLine(ex1 - 1.5f * d, ey - 2.1f * d, ex1 + 1.2f * d, ey - 1.3f * d, line)
            c.drawLine(ex2 + 1.5f * d, ey - 2.1f * d, ex2 - 1.2f * d, ey - 1.3f * d, line)
            line.alpha = 255
        }

        // mouth (yawn / gasp)
        if (mouth > 0.05f) {
            fill.color = cOutline
            val mx = hl + hw * 0.51f
            val my = ht + hh * 0.80f
            val mr = (0.4f + 1.3f * mouth) * d
            rect.set(mx - mr * 0.8f, my - mr, mx + mr * 0.8f, my + mr)
            c.drawOval(rect, fill)
        }
    }

    private fun drawEye(c: Canvas, x: Float, y: Float) {
        if (lid < 0.3f) {
            // asleep: a little closed-eye curve
            rect.set(x - 1.3f * d, y - 1.0f * d, x + 1.3f * d, y + 1.0f * d)
            c.drawArc(rect, 20f, 140f, false, line)
            return
        }
        val wide = (lid - 1f).coerceAtLeast(0f)
        val open = lid.coerceAtMost(1f)
        val rx = (1.0f + wide * 1.1f) * d
        val ry = (0.45f + 0.8f * open + wide * 1.6f) * d
        fill.color = cOutline
        rect.set(x - rx, y - ry, x + rx, y + ry)
        c.drawOval(rect, fill)
        if (open > 0.8f) {
            fill.color = 0xFFFFFFFF.toInt()
            c.drawCircle(x + 0.35f * d, y - 0.4f * d, 0.38f * d, fill)
        }
    }

    private fun drawZs(c: Canvas, g: Float, now: Long) {
        for (born in zs) {
            val age = ((now - born) / Z_LIFE_MS.toFloat()).coerceIn(0f, 1f)
            zPaint.textSize = (3.4f + 3.2f * age) * d
            zPaint.alpha = (sin(PI.toFloat() * age) * 220f).toInt().coerceIn(0, 255)
            c.drawText("z", (10f + age * 5f) * d, g - (10f + age * 8f) * d, zPaint)
        }
    }

    /** The red "!" that pops up above the head when the cat is startled. */
    private fun drawBang(c: Canvas, g: Float, ts: Float) {
        if (ts < 0f || ts > 0.9f) return
        val p = (ts / 0.12f).coerceIn(0f, 1f)
        val sc = overshoot.getInterpolation(p)
        val alpha = 255 * (1f - ((ts - 0.65f) / 0.25f).coerceIn(0f, 1f))
        val cx = 16f * d
        val by = g - 11.5f * d
        c.save()
        c.scale(sc, sc, cx, by)
        fill.color = cBang
        fill.alpha = alpha.toInt()
        rect.set(cx - 0.8f * d, by - 6.2f * d, cx + 0.8f * d, by - 2.4f * d)
        c.drawRoundRect(rect, 0.8f * d, 0.8f * d, fill)
        c.drawCircle(cx, by - 0.9f * d, 0.9f * d, fill)
        fill.alpha = 255
        c.restore()
    }

    // ---- lifecycle ----------------------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startClock()
    }

    override fun onDetachedFromWindow() {
        stopClock()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startClock() else stopClock()
    }

    // also fires when an ancestor (the pill's holder) goes GONE / VISIBLE
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) startClock() else stopClock()
    }

    private companion object {
        const val STARTLE_COUNT = 4
        const val STARTLE_WINDOW_MS = 1500L
        const val AWAKE_MS = 3200L
        const val Z_LIFE_MS = 2600L

        /** Sprinkles on the pop-tart: x / y as a fraction of the body. */
        val SPRINKLES = arrayOf(
            floatArrayOf(0.22f, 0.32f), floatArrayOf(0.50f, 0.22f), floatArrayOf(0.78f, 0.40f),
            floatArrayOf(0.38f, 0.68f), floatArrayOf(0.66f, 0.74f)
        )
    }
}
