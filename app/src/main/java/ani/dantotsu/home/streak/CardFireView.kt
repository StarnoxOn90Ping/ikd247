package ani.dantotsu.home.streak

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Fire for the Anime / Manga list cards.
 *
 * The view has exactly the size of the card it burns on and draws OUTSIDE its own bounds (its
 * parents need clipChildren=false). Nothing is ever clipped with a rectangle: every flame is
 * rooted on the card's real rounded outline and points away from it, so the fire follows the
 * corners instead of ending in a straight line, and it thins out around the corners until it is
 * gone at the point where the corner meets the side.
 *
 * Life cycle:
 *
 *   WAITING   draws nothing. The card is still sliding in. [ignite] is called once it is in place.
 *   IGNITE    a flash at the top centre, then two "fuses" run along the outline in both
 *             directions (down the sides, round the bottom) and light the flames as they pass.
 *   HOLD      the whole button is on fire: flames all round, the face glows from below, the
 *             border burns hot yellow.
 *   RECEDE    the fire dies from the bottom upwards, until only the flames on top are left.
 *   SETTLED   the calm steady fire on the top edge, with a red / orange border glow that fades
 *             down the sides.
 *
 * [abortIgnition] (called when Home is scrolled) shortens IGNITE / HOLD / RECEDE to a quick
 * fade into SETTLED.
 */
