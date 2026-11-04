package ani.dantotsu.home.pill

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import ani.dantotsu.R
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.min

/**
 * The slim bar under the Anime / Manga list buttons.
 *
 *  - rounded like the list buttons (see [CORNER_DP]), theme-coloured with a soft tonal gradient,
 *    a glossy top edge, a hairline border, a theme-tinted glow and a slow shimmer
 *  - one line of text that slides / fades to the next one when the card changes
 *  - tiny page dots on the right: the active dot stretches into a little bar
 *
 * The whole bar is one tap target:
 *  - a tap reports its x position through [onTap] (the controller decides: text = card action,
 *    anywhere else = next card)
 *  - a horizontal swipe calls [onSwipe] (left = next card, right = previous card)
 */
class InfoPillView(context: Context) : FrameLayout(context) {

    var onTap: ((x: Float) -> Unit)? = null
    var onSwipe: ((forward: Boolean) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val corner = CORNER_DP * d

    private val base = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
    private val onBase = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary)

    // ---- drawing ------------------------------------------------------------------------------

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = base }
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
        color = ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), 52)
    }
    private val borderRect = RectF()
    private val shimmerMatrix = Matrix()
    private var shimmerShader: LinearGradient? = null
    private var shimmerWidth = 0f
    private var shimmerProgress = -1f
    private var shimmerAnim: ValueAnimator? = null

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = onBase }
    private val dotRect = RectF()
    private var dotCount = 0
    private var dotsReserve = 0
    private var dotActive = 0f
    private var dotAnim: ValueAnimator? = null

    // ---- text ---------------------------------------------------------------------------------

    private val textHost = FrameLayout(context)
    private var shown = makeText()
    private var hidden = makeText()

    // ---- touch --------------------------------------------------------------------------------

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = -1f
    private var swiping = false
    private var pressAnim: android.animation.ObjectAnimator? = null

    init {
        setWillNotDraw(false)
        clipChildren = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, corner)
            }
        }
        clipToOutline = true

        // a soft glow in the theme colour under the bar
        elevation = 5f * d
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            outlineSpotShadowColor = base
            outlineAmbientShadowColor = base
        }

        textHost.setPadding((14 * d).toInt(), 0, (4 * d).toInt(), 0)
        textHost.addView(shown)
        textHost.addView(hidden)
        hidden.alpha = 0f
        addView(textHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        isClickable = true
        isFocusable = true
        foreground = RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(onBase, 70)),
            null, ColorDrawable(0xFFFFFFFF.toInt())
        )
    }

    private fun makeText() = TextView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        letterSpacing = 0.02f
        setTextColor(onBase)
        setShadowLayer(1.6f * d, 0f, 0.7f * d, 0x40000000)
        typeface = ResourcesCompat.getFont(context, R.font.poppins_semi_bold)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // ---- touch --------------------------------------------------------------------------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x
                swiping = false
                isPressed = true
                foreground?.setHotspot(e.x, e.y)
                pressTo(0.985f, 0.9f, 110, DecelerateInterpolator())
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (!swiping && abs(dx) > slop && abs(dx) > abs(dy) * 1.2f) {
                    swiping = true
                    parent?.requestDisallowInterceptTouchEvent(true) // keep the page from scrolling
                }
            }

            MotionEvent.ACTION_UP -> {
                val dx = e.x - downX
                val dy = e.y - downY
                release()
                when {
                    swiping && abs(dx) > 28 * d -> onSwipe?.invoke(dx < 0)
                    !swiping && abs(dx) <= slop && abs(dy) <= slop -> performClick()
                }
            }

            MotionEvent.ACTION_CANCEL -> release()
        }
        return true
    }

    private fun release() {
        isPressed = false
        pressTo(1f, 1f, 260, OvershootInterpolator(2.2f))
    }

    // own animator (not animate()), so it never disturbs the controller's entrance animation
    private fun pressTo(sx: Float, sy: Float, ms: Long, ip: android.animation.TimeInterpolator) {
        pressAnim?.cancel()
        pressAnim = android.animation.ObjectAnimator.ofPropertyValuesHolder(
            this,
            android.animation.PropertyValuesHolder.ofFloat(SCALE_X, scaleX, sx),
            android.animation.PropertyValuesHolder.ofFloat(SCALE_Y, scaleY, sy)
        ).apply { duration = ms; interpolator = ip; start() }
    }

    override fun performClick(): Boolean {
        val x = if (lastX < 0f) width / 2f else lastX
        lastX = -1f
        onTap?.invoke(x)
        return super.performClick()
    }

    /** True when [x] (in this view's coordinates) is on the text itself, not on the empty sides. */
    fun isOverText(x: Float): Boolean {
        val avail = textWidthPx()
        if (avail <= 0) return true
        val textW = min(shown.paint.measureText(shown.text.toString()), avail.toFloat())
        val centre = textHost.left + textHost.paddingLeft + avail / 2f
        return abs(x - centre) <= textW / 2f + 10 * d
    }

    // ---- api ----------------------------------------------------------------------------------

    /** Dots (one per card). With 0 or 1 card the dots disappear. */
    fun setPages(count: Int, active: Int, animate: Boolean) {
        if (count != dotCount) {
            dotCount = count
            dotsReserve = if (count > 1) ((count * 8 + 10 + 18) * d).toInt() else 0
            (textHost.layoutParams as LayoutParams).marginEnd = dotsReserve
            textHost.requestLayout()
        }
        dotAnim?.cancel()
        if (animate && isLaidOut && abs(dotActive - active) > 0.01f) {
            dotAnim = ValueAnimator.ofFloat(dotActive, active.toFloat()).apply {
                duration = 260
                addUpdateListener { dotActive = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            dotActive = active.toFloat()
            invalidate()
        }
    }

    /** Makes a screen reader read the current card. */
    fun setReadableText(text: CharSequence) {
        contentDescription = text
    }

    /** Text without any transition (live countdown, data refresh). */
    fun updateText(text: CharSequence) {
        if (shown.text.toString() != text.toString()) shown.text = text
    }

    /** Text with the slide / fade transition. [forward] decides which way it travels. */
    fun showText(text: CharSequence, animate: Boolean, forward: Boolean = true) {
        shown.animate().cancel()
        hidden.animate().cancel()
        // finish whatever was moving: `shown` always holds the newest text
        shown.alpha = 1f
        shown.translationY = 0f
        hidden.alpha = 0f
        hidden.translationY = 0f

        if (!animate || !isLaidOut) {
            shown.text = text
            return
        }
        val dist = (if (forward) 1f else -1f) * 12f * d
        val outgoing = shown
        val incoming = hidden
        shown = incoming
        hidden = outgoing

        incoming.text = text
        incoming.translationY = dist
        incoming.alpha = 0f
        incoming.animate().translationY(0f).alpha(1f).setStartDelay(40).setDuration(300)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
        outgoing.animate().translationY(-dist).alpha(0f).setStartDelay(0).setDuration(240)
            .setInterpolator(AccelerateInterpolator(1.2f)).start()
    }

    private fun textWidthPx(): Int =
        (textHost.width - textHost.paddingLeft - textHost.paddingRight).coerceAtLeast(0)

    /**
     * "<title><suffix>" where only the title is shortened (with …) when it does not fit, so the
     * episode and countdown part always stays readable.
     */
    fun fitTitle(title: String, suffix: String): CharSequence {
        val avail = textWidthPx()
        if (avail <= 0) return title + suffix
        val paint: TextPaint = shown.paint
        val room = avail - paint.measureText(suffix)
        if (paint.measureText(title) <= room) return title + suffix
        if (room <= 0f) return suffix.trim().trimStart(',').trim()
        return TextUtils.ellipsize(title, paint, room, TextUtils.TruncateAt.END).toString() + suffix
    }

    // ---- look: gradient, border, shimmer, dots ------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // tonal gradient: a touch lighter on the left, a touch deeper on the right
        fillPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            ColorUtils.blendARGB(base, 0xFFFFFFFF.toInt(), 0.12f),
            ColorUtils.blendARGB(base, 0xFF000000.toInt(), 0.16f),
            Shader.TileMode.CLAMP
        )
        glossPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x33FFFFFF, 0x00FFFFFF, 0x1A000000), floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        shimmerWidth = w * 0.34f
        shimmerShader = LinearGradient(
            0f, 0f, shimmerWidth, 0f,
            intArrayOf(0x00FFFFFF, 0x40FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        shimmerPaint.shader = shimmerShader
        invalidateOutline()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, fillPaint)
        canvas.drawRect(0f, 0f, w, h, glossPaint)

        val p = shimmerProgress
        val shader = shimmerShader
        if (p in 0f..1f && shader != null) {
            // the band crosses the bar during the first part of the cycle, then rests
            val x = -shimmerWidth + (w + shimmerWidth) * p
            shimmerMatrix.setSkew(-0.35f, 0f)
            shimmerMatrix.postTranslate(x, 0f)
            shader.setLocalMatrix(shimmerMatrix)
            canvas.drawRect(0f, 0f, w, h, shimmerPaint)
        }

        val half = borderPaint.strokeWidth / 2f
        borderRect.set(half, half, w - half, h - half)
        canvas.drawRoundRect(borderRect, corner - half, corner - half, borderPaint)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (dotCount < 2) return
        val dot = 4f * d
        val bar = 10f * d
        val gap = 4f * d
        var total = 0f
        val widths = FloatArray(dotCount) { i ->
            val t = (1f - abs(i - dotActive)).coerceIn(0f, 1f)
            (dot + (bar - dot) * t).also { total += it }
        }
        total += gap * (dotCount - 1)
        var x = width - dotsReserve / 2f - total / 2f
        val cy = height / 2f
        for (i in 0 until dotCount) {
            val t = (1f - abs(i - dotActive)).coerceIn(0f, 1f)
            dotPaint.alpha = (110 + 145 * t).toInt()
            dotRect.set(x, cy - dot / 2f, x + widths[i], cy + dot / 2f)
            canvas.drawRoundRect(dotRect, dot / 2f, dot / 2f, dotPaint)
            x += widths[i] + gap
        }
    }

    private fun startShimmer() {
        if (shimmerAnim != null) return
        if (!ValueAnimator.areAnimatorsEnabled()) return
        shimmerAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 6500 // slow: ~3.5 s sweep, then a rest
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                shimmerProgress = if (t < 0.55f) t / 0.55f else -1f
                invalidate()
            }
            start()
        }
    }

    private fun stopShimmer() {
        shimmerAnim?.cancel()
        shimmerAnim = null
        shimmerProgress = -1f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (windowVisibility == VISIBLE) startShimmer()
    }

    override fun onDetachedFromWindow() {
        stopShimmer()
        dotAnim?.cancel()
        pressAnim?.cancel()
        shown.animate().cancel()
        hidden.animate().cancel()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && isAttachedToWindow) startShimmer() else stopShimmer()
    }

    companion object {
        /** Corner radius. The Anime / Manga list buttons use 16dp on a 72dp-tall card. */
        const val CORNER_DP = 8f
    }
}
