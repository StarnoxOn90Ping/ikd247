package nox.kiwami.home

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.appcompat.R as AppCompatR
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

object NavGlow {

    private const val SPREAD_DP = 16f
    private const val PEAK_ALPHA = 64
    private const val FALLOFF = 2f
    private const val RING_OVERLAP_PX = 1f

    private class GlowDrawable(
        base: Drawable,
        private val oval: Boolean,
        private val radiusPx: Float,
        private val spreadPx: Float,
        private val accent: Int
    ) : DrawableWrapper(base) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            val b = bounds
            val rings = ceil(spreadPx).toInt().coerceAtLeast(1)
            val width = spreadPx / rings
            val half = min(b.width(), b.height()) / 2f
            val corner = if (oval) half else min(radiusPx, half)
            paint.strokeWidth = width + RING_OVERLAP_PX
            for (i in 0 until rings) {
                val t = (i + 0.5f) / rings
                val alpha = (PEAK_ALPHA * (1f - t).pow(FALLOFF)).roundToInt()
                if (alpha <= 0) continue
                val grow = i * width + width / 2f
                rect.set(b.left - grow, b.top - grow, b.right + grow, b.bottom + grow)
                paint.color = ColorUtils.setAlphaComponent(accent, alpha)
                canvas.drawRoundRect(rect, corner + grow, corner + grow, paint)
            }
            super.draw(canvas)
        }
    }

    fun wrap(view: View, shape: Int, radiusDp: Float) {
        val base = view.background ?: return
        if (base is GlowDrawable) return
        val density = view.resources.displayMetrics.density
        val accent = MaterialColors.getColor(view, AppCompatR.attr.colorPrimary)
        view.background = GlowDrawable(
            base,
            shape == GradientDrawable.OVAL,
            radiusDp * density,
            SPREAD_DP * density,
            accent
        )
    }
}
