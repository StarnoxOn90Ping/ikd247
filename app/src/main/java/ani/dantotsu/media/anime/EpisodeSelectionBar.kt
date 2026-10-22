package ani.dantotsu.media.anime

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnticipateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import ani.dantotsu.R
import ani.dantotsu.navBarHeight
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

/**
 * Floating action bar shown at the bottom of the episode list while one or more
 * episodes are selected (long-press an episode to start selecting).
 */
class EpisodeSelectionBar(
    private val context: Context,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onInfo: () -> Unit,
    onWatched: () -> Unit,
    onUnwatched: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    watchedLabel: String = "Watched",
    unwatchedLabel: String = "Unwatched"
) {
    private val card = MaterialCardView(context)
    private val countText = TextView(context)
    private var shown = false

    val view: View get() = card

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    init {
        val surface = MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorSurface, Color.DKGRAY
        )
        val onSurface = MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorOnSurface, Color.WHITE
        )
        val primary = MaterialColors.getColor(
            context, androidx.appcompat.R.attr.colorPrimary, Color.CYAN
        )
        val boldFont: Typeface? = try {
            ResourcesCompat.getFont(context, R.font.poppins_bold)
        } catch (e: Exception) {
            null
        }
        val ripple = TypedValue()
        context.theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless, ripple, true
        )

        card.apply {
            // Flat, full-width bar in the same surface colour as the Info/Watch/Comments bar
            radius = 0f
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(surface)
            isClickable = true // swallow touches so they don't reach the list behind the bar
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply { setMargins(0, 0, 0, 0) }
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(6))
        }

        // Header: close button, "N selected", select all
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val close = ImageView(context).apply {
            setImageResource(R.drawable.ic_round_close_24)
            setColorFilter(onSurface)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            if (ripple.resourceId != 0) setBackgroundResource(ripple.resourceId)
            setOnClickListener { onClose() }
        }
        countText.apply {
            textSize = 15f
            setTextColor(onSurface)
            if (boldFont != null) typeface = boldFont
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val selectAll = TextView(context).apply {
            text = "Select all"
            textSize = 14f
            setTextColor(primary)
            if (boldFont != null) typeface = boldFont
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { onSelectAll() }
        }
        header.addView(close)
        header.addView(countText)
        header.addView(selectAll)

        // Action buttons
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun action(icon: Int, label: String, click: () -> Unit): View =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(2), dp(6), dp(2), dp(6))
                isClickable = true
                isFocusable = true
                if (ripple.resourceId != 0) setBackgroundResource(ripple.resourceId)
                setOnClickListener { click() }
                addView(ImageView(context).apply {
                    setImageResource(icon)
                    setColorFilter(onSurface)
                    layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
                })
                addView(TextView(context).apply {
                    text = label
                    textSize = 11f
                    maxLines = 1
                    gravity = Gravity.CENTER
                    setTextColor(onSurface)
                    setPadding(0, dp(2), 0, 0)
                })
            }
        actions.addView(action(R.drawable.ic_round_info_24, "Info") { onInfo() })
        actions.addView(action(R.drawable.ic_check, watchedLabel) { onWatched() })
        actions.addView(action(R.drawable.ic_round_remove_red_eye_24, unwatchedLabel) { onUnwatched() })
        actions.addView(action(R.drawable.ic_download_24, "Download") { onDownload() })
        actions.addView(action(R.drawable.ic_round_delete_24, "Delete") { onDelete() })

        column.addView(header)
        column.addView(actions)
        // Thin divider on top, like a separator between the list and the bar
        val divider = View(context).apply {
            setBackgroundColor(androidx.core.graphics.ColorUtils.setAlphaComponent(onSurface, 40))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            )
        }
        val wrapper = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(divider)
        wrapper.addView(column)
        card.addView(wrapper)
    }

    /** Height the bar takes up, so the list can leave room for it. */
    fun measuredHeightPx(): Int {
        val w = View.MeasureSpec.makeMeasureSpec(
            context.resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY
        )
        val h = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        card.measure(w, h)
        return card.measuredHeight
    }

    /** Called with the current number of selected episodes. */
    fun update(count: Int) {
        if (count > 0) {
            countText.text = "$count selected"
            show()
        } else {
            hide()
        }
    }

    private fun show() {
        if (shown) return
        shown = true
        (card.layoutParams as? FrameLayout.LayoutParams)?.let {
            // sit directly on top of the Info / Watch / Comments tab bar (64dp + system nav bar)
            it.bottomMargin = dp(64) + navBarHeight
            card.layoutParams = it
        }
        card.animate().cancel()
        card.visibility = View.VISIBLE
        card.alpha = 0f
        card.translationY = dp(56).toFloat()
        // Springy pop-up: overshoots a little, then settles
        card.animate().alpha(1f).translationY(0f)
            .setDuration(450)
            .setInterpolator(OvershootInterpolator(2.2f))
            .start()
    }

    private fun hide() {
        if (!shown) return
        shown = false
        card.animate().cancel()
        // Pulls back slightly before dropping away
        card.animate().alpha(0f).translationY(dp(56).toFloat())
            .setDuration(260)
            .setInterpolator(AnticipateInterpolator(1.6f))
            .withEndAction { if (!shown) card.visibility = View.GONE }
            .start()
    }
}

/**
 * Shared look for a selected episode / chapter card: a tinted outline, plus a bouncy
 * "zoom out" so it visibly sinks in. Pass animate = false when just (re)binding a row.
 */
fun applySelectionStyle(card: FrameLayout, selected: Boolean, animate: Boolean) {
    val density = card.resources.displayMetrics.density
    card.foreground = if (selected) {
        val primary = MaterialColors.getColor(card, androidx.appcompat.R.attr.colorPrimary)
        android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 16f * density
            setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(primary, 70))
            setStroke((3 * density).toInt(), primary)
        }
    } else {
        null
    }
    val target = if (selected) 0.93f else 1f
    card.animate().cancel()
    if (animate) {
        card.animate()
            .scaleX(target)
            .scaleY(target)
            .setDuration(450)
            .setInterpolator(OvershootInterpolator(3.2f))
            .start()
    } else {
        card.scaleX = target
        card.scaleY = target
    }
}
