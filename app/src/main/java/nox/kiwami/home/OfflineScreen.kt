package nox.kiwami.home

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.TransitionDrawable
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import nox.kiwami.R
import nox.kiwami.databinding.ViewOfflineScreenBinding
import java.util.Calendar

/**
 * What "Offline mode" shows instead of the cached Home: an empty, atmospheric screen with a big
 * icon, a shimmering title and a witty line. The line changes when the user comes back to the
 * screen after using something else (see [nextLine]). When the connection returns everything
 * morphs to green ("Back online") with cross-fades instead of snapping.
 */
class OfflineScreen(private val b: ViewOfflineScreenBinding) {
    private val ctx = b.root.context
    private val density = ctx.resources.displayMetrics.density
    private val offlineColor = ContextCompat.getColor(ctx, R.color.offline_accent)
    private val onlineColor = ContextCompat.getColor(ctx, R.color.online_accent)

    /** The accent currently on screen. It is animated between offline/online amber and green. */
    private var accent = offlineColor
    private var sweep = 0f // 0..1 position of the light sweeping across the title
    private var colorAnimator: ValueAnimator? = null
    private val loops = mutableListOf<Animator>()
    private var recovered = false
    private var released = false

    private val lines: List<String> = buildLines()
    private var lineIndex = 0

    fun show() {
        b.offlineScreenKicker.text = kickerForNow()
        b.offlineScreenLine.text = lines.first()

        // Text: staggered fade-up.
        listOf(
            b.offlineScreenKicker, b.offlineScreenTitle, b.offlineScreenLine, b.offlineScreenHint
        ).forEachIndexed { i, v ->
            val target = v.alpha.takeIf { it > 0f } ?: 1f
            v.alpha = 0f
            v.translationY = 24f * density
            v.animate().alpha(target).translationY(0f)
                .setStartDelay(220L + i * 110L).setDuration(550L)
                .setInterpolator(DecelerateInterpolator()).start()
        }

        // Icon area: NEVER animate this box's alpha. A view with alpha < 1 is drawn into an
        // off-screen layer clipped to its own bounds, which cut the 340dp glow down to the 190dp
        // box for a moment (the "square glow"). Only the disc fades; the glow fades on its own.
        b.offlineScreenIconBox.translationY = 24f * density
        b.offlineScreenIconBox.animate().translationY(0f)
            .setStartDelay(120L).setDuration(550L)
            .setInterpolator(DecelerateInterpolator()).start()
        b.offlineScreenDisc.alpha = 0f
        b.offlineScreenDisc.animate().alpha(1f)
            .setStartDelay(120L).setDuration(550L).start()

        startLoops()
    }

    /** Called when the user returns to this screen (e.g. back from Downloads): a fresh line. */
    fun nextLine() {
        if (released || recovered) return
        lineIndex = (lineIndex + 1) % lines.size
        swapText(b.offlineScreenLine, 0.8f, 0L) { text = lines[lineIndex] }
    }

    fun setRecovered(value: Boolean) {
        if (released || value == recovered) return
        recovered = value

        // 1. colours glide from amber to green (or back) – title, kicker, icon tint, shimmer
        colorAnimator?.cancel()
        colorAnimator = ValueAnimator.ofObject(
            ArgbEvaluator(), accent, if (value) onlineColor else offlineColor
        ).apply {
            duration = 800L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                accent = it.animatedValue as Int
                applyAccent()
            }
            start()
        }

        // 2. glow, rings and disc cross-fade into their green versions
        crossfade(b.offlineScreenGlow, R.drawable.bg_offline_glow, R.drawable.bg_online_glow, value)
        crossfade(b.offlineScreenRingOne, R.drawable.bg_offline_pulse, R.drawable.bg_online_pulse, value)
        crossfade(b.offlineScreenRingTwo, R.drawable.bg_offline_pulse, R.drawable.bg_online_pulse, value)
        crossfade(b.offlineScreenDisc, R.drawable.bg_offline_icon_disc, R.drawable.bg_online_icon_disc, value)

