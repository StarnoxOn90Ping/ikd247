package nox.kiwami.home.continuebar

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.LifecycleOwner
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
import com.google.android.material.color.MaterialColors
import nox.kiwami.R
import nox.kiwami.connections.anilist.AnilistHomeViewModel
import nox.kiwami.databinding.FragmentHomeBinding
import nox.kiwami.media.Media
import nox.kiwami.media.MediaDetailsActivity
import nox.kiwami.others.CustomMediaImages
import nox.kiwami.setSlideIn
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import java.io.Serializable

class HomeSlotController(
    private val binding: FragmentHomeBinding,
    private val model: AnilistHomeViewModel
) {
    private val slot = binding.homeSlot
    private var shown: Media? = null
    private var boundKey: String? = null
    private var skeletonShown = false

    private val activeDot by lazy {
        MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary)
    }
    private val idleDot by lazy {
        ColorUtils.setAlphaComponent(
            MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface),
            90
        )
    }

    private val pressDistance by lazy { 2f * binding.root.resources.displayMetrics.density }

    @SuppressLint("ClickableViewAccessibility")
    fun start(owner: LifecycleOwner) {
        binding.homeSlotDots.setColors(activeDot, idleDot)
        binding.homeContinueBar.strokeColor = ColorUtils.setAlphaComponent(Color.WHITE, 40)
        binding.homeContinuePlayHalo.backgroundTintList =
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(activeDot, 77))
        binding.homeContinueBar.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> press(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> press(false)
            }
            false
        }
        slot.bind(binding.homeSlotContinue, binding.homeSlotSocial, binding.homeSlotDots)
        slot.onPageChanged = { page ->
            PrefManager.setVal(PrefName.HomeSlotPage, page)
        }
        slot.onDrag = { page, dir, progress -> binding.homeSlotDots.drag(page, dir, progress) }
        slot.onRelease = { page, dir, commit -> binding.homeSlotDots.release(page, dir, commit) }
        slot.onSettled = { page -> binding.homeSlotDots.snapTo(page) }
        binding.homeSlotDots.setOnClickListener { slot.toggle() }
        binding.homeContinueBar.setOnClickListener { open() }
        model.getAnimeContinue().observe(owner) { refresh() }
        model.getMangaContinue().observe(owner) { refresh() }
        model.homeSettled.observe(owner) { refresh() }
        refresh()
    }

    fun refresh() {
        val layout = PrefManager.getVal<List<Boolean>>(PrefName.HomeLayout)
        val barOn = layout.getOrElse(CONTINUE_INDEX) { true }
        val socialOn = layout.getOrElse(SOCIAL_INDEX) { true }
        val media = if (barOn) {
            ContinueTracker.pick(model.getAnimeContinue().value, model.getMangaContinue().value)
        } else {
            null
        }
        val socialLoading = socialOn && model.getUserStatus().value == null
        val hasSocial = socialOn &&
            (socialLoading || binding.homeUserStatusContainer.visibility != View.GONE)
        val barLoading = barOn && media == null &&
            model.homeSettled.value != true &&
            model.getAnimeContinue().value == null &&
            model.getMangaContinue().value == null
        shown = media
        if (media != null) bind(media)
        val reveal = media != null && skeletonShown
        if (barLoading) skeletonShown = true else if (media != null) skeletonShown = false
        binding.homeContinueBar.visibility = if (media != null) View.VISIBLE else View.GONE
        if (reveal) binding.homeContinueBar.startAnimation(setSlideIn())
        binding.homeContinueSkeleton.visibility = if (barLoading) View.VISIBLE else View.GONE
        slot.configure(media != null || barLoading, hasSocial, PrefManager.getVal(PrefName.HomeSlotPage))
        binding.homeSlotDots.snapTo(slot.page)
    }

    private fun press(down: Boolean) {
        val bar = if (down) PRESS_BAR_SCALE else 1f
        val core = if (down) PRESS_CORE_SCALE else 1f
        binding.homeContinueBar.animate().scaleX(bar).scaleY(bar).setDuration(PRESS_MS).start()
        binding.homeContinuePlayCore.animate().scaleX(core).scaleY(core).setDuration(PRESS_MS).start()
        binding.homeContinuePlayIcon.animate()
            .translationX(if (down) pressDistance else 0f)
            .setDuration(PRESS_MS)
            .start()
    }

    private fun loadBanner(url: String) {
        val view = binding.homeContinueBarImage
        val source: Any = CustomMediaImages.overrideFor(url)
            ?: GlideUrl(PrefManager.getVal<String>(PrefName.ImageUrl).ifEmpty { url })
        Glide.with(view)
            .load(source)
            .override(BANNER_WIDTH, BANNER_HEIGHT)
            .transition(withCrossFade())
            .into(view)
    }

    private fun bind(media: Media) {
        val number = ContinueTracker.nextNumber(media)
        val isAnime = media.anime != null
        val key = "${media.id}:$isAnime:$number"
        if (key == boundKey) return
        boundKey = key
        val context = binding.root.context
        (media.banner ?: media.cover)?.let { loadBanner(it) }
        binding.homeContinueBarTitle.text = media.mainName()
        binding.homeContinueBarLabel.text = context.getString(
            if (isAnime) R.string.home_continue_episode else R.string.home_continue_chapter,
            number
        )
    }

    private fun open() {
        val media = shown ?: return
        media.cameFromContinue = true
        val context = binding.root.context
        context.startActivity(
            Intent(context, MediaDetailsActivity::class.java)
                .putExtra("media", media as Serializable)
                .putExtra("forceContinue", true)
        )
    }

    private companion object {
        const val SOCIAL_INDEX = 7
        const val CONTINUE_INDEX = 9
        const val BANNER_WIDTH = 640
        const val BANNER_HEIGHT = 190
        const val PRESS_MS = 120L
        const val PRESS_BAR_SCALE = 0.97f
        const val PRESS_CORE_SCALE = 0.9f
    }
}
