package ani.dantotsu.onboarding

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import ani.dantotsu.R
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * A tilted wall of poster tiles. Neighbouring columns drift in opposite directions forever
 * (seamless marquee) and every tile is blurred by [SoftBlurTransformation].
 *
 * Only the [TILE_COUNT] webp tiles bundled in the app (res/drawable-nodpi/onb_tile_01..32) are
 * shown. Nothing is downloaded, so the wall looks the same online and offline.
 */
class AnimeTileWall @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val container = FrameLayout(context)
    private val animators = mutableListOf<ValueAnimator>()
    private val tiles = mutableListOf<Pair<ImageView, Int>>() // tile, source index
    private var built = false
    private var wantRunning = false
    private var tileWidth = 0
    private var tileHeight = 0

    init {
        clipChildren = true
        addView(container)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun dpi(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && !built) build(w, h)
    }

    private fun build(w: Int, h: Int) {
        built = true
        val size = (hypot(w.toDouble(), h.toDouble()) * 1.05).toInt()
        container.layoutParams = LayoutParams(size, size, Gravity.CENTER)
        container.rotation = -16f

        val columns = 6
        val gap = dpi(10)
        tileWidth = (size - gap * (columns + 1)) / columns
        tileHeight = (tileWidth * 1.5f).toInt()
        val unit = tileHeight + gap
        val perColumn = ceil(size.toFloat() / unit).toInt() + 1

        for (c in 0 until columns) {
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            column.layoutParams = LayoutParams(tileWidth, unit * perColumn * 2).apply {
                leftMargin = gap + c * (tileWidth + gap)
            }
            for (i in 0 until perColumn * 2) {
                // the second half repeats the first so the loop is seamless
                val sourceIndex = (c * perColumn + i % perColumn) % TILE_COUNT
                val tile = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(0xFF2A2A3A.toInt(), 0xFF14141F.toInt())
                    ).apply { cornerRadius = dp(16f) }
                    outlineProvider = ViewOutlineProvider.BACKGROUND
                    clipToOutline = true
                    layoutParams = LinearLayout.LayoutParams(tileWidth, tileHeight).apply {
                        bottomMargin = gap
                    }
                }
                column.addView(tile)
                tiles.add(tile to sourceIndex)
            }
            container.addView(column)
            column.tag = Triple(perColumn * unit, c % 2 == 0, 32_000L + c * 4_500L)
            column.translationY = if (c % 2 == 0) 0f else -(perColumn * unit).toFloat()
        }
        bindImages()
        if (wantRunning) startAnimators()
    }

    private fun bindImages() {
        // Decode at half size: it gets blurred anyway and the upscale of a blurred image is smooth
        val w = (tileWidth / 2).coerceAtLeast(16)
        val h = (tileHeight / 2).coerceAtLeast(16)
        tiles.forEach { (view, index) ->
            Glide.with(view)
                .load(BUNDLED[index % BUNDLED.size])
                .override(w, h)
                .transform(CenterCrop(), SoftBlurTransformation(BLUR_RADIUS))
                .transition(withCrossFade(300))
                .into(view)
        }
    }

    /** Horizontal shift of the whole wall, used for a parallax effect while paging. */
    fun setParallax(px: Float) {
        container.translationX = px
    }

    fun start() {
        wantRunning = true
        alpha = 0f
        animate().alpha(1f).setDuration(900).start()
        if (built) startAnimators()
    }

    fun stop() {
        wantRunning = false
        animators.forEach { it.cancel() }
        animators.clear()
    }

    @Suppress("UNCHECKED_CAST")
    private fun startAnimators() {
        if (animators.isNotEmpty()) return
        for (i in 0 until container.childCount) {
            val column = container.getChildAt(i) as ViewGroup
            val (distance, goesUp, periodMs) = column.tag as Triple<Int, Boolean, Long>
            animators.add(ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = periodMs
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    val f = it.animatedValue as Float
                    // upward columns: 0 -> -distance, downward columns: -distance -> 0
                    column.translationY = if (goesUp) -distance * f else -distance * (1f - f)
                }
                start()
                // de-sync the columns so tiles never line up in rows
                currentPlayTime = (periodMs * (0.17f * (i + 1) % 1f)).toLong()
            })
        }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    companion object {
        /** Blur strength in pixels of the half-size decoded poster. Raise for blurrier tiles. */
        private const val BLUR_RADIUS = 1

        /** Number of tile images in the rotation (onb_tile_01 .. onb_tile_32). */
        private const val TILE_COUNT = 32

        /** The bundled webp tiles, in order. */
        private val BUNDLED: List<Int> = listOf(
            R.drawable.onb_tile_01, R.drawable.onb_tile_02, R.drawable.onb_tile_03,
            R.drawable.onb_tile_04, R.drawable.onb_tile_05, R.drawable.onb_tile_06,
            R.drawable.onb_tile_07, R.drawable.onb_tile_08, R.drawable.onb_tile_09,
            R.drawable.onb_tile_10, R.drawable.onb_tile_11, R.drawable.onb_tile_12,
            R.drawable.onb_tile_13, R.drawable.onb_tile_14, R.drawable.onb_tile_15,
            R.drawable.onb_tile_16, R.drawable.onb_tile_17, R.drawable.onb_tile_18,
            R.drawable.onb_tile_19, R.drawable.onb_tile_20, R.drawable.onb_tile_21,
            R.drawable.onb_tile_22, R.drawable.onb_tile_23, R.drawable.onb_tile_24,
            R.drawable.onb_tile_25, R.drawable.onb_tile_26, R.drawable.onb_tile_27,
            R.drawable.onb_tile_28, R.drawable.onb_tile_29, R.drawable.onb_tile_30,
            R.drawable.onb_tile_31, R.drawable.onb_tile_32
        )
    }
}
