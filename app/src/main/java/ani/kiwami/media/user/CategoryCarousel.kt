package ani.kiwami.media.user

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import ani.kiwami.R
import ani.kiwami.getThemeColor
import kotlin.math.abs
import kotlin.math.max

/**
 * Floating "wheel" of list categories. The selected category always rests in the middle, inside a
 * pill that morphs to fit its label; neighbours shrink and fade away from the centre, and the
 * carousel snaps (with a haptic tick) to whatever is closest to the middle when you let go.
 */
class CategoryCarousel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private val pill = View(context)
    private val recycler = RecyclerView(context)
    private val snap = LinearSnapHelper()
    private val layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)

    private val onPrimary = context.getThemeColor(com.google.android.material.R.attr.colorOnPrimary)
    private val outline = context.getThemeColor(com.google.android.material.R.attr.colorOutline)

    private var labels: List<String> = emptyList()
    private var widths = IntArray(0)
    private var selected = 0
    private var closest = -1
    private var pillAnimator: ValueAnimator? = null

    /** called when the user picks a different category */
    var onSelected: ((Int) -> Unit)? = null

    init {
        clipChildren = false
        background = GradientDrawable().apply {
            cornerRadius = 32 * density
            setColor(ContextCompat.getColor(context, R.color.grey_nav))
        }
        elevation = 10 * density

        pill.background = ContextCompat.getDrawable(context, R.drawable.bg_segment_thumb)
        addView(pill, LayoutParams(0, (44 * density).toInt(), Gravity.CENTER))

        recycler.layoutManager = layoutManager
        recycler.adapter = ItemAdapter()
        recycler.clipToPadding = false
        recycler.itemAnimator = null
        recycler.overScrollMode = OVER_SCROLL_NEVER
        // no fading edge: it blurred the first/last letters of long selected labels (e.g. "Favorites (x)").
        // Neighbours still fade away from the centre through the per-item alpha in applyEffect().
        recycler.isHorizontalFadingEdgeEnabled = false
        snap.attachToRecyclerView(recycler)
        addView(recycler, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = updateAll()

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val view = snap.findSnapView(layoutManager) ?: return
                val pos = rv.getChildAdapterPosition(view)
                if (pos >= 0 && pos != selected) {
                    selected = pos
                    commitSelection(pos)
                }
                // Once everything has really stopped (the snap helper may start one more settle),
                // nudge the selected label onto the exact middle using the views' real positions.
                rv.post { if (rv.scrollState == RecyclerView.SCROLL_STATE_IDLE) alignSelectedToCenter() }
            }
        })
        recycler.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) = applyEffect(view)
            override fun onChildViewDetachedFromWindow(view: View) {}
        })
        recycler.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateAll() }
    }

    // region public api

    fun setItems(newLabels: List<String>, selectedIndex: Int) {
        labels = newLabels
        widths = IntArray(newLabels.size) { measureLabel(newLabels[it]) }
        selected = selectedIndex.coerceIn(0, max(0, newLabels.size - 1))
        closest = selected
        recycler.adapter?.notifyDataSetChanged()
        post { centerNow() }
    }

    /** moves the carousel to [index] without reporting it back (used when the page was swiped) */
    fun select(index: Int) {
        if (index !in labels.indices || index == selected) return
        selected = index
        smoothCenter(index)
    }

    // endregion

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (labels.isNotEmpty()) post { centerNow() }
    }

    private fun centerNow() {
        if (labels.isEmpty() || width == 0) return
        // half-width padding on both sides lets every category (first and last included) reach the
        // exact middle, without depending on pre-measured label widths being pixel perfect
        val half = width / 2
        recycler.setPadding(half, 0, half, 0)
        layoutManager.scrollToPositionWithOffset(selected, (width - widths[selected]) / 2 - recycler.paddingLeft)
        setPillWidth(widths[selected])
        recycler.post {
            alignSelectedToCenter()
            updateAll()
        }
    }

    /**
     * Moves the selected label so that its real, laid-out centre sits exactly on the carousel's centre
     * and sizes the pill from the real view width. Measured widths are only a first guess.
     */
    private fun alignSelectedToCenter() {
        val child = layoutManager.findViewByPosition(selected) ?: return
        val dx = (child.left + child.right) / 2 - recycler.width / 2
        if (dx != 0) recycler.scrollBy(dx, 0)
        if (child.width > 0 && pill.layoutParams.width != child.width) setPillWidth(child.width)
    }

    private fun smoothCenter(index: Int) {
        val scroller = object : LinearSmoothScroller(context) {
            override fun calculateDxToMakeVisible(view: View, snapPreference: Int): Int =
                recycler.width / 2 - (view.left + view.right) / 2

            override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float =
                110f / displayMetrics.densityDpi
        }
        scroller.targetPosition = index
        layoutManager.startSmoothScroll(scroller)
    }

    private fun commitSelection(index: Int) {
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        pill.animate().cancel()
        pill.animate().scaleY(0.86f).setDuration(110).withEndAction {
            pill.animate().scaleY(1f).setDuration(520).setInterpolator(OvershootInterpolator(4f)).start()
        }.start()
        onSelected?.invoke(index)
    }

    // region visual effects

    private fun updateAll() {
        var best = -1
        var bestWidth = 0
        var bestDist = Float.MAX_VALUE
        val center = recycler.width / 2f
        for (i in 0 until recycler.childCount) {
            val child = recycler.getChildAt(i)
            applyEffect(child)
            val d = abs((child.left + child.right) / 2f - center)
            if (d < bestDist) {
                bestDist = d
                best = recycler.getChildAdapterPosition(child)
                bestWidth = child.width
            }
        }
        if (best >= 0 && best != closest && best < widths.size) {
            closest = best
            animatePill(if (bestWidth > 0) bestWidth else widths[best])
        }
    }

    private fun applyEffect(child: View) {
        val center = recycler.width / 2f
        val d = abs((child.left + child.right) / 2f - center)
        val f = (d / (width * 0.38f)).coerceIn(0f, 1f)
        val scale = 1.1f - 0.26f * f
        child.scaleX = scale
        child.scaleY = scale
        child.alpha = 1f - 0.55f * f
        val colorT = (d / (child.width * 0.5f + 14 * density)).coerceIn(0f, 1f)
        (child as? TextView)?.setTextColor(blend(onPrimary, outline, colorT))
    }

    private fun animatePill(target: Int) {
        val lp = pill.layoutParams
        val from = if (lp.width <= 0) target else lp.width
        pillAnimator?.cancel()
        pillAnimator = ValueAnimator.ofInt(from, target).apply {
            duration = 280
            interpolator = OvershootInterpolator(1.2f)
            addUpdateListener {
                lp.width = it.animatedValue as Int
                pill.layoutParams = lp
            }
            start()
        }
    }

    private fun setPillWidth(w: Int) {
        pillAnimator?.cancel()
        pill.layoutParams = pill.layoutParams.apply { width = w }
    }

    private fun blend(from: Int, to: Int, t: Float): Int = Color.rgb(
        (Color.red(from) * (1 - t) + Color.red(to) * t).toInt(),
        (Color.green(from) * (1 - t) + Color.green(to) * t).toInt(),
        (Color.blue(from) * (1 - t) + Color.blue(to) * t).toInt()
    )

    // endregion

    // region items

    private fun newItem(): TextView = TextView(context).apply {
        typeface = ResourcesCompat.getFont(context, R.font.poppins_bold)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        val pad = (18 * density).toInt()
        setPadding(pad, 0, pad, 0)
        minWidth = (88 * density).toInt()
        maxWidth = (230 * density).toInt()
        layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private fun measureLabel(text: String): Int {
        val tv = newItem()
        tv.text = text
        tv.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec((56 * density).toInt(), MeasureSpec.EXACTLY)
        )
        return tv.measuredWidth
    }

    private inner class Holder(val tv: TextView) : RecyclerView.ViewHolder(tv)

    private inner class ItemAdapter : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(newItem())

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.tv.text = labels[position]
            holder.tv.setOnClickListener {
                val p = holder.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION && p != selected) {
                    selected = p
                    smoothCenter(p)
                    commitSelection(p)
                }
            }
            applyEffect(holder.tv)
        }

        override fun getItemCount() = labels.size
    }

    // endregion
}
