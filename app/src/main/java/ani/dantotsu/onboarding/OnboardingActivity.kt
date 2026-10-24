package ani.dantotsu.onboarding

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import android.content.res.ColorStateList
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import ani.dantotsu.MainActivity
import ani.dantotsu.client
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityOnboardingBinding
import ani.dantotsu.databinding.ItemOnboardingPageBinding
import ani.dantotsu.hasNotificationPermission
import ani.dantotsu.notifications.airing.AiringAlarmScheduler
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.LauncherWrapper
import ani.dantotsu.util.StoragePermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlin.math.abs

/**
 * First launch experience: a paged, animated tour that also asks for everything the app needs
 * to work properly in the background (notifications, battery optimisation exemption, exact
 * alarms and a download folder).
 */
class OnboardingActivity : AppCompatActivity() {

    private enum class Kind { INFO, NOTIFICATIONS, BATTERY, ALARMS, STORAGE, DONE }

    private data class Page(
        val kind: Kind,
        @DrawableRes val icon: Int,
        @StringRes val eyebrow: Int,
        @StringRes val title: Int,
        @StringRes val description: Int,
        val colorTop: Int,
        val colorBottom: Int,
        val tintIcon: Boolean = true,
        val features: List<Int> = emptyList()
    )

    private val pages = listOf(
        Page(
            Kind.INFO, R.drawable.ic_dantotsu_round, R.string.onboarding_eyebrow_welcome,
            R.string.onboarding_welcome_title, R.string.onboarding_welcome_desc,
            0xFF141E55.toInt(), 0xFF6A3FD1.toInt(), tintIcon = false,
            features = listOf(
                R.string.onboarding_feature_anime,
                R.string.onboarding_feature_manga,
                R.string.onboarding_feature_anilist
            )
        ),
        Page(
            Kind.INFO, R.drawable.ic_round_movie_filter_24, R.string.onboarding_eyebrow_discover,
            R.string.onboarding_discover_title, R.string.onboarding_discover_desc,
            0xFF0F3C5F.toInt(), 0xFF1F8FA8.toInt(),
            features = listOf(
                R.string.onboarding_feature_stream,
                R.string.onboarding_feature_read,
                R.string.onboarding_feature_offline
            )
        ),
        Page(
            Kind.INFO, R.drawable.ic_round_notifications_active_24, R.string.onboarding_eyebrow_alerts,
            R.string.onboarding_alerts_title, R.string.onboarding_alerts_desc,
            0xFF4A1259.toInt(), 0xFFD1457B.toInt(),
            features = listOf(
                R.string.onboarding_feature_exact,
                R.string.onboarding_feature_closed
            )
        ),
        Page(
            Kind.NOTIFICATIONS, R.drawable.ic_round_notifications_active_24,
            R.string.onboarding_eyebrow_notifications,
            R.string.onboarding_notifications_title, R.string.onboarding_notifications_desc,
            0xFF3B1E78.toInt(), 0xFF8E54E9.toInt()
        ),
        Page(
            Kind.BATTERY, R.drawable.ic_onboarding_battery, R.string.onboarding_eyebrow_battery,
            R.string.onboarding_battery_title, R.string.onboarding_battery_desc,
            0xFF14532D.toInt(), 0xFF2BAE66.toInt()
        ),
        Page(
            Kind.ALARMS, R.drawable.ic_onboarding_alarm, R.string.onboarding_eyebrow_alarms,
            R.string.onboarding_alarm_title, R.string.onboarding_alarm_desc,
            0xFF7C2D12.toInt(), 0xFFE8903A.toInt()
        ),
        Page(
            Kind.STORAGE, R.drawable.ic_round_folder_24, R.string.onboarding_eyebrow_storage,
            R.string.onboarding_storage_title, R.string.onboarding_storage_desc,
            0xFF1E3A8A.toInt(), 0xFF3B82F6.toInt()
        ),
        Page(
            Kind.DONE, R.drawable.ic_onboarding_sparkle, R.string.onboarding_eyebrow_done,
            R.string.onboarding_done_title, R.string.onboarding_done_desc,
            0xFF2D1B69.toInt(), 0xFFB04FD6.toInt()
        )
    )

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var folderLauncher: LauncherWrapper
    private val pageAdapter = PageAdapter()
    private val dots = mutableListOf<View>()
    private val background = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0xFF141E55.toInt(), 0xFF6A3FD1.toInt())
    )
    private val argb = ArgbEvaluator()

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // After two refusals Android silently ignores the request, so fall back to settings.
            if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                openNotificationSettings()
            }
            refreshStatuses()
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowInsetsControllerCompat(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        folderLauncher = LauncherWrapper(this, ActivityResultContracts.OpenDocumentTree())

        binding.scrim.background = background
        loadPosters()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.skipButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = bars.top + dp(8)
            }
            binding.bottomBar.updatePadding(bottom = bars.bottom + dp(20))
            insets
        }

        setupPager()
        setupDots()
        setupButtons()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val pager = binding.onboardingPager
                if (pager.currentItem > 0) {
                    pager.currentItem = pager.currentItem - 1
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        binding.tileWall.start()
    }

    override fun onResume() {
        super.onResume()
        // The user may just have come back from a system settings screen
        refreshStatuses()
    }

    override fun onStop() {
        super.onStop()
        binding.tileWall.stop()
    }

    // region setup

    private fun setupPager() {
        val pager = binding.onboardingPager
        pager.adapter = pageAdapter
        pager.offscreenPageLimit = 1

        // Parallax: icon slides slower than text, everything fades out towards the edges
        pager.setPageTransformer { page, position ->
            val width = page.width.toFloat()
            page.findViewById<View>(R.id.iconContainer)?.translationX = -position * width * 0.35f
            page.findViewById<View>(R.id.pageTitle)?.translationX = position * width * 0.15f
            page.findViewById<View>(R.id.pageDescription)?.translationX = position * width * 0.25f
            page.alpha = 1f - (abs(position) * 0.85f).coerceIn(0f, 1f)
        }

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(
                position: Int, positionOffset: Float, positionOffsetPixels: Int
            ) {
                val from = pages[position]
                val to = pages[(position + 1).coerceAtMost(pages.lastIndex)]
                background.setColors(
                    intArrayOf(
                        argb.evaluate(positionOffset, from.colorTop, to.colorTop) as Int,
                        argb.evaluate(positionOffset, from.colorBottom, to.colorBottom) as Int
                    )
                )
                updateDots(position + positionOffset)
                binding.tileWall.setParallax(-(position + positionOffset) * dp(36))
            }

            override fun onPageSelected(position: Int) {
                updateButtons(position)
                playEntrance(position)
            }
        })

        // initial state
        background.setColors(intArrayOf(pages[0].colorTop, pages[0].colorBottom))
        updateButtons(0)
        pager.post { playEntrance(0) }
    }

    private fun setupDots() {
        val container = binding.dotsContainer
        pages.indices.forEach { _ ->
            val dot = View(this).apply {
                setBackgroundResource(R.drawable.bg_onboarding_dot)
                layoutParams = android.widget.LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                    marginEnd = dp(8)
                }
            }
            container.addView(dot)
            dots.add(dot)
        }
        updateDots(0f)
    }

    private fun updateDots(position: Float) {
        dots.forEachIndexed { index, dot ->
            val fraction = (1f - abs(position - index)).coerceIn(0f, 1f)
            dot.alpha = 0.4f + 0.6f * fraction
            val width = dp(8) + (dp(20) * fraction).toInt()
            if (dot.layoutParams.width != width) {
                dot.layoutParams = dot.layoutParams.apply { this.width = width }
            }
        }
    }

    private fun setupButtons() {
        binding.nextButton.setOnClickListener {
            val pager = binding.onboardingPager
            if (pager.currentItem >= pages.lastIndex) finishOnboarding()
            else pager.setCurrentItem(pager.currentItem + 1, true)
        }
        binding.skipButton.setOnClickListener {
            binding.onboardingPager.setCurrentItem(pages.lastIndex, true)
        }
    }

    private fun updateButtons(position: Int) {
        val page = pages[position]
        val last = position == pages.lastIndex
        binding.skipButton.animate().alpha(if (last) 0f else 1f).setDuration(200).start()
        binding.skipButton.isEnabled = !last
        binding.nextButton.setText(
            when {
                last -> R.string.onboarding_get_started
                isActionable(page.kind) && !isGranted(page.kind) -> R.string.onboarding_maybe_later
                else -> R.string.onboarding_next
            }
        )
    }

    /** Pulls currently trending posters from AniList for the background; silent if offline. */
    private fun loadPosters() {
        lifecycleScope.launch(Dispatchers.IO) {
            val urls = try {
                val query = "{Page(page:1,perPage:30){media(type:ANIME,sort:TRENDING_DESC," +
                        "isAdult:false){coverImage{large}}}}"
                val response = client.post(
                    "https://graphql.anilist.co/",
                    mapOf(
                        "Content-Type" to "application/json; charset=utf-8",
                        "Accept" to "application/json"
                    ),
                    requestBody = JSONObject().put("query", query).toString()
                        .toRequestBody("application/json; charset=utf-8".toMediaType()),
                    cacheTime = 0
                )
                val media = JSONObject(response.text).getJSONObject("data")
                    .getJSONObject("Page").getJSONArray("media")
                (0 until media.length()).mapNotNull {
                    media.getJSONObject(it).optJSONObject("coverImage")?.optString("large")
                        ?.takeIf { url -> url.isNotEmpty() }
                }
            } catch (e: Exception) {
                emptyList()
            }
            if (urls.isNotEmpty()) withContext(Dispatchers.Main) { binding.tileWall.setImages(urls) }
        }
    }

    // endregion

    // region permission state / actions

    private fun isActionable(kind: Kind) = kind != Kind.INFO && kind != Kind.DONE

    private fun isGranted(kind: Kind): Boolean = when (kind) {
        Kind.NOTIFICATIONS -> hasNotificationPermission(this)
        Kind.BATTERY -> (getSystemService(POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)

        Kind.ALARMS -> AiringAlarmScheduler.canScheduleExact(this)
        Kind.STORAGE -> StoragePermissions.hasDirAccess(this)
        else -> true
    }

    @StringRes
    private fun actionLabel(kind: Kind): Int = when (kind) {
        Kind.NOTIFICATIONS -> R.string.onboarding_notifications_action
        Kind.BATTERY -> R.string.onboarding_battery_action
        Kind.ALARMS -> R.string.onboarding_alarm_action
        Kind.STORAGE -> R.string.onboarding_storage_action
        else -> R.string.onboarding_next
    }

    private fun performAction(kind: Kind) {
        when (kind) {
            Kind.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openNotificationSettings()
                }
            }

            Kind.BATTERY -> {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (e2: Exception) {
                        openAppDetails()
                    }
                }
            }

            Kind.ALARMS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                .setData(Uri.parse("package:$packageName"))
                        )
                    } catch (e: Exception) {
                        openAppDetails()
                    }
                }
            }

            Kind.STORAGE -> {
                folderLauncher.registerForCallback { refreshStatuses() }
                folderLauncher.launch()
            }

            else -> Unit
        }
    }

    private fun openNotificationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (e: Exception) {
            openAppDetails()
        }
    }

    private fun openAppDetails() {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            // nothing else to try
        }
    }

    private fun refreshStatuses() {
        if (!::binding.isInitialized) return
        pages.forEachIndexed { index, page ->
            if (isActionable(page.kind)) pageAdapter.notifyItemChanged(index, PAYLOAD_STATUS)
        }
        updateButtons(binding.onboardingPager.currentItem)
    }

    // endregion

    private fun finishOnboarding() {
        PrefManager.setVal(PrefName.OnboardingDone, true)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    // region page animations

    private fun currentHolder(position: Int): PageViewHolder? {
        val recycler = binding.onboardingPager.getChildAt(0) as? RecyclerView ?: return null
        return recycler.findViewHolderForAdapterPosition(position) as? PageViewHolder
    }

    private fun playEntrance(position: Int) {
        val holder = currentHolder(position) ?: return
        val b = holder.b
        listOf<View>(
            b.iconContainer, b.pageEyebrow, b.pageTitle, b.pageDescription,
            b.featureRow, b.statusChip, b.actionButton
        )
            .forEachIndexed { index, view ->
                if (!view.isVisible) return@forEachIndexed
                // alpha is already handled by the pager transformer while swiping
                view.animate().cancel()
                view.translationY = dp(28).toFloat()
                view.animate()
                    .translationY(0f)
                    .setStartDelay(70L * index)
                    .setDuration(450)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        b.pageIcon.animate().cancel()
        b.pageIcon.scaleX = 0.4f
        b.pageIcon.scaleY = 0.4f
        b.pageIcon.rotation = -20f
        b.pageIcon.animate()
            .scaleX(1f).scaleY(1f).rotation(0f)
            .setStartDelay(120)
            .setDuration(650)
            .setInterpolator(OvershootInterpolator(2f))
            .start()
    }

    // endregion

    private inner class PageViewHolder(val b: ItemOnboardingPageBinding) :
        RecyclerView.ViewHolder(b.root) {
        private val animators = mutableListOf<ValueAnimator>()

        fun bind(page: Page) {
            b.pageIcon.setImageResource(page.icon)
            b.pageIcon.imageTintList =
                if (page.tintIcon) android.content.res.ColorStateList.valueOf(Color.WHITE) else null
            b.pageEyebrow.setText(page.eyebrow)
            b.pageTitle.setText(page.title)
            b.pageDescription.setText(page.description)
            bindFeatures(page)
            bindStatus(page)
        }

        private fun bindFeatures(page: Page) {
            b.featureRow.removeAllViews()
            b.featureRow.isVisible = page.features.isNotEmpty()
            page.features.forEach { res ->
                val chip = TextView(b.root.context).apply {
                    setText(res)
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    typeface = androidx.core.content.res.ResourcesCompat
                        .getFont(context, R.font.poppins_semi_bold)
                    setBackgroundResource(R.drawable.bg_onboarding_chip)
                    setPadding(dp(11), dp(7), dp(11), dp(7))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(4); marginEnd = dp(4) }
                }
                b.featureRow.addView(chip)
            }
        }

        fun bindStatus(page: Page) {
            if (!isActionable(page.kind)) {
                b.statusChip.isVisible = false
                b.actionButton.isVisible = false
                return
            }
            val granted = isGranted(page.kind)
            b.statusChip.isVisible = true
            b.statusChip.setText(
                if (granted) R.string.onboarding_status_enabled else R.string.onboarding_status_pending
            )
            b.statusChip.setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (granted) R.drawable.ic_onboarding_check else R.drawable.ic_onboarding_dot,
                0, 0, 0
            )
            TextViewCompat.setCompoundDrawableTintList(
                b.statusChip,
                ColorStateList.valueOf(if (granted) 0xFF7CFFB2.toInt() else 0xFFFFC857.toInt())
            )
            // Exact alarms need no grant below Android 12, nothing to ask for then.
            b.actionButton.isVisible = !granted
            b.actionButton.setText(actionLabel(page.kind))
            b.actionButton.setOnClickListener { performAction(page.kind) }
        }

        fun startAnimations() {
            stopAnimations()
            animators.add(ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2400
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    b.pageIcon.translationY = -dp(7) * (it.animatedValue as Float)
                }
                start()
            })
        }

        fun stopAnimations() {
            animators.forEach { it.cancel() }
            animators.clear()
        }
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageViewHolder>() {
        override fun getItemCount() = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PageViewHolder(
            ItemOnboardingPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            holder.bind(pages[position])
        }

        override fun onBindViewHolder(
            holder: PageViewHolder, position: Int, payloads: MutableList<Any>
        ) {
            if (payloads.contains(PAYLOAD_STATUS)) holder.bindStatus(pages[position])
            else super.onBindViewHolder(holder, position, payloads)
        }

        override fun onViewAttachedToWindow(holder: PageViewHolder) {
            holder.startAnimations()
        }

        override fun onViewDetachedFromWindow(holder: PageViewHolder) {
            holder.stopAnimations()
        }
    }

    companion object {
        private const val PAYLOAD_STATUS = "status"
    }
}
