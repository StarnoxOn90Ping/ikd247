package ani.dantotsu.home.streak

import android.animation.ValueAnimator
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import ani.dantotsu.R
import ani.dantotsu.databinding.FragmentHomeBinding
import com.google.android.material.color.MaterialColors
import kotlin.random.Random

/**
 * Drives the streak shown in the left of the Home header:
 *
 *   [big fire]  [number on fire]  [short funny line]            [search] [pfp]
 *
 * It also sets the fire burning along the bottom of the Anime / Manga list buttons once the
 * streak reaches [StreakManager.BUTTON_FIRE_STREAK]. That fire is added to the cards from code,
 * so the list buttons' own layout stays exactly as it was.
 *
 * Everything here reads local data only, so it works the same in Pre-cached (offline) mode.
 */
class HomeHeaderController(private val b: FragmentHomeBinding) {

    private val ctx = b.root.context
    private val d = ctx.resources.displayMetrics.density
    private val onSurface = MaterialColors.getColor(
        b.root, com.google.android.material.R.attr.colorOnSurface
    )

    private var waveAnim: ValueAnimator? = null
    private var glowAnim: ValueAnimator? = null
    private var countAnim: ValueAnimator? = null
    private var styledStatus: StreakManager.Status? = null
    private var shown = false
    private var lastCount = -1
    private var lastLineKey = ""
    private var tapCount = 0
    private var released = false

    private var animeFire: FireView? = null
    private var mangaFire: FireView? = null

    // ---- setup --------------------------------------------------------------------------------

    fun start() {
        b.homeUserDataProgressBar.visibility = View.GONE
        b.homeUserDataContainer.visibility = View.VISIBLE
        b.homeUserDataContainer.setOnClickListener {
            tapCount++
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            b.homeStreakFire.flare()
            b.homeStreakNumFire.flare()
            refresh(forceNewLine = true)
        }
        refresh()

        // entrance: the three parts fade up one after another. (Alpha is animated on the leaf
        // views only, never on a parent, so nothing gets clipped by an off-screen layer.)
        listOf(b.homeStreakFire, b.homeStreakNumberBox, b.homeStreakLine).forEachIndexed { i, v ->
            val target = v.alpha
            v.alpha = 0f
            v.translationY = 16f * d
            v.animate().alpha(target).translationY(0f)
                .setStartDelay(120L + i * 120L).setDuration(520L)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    fun release() {
        released = true
        waveAnim?.cancel(); waveAnim = null
        glowAnim?.cancel(); glowAnim = null
        countAnim?.cancel(); countAnim = null
        b.homeStreakCount.paint.shader = null
    }

    // ---- refresh ------------------------------------------------------------------------------

    fun refresh(forceNewLine: Boolean = false) {
        if (released) return
        val snap = StreakManager.snapshot()
        val level = StreakManager.fireLevel(snap.count)
        val dim = when (snap.status) {
            StreakManager.Status.ACTIVE -> 0f
            StreakManager.Status.AT_RISK, StreakManager.Status.GRACE -> 0.45f
            else -> 1f
        }

        // big fire on the left
        b.homeStreakFire.setLevel(level)
        b.homeStreakFire.setDim(dim)

        // flames licking up behind the number (only while there is a streak)
        val numFire = b.homeStreakNumFire
        if (snap.count > 0) {
            numFire.setMode(FireView.Mode.EDGE)
            numFire.setLevel(level * 0.8f)
            numFire.setDim(dim)
            numFire.visibility = View.VISIBLE
        } else {
            numFire.visibility = View.GONE
        }

        styleNumber(snap.status)
        setCount(snap.count)

        // funny line (re-rolled when the situation changes, when tapped, or on a new day)
        val key = "${snap.status}-${band(snap.count)}-${StreakManager.today()}"
        if (forceNewLine || key != lastLineKey) {
            lastLineKey = key
            val line = pickLine(snap)
            if (shown && forceNewLine) swapLine(line) else b.homeStreakLine.text = line
        }

        updateButtonFire(snap)
        shown = true
    }

    // ---- the number ---------------------------------------------------------------------------

    /**
     * Fire-coloured gradient that slowly rises through the digits, plus a pulsing orange glow.
     * Muted when the streak is waiting for today's episode, grey when there is no streak.
     */
    private fun styleNumber(status: StreakManager.Status) {
        if (status == styledStatus) return
        styledStatus = status

        val tv = b.homeStreakCount
        waveAnim?.cancel(); glowAnim?.cancel()

        val fm = tv.paint.fontMetrics
        val h = (fm.descent - fm.ascent).coerceAtLeast(1f)

        val fire = intArrayOf(0xFFFF5722.toInt(), 0xFFFFA000.toInt(), 0xFFFFE082.toInt(), 0xFFFFA000.toInt(), 0xFFFF5722.toInt())
        val grey = ColorUtils.setAlphaComponent(onSurface, 130)

        val burning = status == StreakManager.Status.ACTIVE
        val muted = status == StreakManager.Status.AT_RISK || status == StreakManager.Status.GRACE

        val colors = when {
            burning -> fire
            muted -> fire.map { ColorUtils.blendARGB(it, 0xFF808080.toInt(), 0.5f) }.toIntArray()
            else -> intArrayOf(grey, grey)
        }
        val shader = LinearGradient(0f, 0f, 0f, h, colors, null, Shader.TileMode.MIRROR)
        tv.paint.shader = shader

        if (burning || muted) {
            val m = Matrix()
            // the colour pattern repeats every [h] pixels, so sliding by [h] loops seamlessly
            waveAnim = ValueAnimator.ofFloat(0f, h).apply {
                duration = if (burning) 1600L else 3400L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    m.setTranslate(0f, -(it.animatedValue as Float))
                    shader.setLocalMatrix(m)
                    tv.invalidate()
                }
                start()
            }
            val glow = if (burning) 0xFFFF6D00.toInt() else 0x99B5651D.toInt()
            val maxR = (if (burning) 16f else 7f) * d
            glowAnim = ValueAnimator.ofFloat(4f * d, maxR).apply {
                duration = 900L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    tv.setShadowLayer(it.animatedValue as Float, 0f, 0f, glow)
                }
                start()
            }
        } else {
            tv.setShadowLayer(0f, 0f, 0f, 0)
            tv.invalidate()
        }
    }

