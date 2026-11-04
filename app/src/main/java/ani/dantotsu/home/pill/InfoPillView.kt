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
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import ani.dantotsu.R
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.max

/**
 * The slim pill under the Anime / Manga list buttons.
 *
 *  - fully rounded, filled with the theme colour, with a slow shimmer sweeping across
 *  - one line of text that slides / fades to the next one when the card changes
 *  - tiny page dots on the right: the active dot stretches into a little bar
 *
 * Two tap zones: the **text area** ([onContentClick]) and the **dots area** at the right end
 * ([onNextClick]), which always moves to the next card.
 */
class InfoPillView(context: Context) : FrameLayout(context) {

    var onContentClick: (() -> Unit)? = null
    var onNextClick: (() -> Unit)? = null

    private val d = resources.displayMetrics.density

    private val fill = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
    private val onFill = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary)

    // ---- drawing ------------------------------------------------------------------------------

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shimmerMatrix = Matrix()
    private var shimmerShader: LinearGradient? = null
    private var shimmerWidth = 0f
    private var shimmerProgress = -1f
    private var shimmerAnim: ValueAnimator? = null

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = onFill }
    private val dotRect = RectF()
    private var dotCount = 0
    private var dotActive = 0f
    private var dotAnim: ValueAnimator? = null

    // ---- text ---------------------------------------------------------------------------------

    private val textHost = FrameLayout(context)
    private var shown = makeText()
    private var hidden = makeText()
    private val dotsZone = View(context)

    init {
        setWillNotDraw(false)
        clipChildren = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }
        clipToOutline = true
        contentDescription = null

        textHost.setPadding((14 * d).toInt(), 0, (4 * d).toInt(), 0)
        textHost.addView(shown)
        textHost.addView(hidden)
        hidden.alpha = 0f
        textHost.isClickable = true
        textHost.foreground = ripple()
        textHost.setOnClickListener { onContentClick?.invoke() }
        addView(textHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        dotsZone.isClickable = true
        dotsZone.foreground = ripple()
        dotsZone.visibility = GONE
        dotsZone.setOnClickListener { onNextClick?.invoke() }
        addView(dotsZone, LayoutParams(0, LayoutParams.MATCH_PARENT, Gravity.END))
    }

    private fun ripple() = RippleDrawable(
        ColorStateList.valueOf(ColorUtils.setAlphaComponent(onFill, 60)), null, ColorDrawable(0xFFFFFFFF.toInt())
    )

    private fun makeText() = TextView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        setTextColor(onFill)
        typeface = ResourcesCompat.getFont(context, R.font.poppins_semi_bold)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // ---- api ----------------------------------------------------------------------------------

    /** Dots (one per card). With 0 or 1 card the dots area disappears. */
    fun setPages(count: Int, active: Int, animate: Boolean) {
        if (count != dotCount) {
            dotCount = count
            val showDots = count > 1
            dotsZone.visibility = if (showDots) VISIBLE else GONE
            val zoneW = if (showDots) max((40 * d).toInt(), ((count * 8 + 10 + 16) * d).toInt()) else 0
            dotsZone.layoutParams = (dotsZone.layoutParams as LayoutParams).also { it.width = zoneW }
            (textHost.layoutParams as LayoutParams).marginEnd = zoneW
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
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
        outgoing.animate().translationY(-dist).alpha(0f).setStartDelay(0).setDuration(240)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.2f)).start()
    }

    /** Width available for the text, for [fitTitle]. */
    private fun textWidthPx(): Int =
        (textHost.width - textHost.paddingLeft - textHost.paddingRight).let { if (it > 0) it else 0 }

    /**
     * "<title><suffix>" where only the title is shortened (with …) when it does not fit, so the
     * episode and countdown part always stays readable.
     */
    fun fitTitle(title: String, suffix: String): CharSequence {
        val avail = textWidthPx()
        if (avail <= 0) return title + suffix
        val paint: TextPaint = shown.paint
        val suffixW = paint.measureText(suffix)
        val room = avail - suffixW
        if (paint.measureText(title) <= room) return title + suffix
        if (room <= 0f) return suffix.trim().trimStart(',').trim()
        return TextUtils.ellipsize(title, paint, room, TextUtils.TruncateAt.END).toString() + suffix
    }

    // ---- shimmer + dots -----------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shimmerWidth = w * 0.34f
        shimmerShader = LinearGradient(
            0f, 0f, shimmerWidth, 0f,
            intArrayOf(0x00FFFFFF, 0x40FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        shimmerPaint.shader = shimmerShader
        glossPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x26FFFFFF, 0x00FFFFFF, 0x14000000), floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        invalidateOutline() // re-evaluate the rounded outline for the new size
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, fillPaint)
        canvas.drawRect(0f, 0f, w, h, glossPaint)

        val p = shimmerProgress
        val shader = shimmerShader
        if (p in 0f..1f && shader != null) {
            // the band crosses the pill during the first part of the cycle, then rests
            val x = -shimmerWidth + (w + shimmerWidth) * p
            shimmerMatrix.setSkew(-0.35f, 0f)
            shimmerMatrix.postTranslate(x, 0f)
            shader.setLocalMatrix(shimmerMatrix)
            canvas.drawRect(0f, 0f, w, h, shimmerPaint)
        }
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
        var x = width - dotsZone.width / 2f - total / 2f
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
        shown.animate().cancel()
        hidden.animate().cancel()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && isAttachedToWindow) startShimmer() else stopShimmer()
    }
}
