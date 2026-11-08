package ani.dantotsu.media

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * A TextView whose last visible line can be faded out from the bottom (instead of ending in "...").
 * [fadeAmount] goes from 0 (no fade) to 1 (full fade). The bottom half of the last line is erased
 * with a soft gradient, so it works on any background colour.
 */
class FadingTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val fadePaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    /** Animators currently driving this view, so a re-bind can stop them. */
    var fadeAnimator: android.animation.Animator? = null
    var heightAnimator: android.animation.Animator? = null

    private var shaderTop = Float.NaN
    private var shaderBottom = Float.NaN

    /** How tall the soft edge is, in text lines. Grows while the synopsis is unfolding. */
    var fadeSpan: Float = 0.85f
        set(value) {
            if (value != field) {
                field = value
                invalidate()
            }
        }

    var fadeAmount: Float = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v != field) {
                field = v
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        if (fadeAmount <= 0f || height <= 0) {
            super.onDraw(canvas)
            return
        }
        val w = width.toFloat()
        val h = height.toFloat()
        val bottom = h - compoundPaddingBottom
        val top = (bottom - lineHeight * fadeSpan).coerceAtLeast(0f)
        val save = canvas.saveLayer(0f, 0f, w, h, null)
        super.onDraw(canvas)
        if (top != shaderTop || bottom != shaderBottom) {
            shaderTop = top
            shaderBottom = bottom
            fadePaint.shader = LinearGradient(
                0f, top, 0f, bottom,
                0x00000000, 0xEB000000.toInt(),
                Shader.TileMode.CLAMP
            )
        }
        fadePaint.alpha = (fadeAmount * 255).toInt()
        canvas.drawRect(0f, top, w, h, fadePaint)
        canvas.restoreToCount(save)
    }
}