class CardFireView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private enum class Phase { WAITING, IGNITE, HOLD, RECEDE, SETTLED }

    private val d = resources.displayMetrics.density

    // ---- state -------------------------------------------------------------------------------

    private var phase = Phase.WAITING
    private var igniteStart = 0L
    private var ignP = 0f // how far the fire has spread round the outline, 0..1
    private var recP = 0f // how far it has died back from the bottom, 0..1
    private var aborting = false
    private var abortStart = 0L
    private var abortMul = 1f
    private var startY = 0
    private val loc = IntArray(2)

    private var stage = 3
    private var intensity = EDGE_INTENSITY[3]
    private var boost = 1f
    private var dim = 0f
    private var animated = true
    private var sparksOn = true
    private var running = false
    private var headroom = 24f * d
    private var cornerPx = 16f * d

    private var lastNanos = 0L
    private val startNanos = System.nanoTime()
    private var spawnDebt = 0f
    private var fuseDebt = 0f

    // ---- paints ------------------------------------------------------------------------------

    private val outer = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val flashP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimRed = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rimHot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ember = Paint(Paint.ANTI_ALIAS_FLAG)
    private val allPaints = listOf(outer, mid, inner, halo, flashP, face, rimRed, rimHot, ember)

    private val path = Path()
    private val rect = RectF()
    private val shaderM = Matrix()

    // ---- outline geometry (rebuilt when the size or the corner radius changes) ----------------

    private var cw = 0f
    private var ch = 0f
    private var cr = 1f
    private var segA = 0f // half of the straight top edge
    private var segArc = 0f // one rounded corner
    private var segV = 0f // one straight side
    private var segB = 0f // the straight bottom edge
    private var perim = 0f
    private var spacing = 1f
    private var n = 0

    // One entry per flame root, walking the outline clockwise from the top centre.
    private var fx = FloatArray(0) // root x
    private var fy = FloatArray(0) // root y
    private var flean = FloatArray(0) // lean in degrees (+ = towards the right)
    private var fcos = FloatArray(0)
    private var fdist = FloatArray(0) // 0 at the top centre .. 1 at the bottom centre
    private var fset = FloatArray(0) // strength of the steady fire here (1 on top, 0 down the sides)
    private var fwTop = FloatArray(0) // weights for the full-fire height (top / side / bottom)
    private var fwSide = FloatArray(0)
    private var fwBot = FloatArray(0)
    private val pt = FloatArray(4) // x, y, normal x, normal y

    private class Spark(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var life: Float, val decay: Float, val size: Float, val hot: Boolean
    )

    private val sparks = ArrayList<Spark>()
    private val rnd = Random(11)

    private val scrollListener = ViewTreeObserver.OnScrollChangedListener {
        if (phase == Phase.IGNITE || phase == Phase.HOLD || phase == Phase.RECEDE) {
            getLocationInWindow(loc)
            // only a real scroll of Home counts (other lists scrolling sideways fire this too)
            if (abs(loc[1] - startY) > 2) abortIgnition()
        }
    }

    // ---- configuration -----------------------------------------------------------------------

    fun setStage(value: Int) {
        stage = value.coerceIn(0, StreakManager.MAX_STAGE)
        intensity = EDGE_INTENSITY[stage]
        invalidate()
    }

    /** 1 = normal. */
    fun setBoost(value: Float) {
        boost = value.coerceIn(0.5f, 1.5f)
        invalidate()
    }

    /** false = a still picture of the steady fire, no ignition, no flicker, no sparks. */
    fun setAnimated(value: Boolean) {
        if (animated == value) return
        animated = value
        if (!value && phase != Phase.WAITING) phase = Phase.SETTLED
        aborting = false
        lastNanos = 0L
        updateRunning()
        invalidate()
    }

    fun setSparks(value: Boolean) {
        sparksOn = value
        if (!value) sparks.clear()
        invalidate()
    }

    /** @param value 0f = burning, 1f = grey and nearly out */
    fun setDim(value: Float) {
        dim = value.coerceIn(0f, 1f)
        val filter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1f - dim * 0.9f) })
        allPaints.forEach { it.colorFilter = filter }
        invalidate()
    }

    /** How far above the card the fire may reach, in px. Nothing is ever drawn higher. */
    fun setHeadroom(px: Float) {
        headroom = px.coerceIn(0f, 80f * d)
        invalidate()
    }

    /** Corner radius of the card, in px. */
    fun setCornerRadius(px: Float) {
        cornerPx = px
        computeGeometry()
        buildShaders()
        invalidate()
    }

    /** Starts the ignition. Call it once the card is in place. Does nothing the second time. */
    fun ignite() {
        if (phase != Phase.WAITING) return
        clearAnimation() // a layout animation may have been attached; the fire never slides
        if (!animated) {
            phase = Phase.SETTLED
            invalidate()
            return
        }
        igniteStart = System.nanoTime()
        aborting = false
        abortMul = 1f
        ignP = 0f
        recP = 0f
        phase = Phase.IGNITE
        lastNanos = 0L
        if (isAttachedToWindow) {
            getLocationInWindow(loc)
            startY = loc[1]
        }
        updateRunning()
        invalidate()
    }

    /** Ends the ignition early: the full fire fades away quickly and only the top flames stay. */
    fun abortIgnition() {
        if (aborting) return
        if (phase == Phase.IGNITE || phase == Phase.HOLD || phase == Phase.RECEDE) {
            aborting = true
            abortStart = System.nanoTime()
        }
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    private fun updateRunning() {
        val shouldRun = animated && phase != Phase.WAITING && isShown && isAttachedToWindow
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
        viewTreeObserver.addOnScrollChangedListener(scrollListener)
        updateRunning()
    }

    override fun onDetachedFromWindow() {
        running = false
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnScrollChangedListener(scrollListener)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateRunning()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeGeometry()
        buildShaders()
    }

    // ---- geometry ----------------------------------------------------------------------------

    /**
     * Point on the card's rounded outline [sIn] px along it, walking clockwise from the top centre.
     * Result in [pt]: x, y and the outward normal (nx, ny).
     */
    private fun pointAt(sIn: Float) {
        var s = sIn
        val w = cw
        val h = ch
        val r = cr
        if (s < segA) { setPt(w / 2f + s, 0f, 0f, -1f); return }
        s -= segA
        if (s < segArc) { arc(w - r, r, -PI.toFloat() / 2f + s / r, r); return }
        s -= segArc
        if (s < segV) { setPt(w, r + s, 1f, 0f); return }
        s -= segV
        if (s < segArc) { arc(w - r, h - r, s / r, r); return }
        s -= segArc
        if (s < segB) { setPt(w - r - s, h, 0f, 1f); return }
        s -= segB
        if (s < segArc) { arc(r, h - r, PI.toFloat() / 2f + s / r, r); return }
        s -= segArc
        if (s < segV) { setPt(0f, h - r - s, -1f, 0f); return }
        s -= segV
        if (s < segArc) { arc(r, r, PI.toFloat() + s / r, r); return }
        s -= segArc
        setPt(r + s, 0f, 0f, -1f)
    }

    private fun setPt(x: Float, y: Float, nx: Float, ny: Float) {
        pt[0] = x; pt[1] = y; pt[2] = nx; pt[3] = ny
    }

    private fun arc(cx: Float, cy: Float, a: Float, r: Float) {
        val nx = cos(a)
        val ny = sin(a)
        setPt(cx + r * nx, cy + r * ny, nx, ny)
    }

    private fun computeGeometry() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        cw = w
        ch = h
        cr = min(cornerPx, min(w, h) / 2f).coerceAtLeast(1f)
        segA = w / 2f - cr
        segArc = (PI * cr / 2.0).toFloat()
        segV = h - 2f * cr
        segB = w - 2f * cr
        perim = 2f * segA + segB + 2f * segV + 4f * segArc
        val count = max(12, (perim / (8.5f * d)).roundToInt())
        spacing = perim / count
        if (count != n) {
            n = count
            fx = FloatArray(n); fy = FloatArray(n); flean = FloatArray(n); fcos = FloatArray(n)
            fdist = FloatArray(n); fset = FloatArray(n)
            fwTop = FloatArray(n); fwSide = FloatArray(n); fwBot = FloatArray(n)
        }
        val inset = ROOT_INSET * d // roots sit a little inside the card, so the fire grows out of its edge
        for (k in 0 until n) {
            val s = k * spacing
            pointAt(s)
            val nx = pt[2]
            val ny = pt[3]
            fx[k] = pt[0] - nx * inset
            fy[k] = pt[1] - ny * inset
            fdist[k] = min(s, perim - s) / (perim / 2f)
            // angle of the outward normal away from "straight up": 0 on top, 90 at the sides, 180 at the bottom
            val a = abs(Math.toDegrees(atan2(nx.toDouble(), (-ny).toDouble())).toFloat())
            val sign = if (nx >= 0f) 1f else -1f
            // steady fire: full on the straight top, tapering round the corner, gone where it meets the side
            fset[k] = if (a >= 88f) 0f else (1f - a / 88f).pow(1.4f)
            // flames lean away from the card round the corner, and stand up straight at the bottom
            flean[k] = sign * (if (a <= 90f) a * 0.5f else 45f * (1f - (a - 90f) / 90f))
            fcos[k] = max(0.5f, cos(Math.toRadians(flean[k].toDouble())).toFloat())
            fwTop[k] = if (a <= 90f) 1f - a / 90f else 0f
            fwSide[k] = if (a <= 90f) a / 90f else (180f - a) / 90f
            fwBot[k] = if (a > 90f) (a - 90f) / 90f else 0f
        }
    }

    private fun buildShaders() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // flame-local gradients: y = 0 is the root, y = -100 the tip (scaled per flame when drawn)
        val pos = floatArrayOf(0f, 0.55f, 1f)
        fun grad(c0: Int, c1: Int, c2: Int) =
            LinearGradient(0f, 0f, 0f, -100f, intArrayOf(c0, c1, c2), pos, Shader.TileMode.CLAMP)
        outer.shader = grad(0xFFFF9A1F.toInt(), 0xE6FF4A00.toInt(), 0x00D32F00)
        mid.shader = grad(0xFFFFD54F.toInt(), 0xE6FFA000.toInt(), 0x00FF9100)
        inner.shader = grad(0xFFFFFFF0.toInt(), 0xE6FFF0A0.toInt(), 0x00FFE082)

        val rx = haloRx()
        halo.shader = RadialGradient(
            w / 2f, 0f, rx,
            intArrayOf(0xFFFF7A00.toInt(), 0x80FF5500.toInt(), 0x00FF5500),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        flashP.shader = RadialGradient(
            w / 2f, 0f, rx,
            intArrayOf(0xFFFFF6D5.toInt(), 0x99FFC24D.toInt(), 0x00FF7A00),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP
        )

        // the face of the card lights up from the bottom while the whole button burns
        face.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0x00FF3D00, 0x55FF4A00, 0xFFFF7A00.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )

        // the border: red-orange at the top, fading away down the sides
        rimRed.shader = LinearGradient(
            0f, -4f * d, 0f, h,
            intArrayOf(0xFFFF5A00.toInt(), 0xCCFF3D00.toInt(), 0x55D62800, 0x00B71C1C),
            floatArrayOf(0f, 0.22f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        // white-hot yellow on the very top of the border, only really visible while it burns
        rimHot.shader = LinearGradient(
            0f, -4f * d, 0f, h * 0.75f,
            intArrayOf(0xFFFFF0B0.toInt(), 0xE6FFC400.toInt(), 0x00FF8F00),
            floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP
        )
    }

    private fun haloRx() = cw / 2f + 10f * d

    // ---- timeline ----------------------------------------------------------------------------

    private fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun easeOut(x: Float): Float {
        val t = 1f - x.coerceIn(0f, 1f)
        return 1f - t * t * t
    }

    private fun advance(now: Long) {
        if (phase == Phase.WAITING || phase == Phase.SETTLED) return
        if (aborting) {
            val a = (now - abortStart) / 1e6f / ABORT_MS
            if (a >= 1f) {
                aborting = false
                abortMul = 1f
                phase = Phase.SETTLED
            } else {
                abortMul = 1f - smooth(a)
            }
            return
        }
        val t = (now - igniteStart) / 1e6f
        when {
            t < IGNITE_MS -> { phase = Phase.IGNITE; ignP = easeOut(t / IGNITE_MS); recP = 0f }
            t < IGNITE_MS + HOLD_MS -> { phase = Phase.HOLD; ignP = 1f; recP = 0f }
            t < IGNITE_MS + HOLD_MS + RECEDE_MS -> {
                phase = Phase.RECEDE
                ignP = 1f
                recP = smooth((t - IGNITE_MS - HOLD_MS) / RECEDE_MS)
            }
            else -> phase = Phase.SETTLED
        }
    }

    /**
     * How much the full fire burns at a place on the outline ([dist01]: 0 at the top centre,
     * 1 at the bottom centre). It is lit from the top by the fuses and dies from the bottom up.
     */
    private fun engulfCov(dist01: Float): Float {
        if (phase == Phase.WAITING || phase == Phase.SETTLED) return 0f
        val ign = smooth((ignP * 1.15f - dist01) / 0.15f)
        val rem = smooth((recP * 1.15f - (1f - dist01)) / 0.15f)
        return ign * (1f - rem) * abortMul
    }

    // ---- drawing -----------------------------------------------------------------------------

    /** Smooth pseudo-noise in -1..1. */
    private fun noise(t: Float, phase: Float): Float =
        (sin(t * 2.3f + phase) + 0.6f * sin(t * 3.9f + phase * 1.7f) + 0.35f * sin(t * 6.1f + phase * 0.6f)) / 1.95f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (phase == Phase.WAITING) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || n == 0) return

        val now = System.nanoTime()
        val dt = if (!animated || lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceAtMost(0.05f)
        lastNanos = now
        val time = if (animated) (now - startNanos) / 1e9f else 2.6f
        advance(now)

        val i = (intensity * boost * (1f - 0.45f * dim)).coerceIn(0f, 1.2f)
        if (i <= 0f) return
        val ii = min(i, 1f)
        val t = time * (1f - 0.4f * dim)
        val burning = phase == Phase.IGNITE || phase == Phase.HOLD || phase == Phase.RECEDE
        val burn = if (burning) ignP * (1f - recP) * abortMul else 0f
        val flash = if (phase == Phase.IGNITE && !aborting) {
            val x = ((now - igniteStart) / 1e6f - 90f) / 150f
            exp(-x * x)
        } else 0f
        val pulse = 0.88f + 0.12f * sin(t * 4.2f) + 0.04f * sin(t * 9.1f)
        rect.set(0f, 0f, cw, ch)

        // 1. heat haze above the card (it fades out sideways, so it never ends in a box)
        drawHalo(canvas, halo, (0.30f + 0.35f * ii + 0.25f * burn) * pulse, (12f + 22f * burn) * d)
        if (flash > 0.01f) drawHalo(canvas, flashP, flash * 0.9f, 16f * d)

        // 2. the face of the card glows from below while the whole button burns
        if (burn > 0.01f) {
            face.alpha = ((burn * 0.5f).coerceIn(0f, 1f) * 255f).toInt()
            canvas.drawRoundRect(rect, cr, cr, face)
        }

        // 3. the border: a red / orange glow that is strongest at the top and fades down the sides
        val rimStrength = 0.7f + 0.3f * ii
        shaderM.setScale(1f, 1f + 0.9f * burn, 0f, 0f)
        rimRed.shader?.setLocalMatrix(shaderM)
        for (j in RIM_W.indices) {
            rimRed.strokeWidth = RIM_W[j] * d
            rimRed.alpha = ((RIM_A[j] * rimStrength * pulse * (1f + 0.5f * burn)).coerceIn(0f, 1f) * 255f).toInt()
            canvas.drawRoundRect(rect, cr, cr, rimRed)
        }
        val heat = (0.2f + 0.7f * burn + 0.35f * flash).coerceIn(0f, 1f)
        shaderM.setScale(1f, 1f + 0.6f * burn, 0f, 0f)
        rimHot.shader?.setLocalMatrix(shaderM)
        for (j in HOT_W.indices) {
            rimHot.strokeWidth = HOT_W[j] * d
            rimHot.alpha = ((HOT_A[j] * heat * pulse).coerceIn(0f, 1f) * 255f).toInt()
            canvas.drawRoundRect(rect, cr, cr, rimHot)
        }

        // 4. the flames
        drawFlames(canvas, t, ii)

        // 5. sparks and the two fuses that light the button
        if (animated && sparksOn) updateSparks(canvas, dt, ii, burn)
        if (phase == Phase.IGNITE && !aborting && ignP < 0.98f) drawFuses(canvas)

        if (running) postInvalidateOnAnimation()
    }

    private fun drawHalo(canvas: Canvas, p: Paint, alpha: Float, downPx: Float) {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        if (a <= 0) return
        p.alpha = a
        val rx = haloRx()
        val cx = cw / 2f
        if (headroom > 0f) {
            shaderM.setScale(1f, headroom / rx, cx, 0f)
            p.shader?.setLocalMatrix(shaderM)
            canvas.drawRect(cx - rx, -headroom, cx + rx, 0f, p)
        }
        shaderM.setScale(1f, downPx / rx, cx, 0f)
        p.shader?.setLocalMatrix(shaderM)
        canvas.drawRect(cx - rx, 0f, cx + rx, downPx, p)
    }

    /** A teardrop whose tip leads and whose body lags, so it licks instead of just swaying. */
    private fun flame(canvas: Canvas, w: Float, h: Float, t: Float, ph: Float, lean: Float, paint: Paint) {
        val s1 = noise(t * 1.5f, ph) * w * 0.14f
        val s2 = noise(t * 1.5f - 0.45f, ph + 1.3f) * w * 0.55f
        val tx = lean * h * 0.16f + s2
        val ty = -h * (0.88f + 0.12f * noise(t * 2.1f, ph + 2f))
        path.reset()
        path.moveTo(-w / 2f, 0f)
        path.cubicTo(-w * 0.62f + s1, -h * 0.30f, tx - w * 0.30f + s2 * 0.15f, -h * 0.62f, tx, ty)
        path.cubicTo(tx + w * 0.30f + s2 * 0.15f, -h * 0.62f, w * 0.62f + s1, -h * 0.30f, w / 2f, 0f)
        path.quadTo(0f, w * 0.3f, -w / 2f, 0f)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawFlames(canvas: Canvas, t: Float, ii: Float) {
        val inset = ROOT_INSET * d
        // steady fire on top, and the taller fire of the full burn (top / sides / bottom)
        val reachS = min(headroom + inset, 42f * d) * (0.55f + 0.45f * ii)
        val reachE = min(headroom + inset, 52f * d) * 1.15f
        val sideE = ch * 0.62f
        val botE = ch * 0.50f
        for (layer in 0 until 3) {
            val paint = if (layer == 0) outer else if (layer == 1) mid else inner
            val sc = if (layer == 0) 1f else if (layer == 1) 0.68f else 0.38f
            val grow = if (layer == 0) 1f else sc * 1.05f
            for (k in 0 until n) {
                val eng = engulfCov(fdist[k])
                val hS = reachS * fset[k]
                if (hS < 0.5f * d && eng < 0.02f) continue
                val hE = fwTop[k] * reachE + fwSide[k] * sideE + fwBot[k] * botE
                val base = hS + (max(hE, hS) - hS) * eng
                if (base < 1.5f * d) continue
                val n01 = 0.5f + 0.5f * noise(t * 1.3f, k * 2.37f)
                // slow waves of taller flames travelling along the outline
                val wave = 0.82f + 0.18f * sin(t * 1.7f - k * 0.55f)
                var fh = base * (0.35f + 0.65f * n01) * wave * grow
                // a flame's tip never goes higher than the free space above the card
                fh = min(fh, (fy[k] + headroom) / fcos[k])
                if (fh < 1.5f * d) continue
                paint.alpha = ((fh / (7f * d)).coerceIn(0f, 1f) * 255f).toInt()
                val wob = noise(t * 0.5f, k.toFloat())
                canvas.save()
                canvas.translate(fx[k], fy[k])
                canvas.rotate(flean[k] + wob * 7f)
                shaderM.setScale(1f, fh / 100f)
                paint.shader?.setLocalMatrix(shaderM)
                flame(canvas, spacing * 2.3f * sc, fh, t * 1.1f, k * 2.37f + sc * 3f, wob * 0.7f, paint)
                canvas.restore()
            }
        }
    }

    // ---- sparks and fuses --------------------------------------------------------------------

    private fun spawnAt(k: Int, speedMul: Float) {
        if (sparks.size >= MAX_SPARKS) return
        val lean = Math.toRadians(flean[k].toDouble()).toFloat()
        val sp = (24f + 34f * rnd.nextFloat()) * d * speedMul
        sparks += Spark(
            fx[k] + (rnd.nextFloat() - 0.5f) * spacing, fy[k],
            sin(lean) * sp * 0.8f + (rnd.nextFloat() - 0.5f) * 12f * d,
            -cos(lean) * sp,
            1f, 0.75f + 0.6f * rnd.nextFloat(),
            (0.8f + 1.4f * rnd.nextFloat()) * d,
            rnd.nextFloat() < 0.4f
        )
    }

    private fun updateSparks(canvas: Canvas, dt: Float, ii: Float, burn: Float) {
        val rate = if (dim > 0.85f) 0f else 3f + 13f * ii + 22f * burn
        spawnDebt += rate * dt
        var guard = 0
        while (spawnDebt >= 1f && guard++ < 6) {
            spawnDebt -= 1f
            var k = -1
            for (tries in 0 until 6) {
                val c = rnd.nextInt(n)
                val cov = max(fset[c] * (0.6f + 0.4f * ii), engulfCov(fdist[c]))
                if (rnd.nextFloat() < cov) { k = c; break }
            }
            if (k >= 0) spawnAt(k, 1f)
        }
        if (spawnDebt > 3f) spawnDebt = 3f

        // while the fuses run, they throw sparks
        if (phase == Phase.IGNITE && !aborting && dim <= 0.85f) {
            fuseDebt += 70f * dt
            var g = 0
            while (fuseDebt >= 1f && g++ < 4) {
                fuseDebt -= 1f
                val head = (ignP * 1.15f * (perim / 2f) / spacing).roundToInt().coerceIn(0, n / 2)
                spawnAt(if (rnd.nextBoolean()) head else (n - head) % n, 1.3f)
            }
            if (fuseDebt > 3f) fuseDebt = 3f
        }

        val iter = sparks.iterator()
        while (iter.hasNext()) {
            val s = iter.next()
            s.life -= dt * s.decay
            if (s.life <= 0f || s.y < -headroom) { iter.remove(); continue }
            s.x += s.vx * dt + sin(s.life * 9f + s.size) * 0.35f * d
            s.y += s.vy * dt * 0.85f
            // they fade out as they near the top of the free space
            val ceiling = ((s.y + headroom) / (headroom * 0.4f + 1f)).coerceIn(0f, 1f)
            val a = s.life.coerceIn(0f, 1f) * ceiling
            val rad = s.size * (0.5f + 0.5f * s.life)
            val col = if (s.hot && s.life > 0.5f) 0xFFE082 else if (s.life > 0.55f) 0xFFC107 else 0xFF6D00
            ember.color = ((a * 0.28f * 255f).toInt() shl 24) or col
            canvas.drawCircle(s.x, s.y, rad * 3f, ember)
            ember.color = ((a * 255f).toInt() shl 24) or col
            canvas.drawCircle(s.x, s.y, rad, ember)
        }
    }

    private fun drawFuses(canvas: Canvas) {
        val fa = (1f - ignP).coerceIn(0f, 1f)
        val fd = ignP * 1.15f * (perim / 2f)
        for (side in 0..1) {
            val s = (if (side == 0) fd else perim - fd).coerceIn(0f, perim - 0.01f)
            pointAt(s)
            ember.color = ((0.35f * (0.4f + 0.6f * fa) * 255f).toInt() shl 24) or 0xFFB300
            canvas.drawCircle(pt[0], pt[1], 8f * d, ember)
            ember.color = ((0.95f * 255f).toInt() shl 24) or 0xFFF3C4
            canvas.drawCircle(pt[0], pt[1], 2.6f * d, ember)
        }
    }

    private companion object {
        /** How fierce the fire is per stage (0..1). The list buttons only burn from stage 3 on. */
        val EDGE_INTENSITY = floatArrayOf(0f, 0f, 0f, 0.45f, 0.72f, 1f)

        const val ROOT_INSET = 3f // dp: flames are rooted this far inside the card's edge
        const val MAX_SPARKS = 30

        // ignition timeline, in ms
        const val IGNITE_MS = 850f // the fuses run round the button
        const val HOLD_MS = 1200f // the whole button burns
        const val RECEDE_MS = 1400f // the fire dies from the bottom up
        const val ABORT_MS = 380f // quick fade when Home is scrolled

        // border glow layers (dp, alpha)
        val RIM_W = floatArrayOf(10f, 5.5f, 2.6f)
        val RIM_A = floatArrayOf(0.10f, 0.22f, 0.85f)
        val HOT_W = floatArrayOf(4f, 1.4f)
        val HOT_A = floatArrayOf(0.5f, 0.9f)
    }
}
