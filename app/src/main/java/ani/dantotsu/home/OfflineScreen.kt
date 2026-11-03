package ani.dantotsu.home

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import ani.dantotsu.R
import ani.dantotsu.databinding.ViewOfflineScreenBinding
import java.util.Calendar

/**
 * What "Offline mode" shows instead of the cached Home: an empty, atmospheric screen with a big
 * icon, a shimmering title and a witty line that changes every few seconds (some depend on the
 * time of day). Turns green ("Back online") when the connection returns.
 */
class OfflineScreen(private val b: ViewOfflineScreenBinding) {
    private val ctx = b.root.context
    private val offlineColor = ContextCompat.getColor(ctx, R.color.offline_accent)
    private val onlineColor = ContextCompat.getColor(ctx, R.color.online_accent)

    private val animators = mutableListOf<ObjectAnimator>()
    private var shimmer: ValueAnimator? = null
    private var recovered = false
    private var released = false

    private val lines: List<String> = buildLines()
    private var lineIndex = 0
    private val nextLine = object : Runnable {
        override fun run() {
            if (released || recovered) return
            lineIndex = (lineIndex + 1) % lines.size
            b.offlineScreenLine.animate().alpha(0f).setDuration(350L).withEndAction {
                if (released || recovered) return@withEndAction
                b.offlineScreenLine.text = lines[lineIndex]
                b.offlineScreenLine.animate().alpha(0.8f).setDuration(450L).start()
            }.start()
            b.root.postDelayed(this, LINE_MS)
        }
    }

    fun show() {
        b.offlineScreenKicker.text = kickerForNow()
        b.offlineScreenLine.text = lines.first()

        // staggered entrance
        listOf(
            b.offlineScreenIconBox, b.offlineScreenKicker, b.offlineScreenTitle,
            b.offlineScreenLine, b.offlineScreenHint
        ).forEachIndexed { i, v ->
            val target = v.alpha.takeIf { it > 0f } ?: 1f
            v.alpha = 0f
            v.translationY = 24f * ctx.resources.displayMetrics.density
            v.animate().alpha(target).translationY(0f)
                .setStartDelay(120L + i * 110L).setDuration(550L)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        startAnimations()
        b.root.postDelayed(nextLine, LINE_MS)
    }

    fun setRecovered(value: Boolean) {
        if (released || value == recovered) return
        recovered = value
        val color = if (value) onlineColor else offlineColor
        val ringBg = if (value) R.drawable.bg_online_pulse else R.drawable.bg_offline_pulse
        b.offlineScreenGlow.setBackgroundResource(if (value) R.drawable.bg_online_glow else R.drawable.bg_offline_glow)
        b.offlineScreenRingOne.setBackgroundResource(ringBg)
        b.offlineScreenRingTwo.setBackgroundResource(ringBg)
        b.offlineScreenDisc.setBackgroundResource(if (value) R.drawable.bg_online_icon_disc else R.drawable.bg_offline_icon_disc)
        b.offlineScreenIcon.setImageResource(if (value) R.drawable.ic_wifi_on_24 else R.drawable.ic_wifi_off_24)
        ImageViewCompat.setImageTintList(b.offlineScreenIcon, ColorStateList.valueOf(color))
        b.offlineScreenKicker.setTextColor(color)
        b.offlineScreenTitle.setTextColor(color)
        if (value) {
            b.offlineScreenKicker.setText(R.string.offline_screen_back_kicker)
            b.offlineScreenTitle.setText(R.string.offline_screen_back_title)
            b.offlineScreenLine.setText(R.string.offline_screen_back_line)
            b.offlineScreenHint.setText(R.string.offline_screen_back_hint)
            b.offlineScreenLine.alpha = 0.8f
        } else {
            b.offlineScreenKicker.text = kickerForNow()
            b.offlineScreenTitle.setText(R.string.offline_screen_title)
            b.offlineScreenLine.text = lines[lineIndex]
            b.offlineScreenHint.setText(R.string.offline_screen_hint)
            b.root.removeCallbacks(nextLine)
            b.root.postDelayed(nextLine, LINE_MS)
        }
        startAnimations()
    }

    fun release() {
        released = true
        b.root.removeCallbacks(nextLine)
        stopAnimations()
    }

    // ---- animation ----------------------------------------------------------------------------

    private fun stopAnimations() {
        animators.forEach { it.cancel() }
        animators.clear()
        shimmer?.cancel()
        shimmer = null
    }

    private fun startAnimations() {
        stopAnimations()
        val accent = if (recovered) onlineColor else offlineColor

        // rings: 120dp -> 1.45x = 174dp, inside the 190dp box, fading out before the edge
        listOf(b.offlineScreenRingOne to 0L, b.offlineScreenRingTwo to 1000L).forEach { (ring, delay) ->
            ring.alpha = 0f
            animators += ObjectAnimator.ofPropertyValuesHolder(
                ring,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.45f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.45f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.6f, 0f)
            ).apply {
                duration = 2000L
                startDelay = delay
                repeatCount = ObjectAnimator.INFINITE
                interpolator = DecelerateInterpolator()
                start()
            }
        }
        animators += ObjectAnimator.ofPropertyValuesHolder(
            b.offlineScreenDisc,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f)
        ).apply {
            duration = 1600L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        animators += ObjectAnimator.ofFloat(b.offlineScreenGlow, View.ALPHA, 1f, 0.55f).apply {
            duration = 2200L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        animators += ObjectAnimator.ofFloat(b.offlineScreenIcon, View.ALPHA, 1f, 0.65f).apply {
            duration = 1600L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // light sweeping across the title
        val title = b.offlineScreenTitle
        title.post {
            if (released) return@post
            val w = title.width.toFloat().coerceAtLeast(1f)
            val shader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(accent, accent, 0xFFFFFFFF.toInt(), accent, accent),
                floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
            val m = Matrix()
            title.paint.shader = shader
            shimmer = ValueAnimator.ofFloat(-w, w).apply {
                duration = 2600L
                repeatCount = ValueAnimator.INFINITE
                startDelay = 700L
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    m.setTranslate(it.animatedValue as Float, 0f)
                    shader.setLocalMatrix(m)
                    title.invalidate()
                }
                start()
            }
        }
    }

    // ---- text ---------------------------------------------------------------------------------

    private fun hour() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    private fun kickerForNow(): String = when (hour()) {
        in 5..11 -> "GOOD MORNING"
        in 12..16 -> "GOOD AFTERNOON"
        in 17..21 -> "GOOD EVENING"
        else -> "NIGHT OWL MODE"
    }

    /** Time-of-day lines first, then the general pool; each group shuffled once per launch. */
    private fun buildLines(): List<String> {
        val res = ctx.resources
        val timed = res.getStringArray(
            when (hour()) {
                in 5..11 -> R.array.offline_lines_morning
                in 12..16 -> R.array.offline_lines_afternoon
                in 17..21 -> R.array.offline_lines_evening
                else -> R.array.offline_lines_night
            }
        ).toList().shuffled()
        val generic = res.getStringArray(R.array.offline_lines_generic).toList().shuffled()
        // interleave so a time-based line shows up early but not every time
        val out = mutableListOf<String>()
        val t = timed.iterator()
        val g = generic.iterator()
        while (t.hasNext() || g.hasNext()) {
            if (g.hasNext()) out += g.next()
            if (t.hasNext()) out += t.next()
            if (g.hasNext()) out += g.next()
        }
        return out
    }

    private companion object {
        const val LINE_MS = 5500L
    }
}