        // 3. icon: shrinks + spins away, swaps, then springs back
        val icon = b.offlineScreenIcon
        icon.animate().cancel()
        icon.animate().scaleX(0.3f).scaleY(0.3f).rotation(-120f)
            .setStartDelay(0L).setDuration(220L).setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                if (released) return@withEndAction
                icon.setImageResource(if (value) R.drawable.ic_wifi_on_24 else R.drawable.ic_wifi_off_24)
                icon.rotation = 120f
                icon.animate().scaleX(1f).scaleY(1f).rotation(0f)
                    .setStartDelay(0L).setDuration(520L).setInterpolator(OvershootInterpolator(2.2f))
                    .start()
            }.start()

        // 4. texts slide/fade out, swap, slide/fade back in – staggered
        swapText(b.offlineScreenKicker, 0.85f, 0L) {
            text = if (value) ctx.getString(R.string.offline_screen_back_kicker) else kickerForNow()
        }
        swapText(b.offlineScreenTitle, 1f, 60L) {
            setText(if (value) R.string.offline_screen_back_title else R.string.offline_screen_title)
        }
        swapText(b.offlineScreenLine, 0.8f, 120L) {
            text = if (value) ctx.getString(R.string.offline_screen_back_line) else lines[lineIndex]
        }
        swapText(b.offlineScreenHint, 0.5f, 180L) {
            setText(if (value) R.string.offline_screen_back_hint else R.string.offline_screen_hint)
        }
    }

    fun release() {
        released = true
        colorAnimator?.cancel()
        colorAnimator = null
        loops.forEach { it.cancel() }
        loops.clear()
        b.offlineScreenTitle.paint.shader = null
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Fades [v] out, swaps its content via [apply], then fades/slides it back in. */
    private fun swapText(v: TextView, targetAlpha: Float, delay: Long, apply: TextView.() -> Unit) {
        v.animate().cancel()
        v.animate().alpha(0f).translationY(-10f * density)
            .setStartDelay(delay).setDuration(200L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                if (released) return@withEndAction
                v.apply()
                v.translationY = 12f * density
                v.animate().alpha(targetAlpha).translationY(0f)
                    .setStartDelay(0L).setDuration(420L)
                    .setInterpolator(DecelerateInterpolator()).start()
            }.start()
    }

    private fun crossfade(v: View, offRes: Int, onRes: Int, toOnline: Boolean) {
        val from = ContextCompat.getDrawable(ctx, if (toOnline) offRes else onRes)?.mutate() ?: return
        val to = ContextCompat.getDrawable(ctx, if (toOnline) onRes else offRes)?.mutate() ?: return
        val td = TransitionDrawable(arrayOf(from, to))
        td.isCrossFadeEnabled = true
        v.background = td
        td.startTransition(800)
    }

    /** Pushes the current [accent] into every tinted element, including the title's shimmer. */
    private fun applyAccent() {
        b.offlineScreenKicker.setTextColor(accent)
        b.offlineScreenTitle.setTextColor(accent)
        ImageViewCompat.setImageTintList(b.offlineScreenIcon, ColorStateList.valueOf(accent))
        rebuildShader()
    }

    /**
     * The title is painted with a gradient shader, and a shader overrides the text colour. Because
     * of that, the shader has to be rebuilt (and the view invalidated) whenever the accent changes.
     * Before, the old orange shader kept being drawn after the text turned "green" until the next
     * shimmer frame, which is why "Back online" showed up orange for a second or two.
     */
    private fun rebuildShader() {
        val t = b.offlineScreenTitle
        val w = t.width.toFloat().coerceAtLeast(1f)
        val x = -w + 2f * w * sweep
        t.paint.shader = LinearGradient(
            x, 0f, x + w, 0f,
            intArrayOf(accent, accent, 0xFFFFFFFF.toInt(), accent, accent),
            floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        t.invalidate()
    }

    // ---- looping animation --------------------------------------------------------------------

    private fun startLoops() {
        // rings: 120dp -> 1.45x = 174dp, inside the 190dp box, fading out before the edge
        listOf(b.offlineScreenRingOne to 0L, b.offlineScreenRingTwo to 1000L).forEach { (ring, delay) ->
            ring.alpha = 0f
            loops += ObjectAnimator.ofPropertyValuesHolder(
                ring,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.45f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.45f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.6f, 0f)
            ).apply {
                duration = 2000L
                startDelay = 400L + delay
                repeatCount = ObjectAnimator.INFINITE
                interpolator = DecelerateInterpolator()
                start()
            }
        }
        loops += ObjectAnimator.ofPropertyValuesHolder(
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
        loops += ObjectAnimator.ofFloat(b.offlineScreenIcon, View.ALPHA, 1f, 0.65f).apply {
            duration = 1600L
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // glow: fades in on its own first, then starts its slow pulse
        b.offlineScreenGlow.alpha = 0f
        loops += ObjectAnimator.ofFloat(b.offlineScreenGlow, View.ALPHA, 0f, 1f).apply {
            duration = 800L
            startDelay = 300L
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled || released) return
                    loops += ObjectAnimator.ofFloat(b.offlineScreenGlow, View.ALPHA, 1f, 0.55f).apply {
                        duration = 2200L
                        repeatCount = ObjectAnimator.INFINITE
                        repeatMode = ObjectAnimator.REVERSE
                        interpolator = AccelerateDecelerateInterpolator()
                        start()
                    }
                }
            })
            start()
        }

        // light sweeping across the title
        b.offlineScreenTitle.post {
            if (released) return@post
            rebuildShader()
            loops += ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2600L
                repeatCount = ValueAnimator.INFINITE
                startDelay = 900L
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    sweep = it.animatedValue as Float
                    rebuildShader()
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

    /** Time-of-day lines mixed into the general pool; shuffled once per launch. */
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
}