    private fun setCount(count: Int) {
        countAnim?.cancel()
        val tv = b.homeStreakCount
        if (lastCount in 0 until count) {
            // the streak just grew: count up and flare
            countAnim = ValueAnimator.ofInt(lastCount, count).apply {
                duration = 800L
                interpolator = DecelerateInterpolator()
                addUpdateListener { tv.text = (it.animatedValue as Int).toString() }
                start()
            }
            b.homeStreakFire.flare(1800L)
            b.homeStreakNumFire.flare(1800L)
        } else {
            tv.text = count.toString()
        }
        lastCount = count
    }

    // ---- the line -----------------------------------------------------------------------------

    private fun swapLine(line: String) {
        val tv = b.homeStreakLine
        tv.animate().cancel()
        tv.animate().alpha(0f).translationY(-6f * d).setStartDelay(0L).setDuration(150L).withEndAction {
            if (released) return@withEndAction
            tv.text = line
            tv.translationY = 8f * d
            tv.animate().alpha(0.85f).translationY(0f).setDuration(320L)
                .setInterpolator(DecelerateInterpolator()).start()
        }.start()
    }

    private fun band(count: Int) = when {
        count <= 0 -> 0
        count == 1 -> 1
        count < 7 -> 2
        count < 14 -> 3
        count < 30 -> 4
        count < 60 -> 5
        else -> 6
    }

    private fun pickLine(s: StreakManager.Snapshot): String {
        val res = when (s.status) {
            StreakManager.Status.NONE -> R.array.streak_lines_none
            StreakManager.Status.LOST -> R.array.streak_lines_lost
            StreakManager.Status.AT_RISK -> R.array.streak_lines_at_risk
            StreakManager.Status.GRACE -> R.array.streak_lines_grace
            StreakManager.Status.ACTIVE -> when (band(s.count)) {
                1 -> R.array.streak_lines_day1
                2 -> R.array.streak_lines_early
                3 -> R.array.streak_lines_week
                4 -> R.array.streak_lines_fortnight
                5 -> R.array.streak_lines_month
                else -> R.array.streak_lines_legend
            }
        }
        val lines = ctx.resources.getStringArray(res)
        // stable for a given day/state, different after a tap
        val seed = StreakManager.today() * 31 + s.count * 7 + s.status.ordinal + tapCount * 13L
        return lines[Random(seed).nextInt(lines.size)]
    }

    // ---- fire on the Anime / Manga list buttons ----------------------------------------------

    private fun updateButtonFire(snap: StreakManager.Snapshot) {
        val burning = snap.count >= StreakManager.BUTTON_FIRE_STREAK
        if (burning) {
            animeFire = animeFire ?: addFire(b.homeAnimeList)
            mangaFire = mangaFire ?: addFire(b.homeMangaList)
        }
        listOfNotNull(animeFire, mangaFire).forEach { f ->
            if (burning) {
                f.setLevel(StreakManager.fireLevel(snap.count))
                f.setDim(if (snap.status == StreakManager.Status.ACTIVE) 0f else 0.35f)
                f.visibility = View.VISIBLE
            } else {
                f.visibility = View.GONE
            }
        }
    }

    /** Adds a fire strip to a list card, between its dark scrim and its label (index 2). */
    private fun addFire(card: ViewGroup): FireView {
        val fire = FireView(ctx).apply { setMode(FireView.Mode.EDGE) }
        card.addView(
            fire,
            minOf(2, card.childCount),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (44 * d).toInt(),
                Gravity.BOTTOM
            )
        )
        return fire
    }
}
