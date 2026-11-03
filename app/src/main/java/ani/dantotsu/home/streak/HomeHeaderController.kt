package ani.dantotsu.home.streak

import android.animation.ValueAnimator
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import ani.dantotsu.R
import ani.dantotsu.databinding.FragmentHomeBinding
import com.google.android.material.color.MaterialColors
import kotlin.random.Random

/**
 * Drives the redesigned Home header:
 *  - the big, softly shimmering username
 *  - the streak card (fire, count, funny line, last-7-days dots)
 *  - the fire on the Anime / Manga list buttons once the streak reaches
 *    [StreakManager.BUTTON_FIRE_STREAK]
 *
 * Everything here reads local data only, so it works the same in Pre-cached (offline) mode.
 */
class HomeHeaderController(private val b: FragmentHomeBinding) {

    private val ctx = b.root.context
    private val d = ctx.resources.displayMetrics.density

    private val primary = themeColor(androidx.appcompat.R.attr.colorPrimary)
    private val surface = themeColor(com.google.android.material.R.attr.colorSurface)
    private val onSurface = themeColor(com.google.android.material.R.attr.colorOnSurface)

    private var nameAnim: ValueAnimator? = null
    private var countAnim: ValueAnimator? = null
    private var shown = false
    private var lastCount = -1
    private var lastLineKey = ""
    private var tapCount = 0
    private var released = false

    private fun themeColor(attr: Int) = MaterialColors.getColor(b.root, attr)

    // ---- setup --------------------------------------------------------------------------------

    fun start() {
        // card look: translucent surface, theme-coloured hairline and a soft theme wash
        b.homeStreakCard.setCardBackgroundColor(ColorUtils.setAlphaComponent(surface, 205))
        b.homeStreakCard.strokeColor = ColorUtils.setAlphaComponent(primary, 120)
        b.homeStreakGlow.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(ColorUtils.setAlphaComponent(primary, 80), ColorUtils.setAlphaComponent(primary, 0))
        )

        b.homeStreakCard.setOnClickListener {
            tapCount++
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            b.homeStreakFire.flare()
            refresh(forceNewLine = true)
        }

        styleUsername()
        refresh()

        // entrance: the card rises into place
        b.homeStreakCard.apply {
            visibility = View.VISIBLE
            alpha = 0f
            translationY = 18f * d
            animate().alpha(1f).translationY(0f)
                .setStartDelay(150L).setDuration(520L)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    fun release() {
        released = true
        nameAnim?.cancel(); nameAnim = null
        countAnim?.cancel(); countAnim = null
        b.homeUserName.paint.shader = null
    }

    // ---- username -----------------------------------------------------------------------------

    /** Theme-coloured gradient with a slow light wave running through the name. Call after the text is set. */
    fun styleUsername() {
        val tv = b.homeUserName
        tv.post {
            if (released) return@post
            val text = tv.text?.toString().orEmpty()
            val w = tv.paint.measureText(text).coerceAtLeast(1f)
            val bright = ColorUtils.blendARGB(primary, 0xFFFFFFFF.toInt(), 0.75f)
            val shader = LinearGradient(
                0f, 0f, w, 0f, intArrayOf(primary, bright, primary), null, Shader.TileMode.MIRROR
            )
            tv.paint.shader = shader
            val m = Matrix()
            nameAnim?.cancel()
            // the pattern repeats every [w] pixels, so sliding by exactly [w] loops without a jump
            nameAnim = ValueAnimator.ofFloat(0f, w).apply {
                duration = 5200L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    m.setTranslate(it.animatedValue as Float, 0f)
                    shader.setLocalMatrix(m)
                    tv.invalidate()
                }
                start()
            }
        }
    }

    // ---- streak -------------------------------------------------------------------------------

    fun refresh(forceNewLine: Boolean = false) {
        if (released) return
        val snap = StreakManager.snapshot()

        // fire + count
        b.homeStreakFire.setLevel(StreakManager.fireLevel(snap.count))
        b.homeStreakFire.setDim(
            when (snap.status) {
                StreakManager.Status.ACTIVE -> 0f
                StreakManager.Status.AT_RISK, StreakManager.Status.GRACE -> 0.45f
                else -> 1f
            }
        )
        setCount(snap.count)

        // funny line (re-rolled when the situation changes, when tapped, or on a new day)
        val key = "${snap.status}-${band(snap.count)}-${StreakManager.today()}"
        if (forceNewLine || key != lastLineKey) {
            lastLineKey = key
            val line = pickLine(snap)
            if (!shown || forceNewLine) swapLine(line, animate = shown) else b.homeStreakLine.text = line
        }

        renderDots(snap.week)

        // fire on the list buttons from 30 days on
        val burning = snap.count >= StreakManager.BUTTON_FIRE_STREAK
        listOf(b.homeAnimeFire, b.homeMangaFire).forEach { f ->
            if (burning) {
                f.setMode(FireView.Mode.EDGE)
                f.setLevel(StreakManager.fireLevel(snap.count))
                f.setDim(if (snap.status == StreakManager.Status.ACTIVE) 0f else 0.35f)
                f.visibility = View.VISIBLE
            } else {
                f.visibility = View.GONE
            }
        }
        shown = true
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
        } else {
            tv.text = count.toString()
        }
        lastCount = count
    }

    private fun swapLine(line: String, animate: Boolean) {
        val tv = b.homeStreakLine
        if (!animate) { tv.text = line; return }
        tv.animate().cancel()
        tv.animate().alpha(0f).translationY(-6f * d).setDuration(150L).withEndAction {
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

    private fun renderDots(week: List<StreakManager.Dot>) {
        val box = b.homeStreakDots
        val size = (8 * d).toInt()
        val gap = (3 * d).toInt()
        if (box.childCount != week.size) {
            box.removeAllViews()
            repeat(week.size) {
                box.addView(View(ctx), LinearLayout.LayoutParams(size, size).apply {
                    marginStart = gap; marginEnd = gap
                })
            }
        }
        week.forEachIndexed { i, dot ->
            val isToday = i == week.lastIndex
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                when (dot) {
                    StreakManager.Dot.LIT -> setColor(primary)
                    StreakManager.Dot.GRACE -> {
                        setColor(ColorUtils.setAlphaComponent(primary, 70))
                        setStroke((1.5f * d).toInt(), primary)
                    }
                    StreakManager.Dot.EMPTY -> {
                        setColor(ColorUtils.setAlphaComponent(onSurface, 50))
                        if (isToday) setStroke((1.5f * d).toInt(), ColorUtils.setAlphaComponent(primary, 170))
                    }
                }
            }
            box.getChildAt(i).background = shape
        }
    }
}