package ani.dantotsu.onboarding

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * A tilted wall of anime poster tiles. Neighbouring columns drift in opposite directions forever
 * (seamless marquee), the whole wall is lightly blurred and sits behind the onboarding pages.
 * Until posters arrive (or when offline) the tiles are soft gradient placeholders.
 */
class AnimeTileWall @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val container = FrameLayout(context)
    private val animators = mutableListOf<ValueAnimator>()
    private val tiles = mutableListOf<Pair<ImageView, Int>>() // tile, poster index
    private var urls: List<String> = emptyList()
    private var built = false
    private var wantRunning = false
    private var tileWidth = 0
    private var tileHeight = 0

    private val placeholderPalette = listOf(
        0xFF6A3FD1.toInt() to 0xFF3B1E78.toInt(),
        0xFF1F8FA8.toInt() to 0xFF0F3C5F.toInt(),
        0xFFD1457B.toInt() to 0xFF4A1259.toInt(),
        0xFF2BAE66.toInt() to 0xFF14532D.toInt(),
        0xFFE8903A.toInt() to 0xFF7C2D12.toInt(),
        0xFF3B82F6.toInt() to 0xFF1E3A8A.toInt()
    )

    init {
        clipChildren = true
        addView(container)
        // "A bit blurred, but not too much"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            container.setRenderEffect(
                RenderEffect.createBlurEffect(dp(2.2f), dp(2.2f), Shader.TileMode.CLAMP)
            )
        }
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
                val posterIndex = c * 5 + i % perColumn
                val tile = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    val (top, bottom) = placeholderPalette[(c + i % perColumn) % placeholderPalette.size]
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR, intArrayOf(top, bottom)
                    ).apply { cornerRadius = dp(16f) }
                    outlineProvider = ViewOutlineProvider.BACKGROUND
                    clipToOutline = true
                    layoutParams = LinearLayout.LayoutParams(tileWidth, tileHeight).apply {
                        bottomMargin = gap
                    }
                }
                column.addView(tile)
                tiles.add(tile to posterIndex)
            }
            container.addView(column)
            column.tag = Triple(perColumn * unit, c % 2 == 0, 30_000L + c * 4_500L)
            column.translationY = if (c % 2 == 0) 0f else -(perColumn * unit).toFloat()
        }
        bindImages()
        if (wantRunning) startAnimators()
    }

    fun setImages(posterUrls: List<String>) {
        urls = posterUrls
        if (built) bindImages()
    }

    private fun bindImages() {
        if (urls.isEmpty()) return
        // Android 12+ blurs with RenderEffect; below that decode tiny and let the upscale soften it
        val downscale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 1 else 5
        tiles.forEach { (view, index) ->
            Glide.with(view)
                .load(urls[index % urls.size])
                .override(
                    (tileWidth / downscale).coerceAtLeast(8),
                    (tileHeight / downscale).coerceAtLeast(8)
                )
                .centerCrop()
                .transition(withCrossFade(600))
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
}
