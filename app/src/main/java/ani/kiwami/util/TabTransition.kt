package ani.kiwami.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.PathInterpolator
import androidx.viewpager2.widget.ViewPager2
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Depth-parallax tab transition. It is driven by the scroll position on every frame, so it is one
 * continuous motion (nothing restarts or flashes when a page settles):
 *  - both pages drift towards the middle at half speed, so they glide over each other
 *  - the page that is leaving shrinks back a little and fades out, the new one grows in and fades up
 *  - the fade is eased (squared), so the content never dips noticeably dark halfway through
 */
class TabPageTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        val p = position.coerceIn(-1f, 1f)
        val a = abs(p)
        if (a >= 1f) {
            // Off-screen pages must go back to their real position. Left shifted by the parallax they
            // sit over the screen invisibly and swallow every touch on that half.
            page.alpha = 0f
            page.translationX = 0f
            page.scaleX = 1f
            page.scaleY = 1f
            return
        }
        val scale = 1f - 0.07f * a
        page.alpha = 1f - a * a
        page.scaleX = scale
        page.scaleY = scale
        page.translationX = -p * page.width * 0.5f
    }
}

private val running = WeakHashMap<ViewPager2, ValueAnimator>()
private val wanted = WeakHashMap<ViewPager2, Int>()
private val guarded = WeakHashMap<ViewPager2, Boolean>()

/** Whatever happens while switching quickly, the pager always ends on the tab that was asked for last. */
private fun ViewPager2.ensureGuard() {
    if (guarded.put(this, true) != null) return
    val pager = this
    registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrollStateChanged(state: Int) {
            if (state != ViewPager2.SCROLL_STATE_IDLE) return
            if (running[pager]?.isRunning == true || pager.isFakeDragging) return
            val w = wanted[pager] ?: return
            if (pager.currentItem != w) pager.setCurrentItem(w, false)
        }
    })
}

/**
 * Switches to [item] with a longer, softer glide than ViewPager2's built-in smooth scroll
 * (fast start, long gentle landing). If a switch is still in flight the glide is stopped cleanly
 * and the built-in smooth scroll takes over, so quick taps never leave the pager on a wrong tab.
 */
fun ViewPager2.switchTabSmoothly(item: Int, duration: Long = 480L) {
    wanted[this] = item
    ensureGuard()
    if (width == 0) {
        setCurrentItem(item, false)
        return
    }
    val busy = isFakeDragging || running[this]?.isRunning == true ||
        scrollState != ViewPager2.SCROLL_STATE_IDLE
    if (busy) {
        // stop the running glide (its end handler finishes the fake drag), then re-aim.
        // setCurrentItem throws while fake dragging.
        running[this]?.cancel()
        if (isFakeDragging) endFakeDrag()
        setCurrentItem(item, true)
        return
    }
    val diff = item - currentItem
    if (diff == 0) return
    val total = width * diff
    var last = 0
    val animator = ValueAnimator.ofInt(0, total).apply {
        this.duration = duration
        interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f) // easeOutQuint-ish
        addUpdateListener {
            val v = it.animatedValue as Int
            fakeDragBy(-(v - last).toFloat())
            last = v
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                beginFakeDrag()
            }

            override fun onAnimationEnd(animation: Animator) {
                if (isFakeDragging) endFakeDrag()
            }
        })
    }
    running[this] = animator
    animator.start()
}
