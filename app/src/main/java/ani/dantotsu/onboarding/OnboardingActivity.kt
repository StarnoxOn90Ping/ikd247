package ani.dantotsu.onboarding

import android.Manifest
import android.animation.Animator
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.widget.GridLayout
import androidx.documentfile.provider.DocumentFile
import ani.dantotsu.databinding.ItemOnboardingPermissionBinding
import ani.dantotsu.settings.saving.internal.PreferencePackager
import ani.dantotsu.toast
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
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
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import ani.dantotsu.MainActivity
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
import kotlin.math.abs

/**
 * First launch experience: a paged, animated tour that also asks for everything the app needs
 * to work properly in the background (notifications, battery optimisation exemption, exact
 * alarms and a download folder).
 */
class OnboardingActivity : AppCompatActivity() {

    private enum class Kind { INFO, RESTORE, DISCLAIMER, PERMISSIONS, THEMES, DONE }

    private enum class Perm(
        @DrawableRes val icon: Int,
        @StringRes val title: Int,
        @StringRes val desc: Int,
        @StringRes val action: Int
    ) {
        NOTIFICATIONS(
            R.drawable.ic_round_notifications_active_24, R.string.onboarding_perm_notifications,
            R.string.onboarding_perm_notifications_desc, R.string.onboarding_perm_allow
        ),
        BATTERY(
            R.drawable.ic_onboarding_battery, R.string.onboarding_perm_battery,
            R.string.onboarding_perm_battery_desc, R.string.onboarding_perm_allow
        ),
        ALARMS(
            R.drawable.ic_onboarding_alarm, R.string.onboarding_perm_alarms,
            R.string.onboarding_perm_alarms_desc, R.string.onboarding_perm_allow
        ),
        STORAGE(
            R.drawable.ic_round_folder_24, R.string.onboarding_perm_storage,
            R.string.onboarding_perm_storage_desc, R.string.onboarding_perm_choose
        );

        /** Exact alarms only need a grant on Android 12+. */
        fun isRelevant() = this != ALARMS || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }

    /** The idle animation of an icon; picked to match what the icon means. */
    private enum class Motion { FLOAT, BELL, SHAKE, PULSE, HOP, SPIN, SPIN_CCW }

    private data class Page(
        val kind: Kind,
        @DrawableRes val icon: Int,
        @StringRes val eyebrow: Int,
        @StringRes val title: Int,
        @StringRes val description: Int,
        val motion: Motion,
        val tintIcon: Boolean = true,
        /** Size of the icon and of its glow container, in dp. */
        val iconDp: Int = 128,
        val containerDp: Int = 240
    )

    private val pages = listOf(
        Page(
            Kind.INFO, R.drawable.ic_kiwami_round, R.string.onboarding_eyebrow_welcome,
            R.string.onboarding_welcome_title, R.string.onboarding_welcome_desc,
            Motion.FLOAT, tintIcon = false, iconDp = 200, containerDp = 300
        ),
        Page(
            Kind.INFO, R.drawable.ic_round_movie_filter_24, R.string.onboarding_eyebrow_track,
            R.string.onboarding_track_title, R.string.onboarding_track_desc, Motion.FLOAT
        ),
        Page(
            Kind.RESTORE, R.drawable.backup_restore, R.string.onboarding_eyebrow_restore,
            R.string.onboarding_restore_title, R.string.onboarding_restore_desc, Motion.SPIN_CCW,
            iconDp = 112, containerDp = 200
        ),
        Page(
            Kind.DISCLAIMER, R.drawable.ic_shield, R.string.onboarding_eyebrow_disclaimer,
            R.string.onboarding_disclaimer_title, R.string.onboarding_disclaimer_desc,
            Motion.PULSE, iconDp = 104, containerDp = 190
        ),
        Page(
            Kind.PERMISSIONS, R.drawable.ic_onboarding_battery, R.string.onboarding_eyebrow_permissions,
            R.string.onboarding_permissions_title, R.string.onboarding_permissions_desc,
            Motion.PULSE, iconDp = 72, containerDp = 140
        ),
        Page(
            Kind.THEMES, R.drawable.ic_palette, R.string.onboarding_eyebrow_themes,
            R.string.onboarding_themes_title, R.string.onboarding_themes_desc,
            Motion.FLOAT, iconDp = 80, containerDp = 150
        ),
        Page(
            Kind.DONE, R.drawable.ic_onboarding_sparkle, R.string.onboarding_eyebrow_done,
            R.string.onboarding_done_title, R.string.onboarding_done_desc, Motion.SPIN,
            iconDp = 144, containerDp = 260
        )
    )

    private val disclaimerIndex = pages.indexOfFirst { it.kind == Kind.DISCLAIMER }

    /** Accent colour shown for each theme in the picker. */
    private val themeSwatches = mapOf(
        "BLUE" to R.color.md_theme_dark_1_primary,
        "GREEN" to R.color.md_theme_dark_2_primary,
        "PURPLE" to R.color.md_theme_dark_3_primary,
        "PINK" to R.color.md_theme_dark_4_primary,
        "SAIKOU" to R.color.md_theme_dark_5_primary,
        "RED" to R.color.md_theme_dark_6_primary,
        "LAVENDER" to R.color.md_theme_dark_7_primary,
        "OCEAN" to R.color.md_theme_dark_8_primary,
        "ORIAX" to R.color.md_theme_dark_9_primary
    )

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var folderLauncher: LauncherWrapper
    private val pageAdapter = PageAdapter()
    private val dots = mutableListOf<View>()
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

    private val restoreLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                val bytes = contentResolver.openInputStream(uri)?.readBytes()
                    ?: throw Exception("Error reading file")
                val name = DocumentFile.fromSingleUri(this, uri)?.name ?: ""
                if (!name.endsWith(".ani")) {
                    toast(getString(R.string.unknown_file_type))
                    return@registerForActivityResult
                }
                if (PreferencePackager.unpack(bytes.toString(Charsets.UTF_8))) {
                    // The backup replaces every preference, including this flag
                    PrefManager.setVal(PrefName.OnboardingDone, true)
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                    finish()
                } else {
                    toast(getString(R.string.error_importing_settings))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                toast(getString(R.string.error_importing_settings))
            }
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

        // Neutral dark wash: keeps text readable without tinting the artwork with an accent colour
        binding.scrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x8C080814.toInt(), 0x59080814, 0xD9080814.toInt())
        )

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
                updateDots(position + positionOffset)
                binding.tileWall.setParallax(-(position + positionOffset) * dp(36))
            }

            override fun onPageSelected(position: Int) {
                updateButtons(position)
                playEntrance(position)
            }
        })

        // initial state
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
            // Skips the intro, but never the disclaimer
            binding.onboardingPager.setCurrentItem(disclaimerIndex, true)
        }
    }

    private fun updateButtons(position: Int) {
        val page = pages[position]
        val last = position == pages.lastIndex
        val showSkip = position < disclaimerIndex
        binding.skipButton.animate().alpha(if (showSkip) 1f else 0f).setDuration(200).start()
        binding.skipButton.isEnabled = showSkip
        binding.nextButton.setText(
            when {
                last -> R.string.onboarding_get_started
                page.kind == Kind.RESTORE -> R.string.onboarding_start_fresh
                page.kind == Kind.PERMISSIONS && !allPermsGranted() -> R.string.onboarding_maybe_later
                else -> R.string.onboarding_next
            }
        )
    }

    // endregion

    // region permission state / actions

    private fun relevantPerms() = Perm.entries.filter { it.isRelevant() }

    private fun allPermsGranted() = relevantPerms().all { isGranted(it) }

    private fun isGranted(perm: Perm): Boolean = when (perm) {
        Perm.NOTIFICATIONS -> hasNotificationPermission(this)
        Perm.BATTERY -> (getSystemService(POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)

        Perm.ALARMS -> AiringAlarmScheduler.canScheduleExact(this)
        Perm.STORAGE -> StoragePermissions.hasDirAccess(this)
    }

    private fun performAction(perm: Perm) {
        when (perm) {
            Perm.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openNotificationSettings()
                }
            }

            Perm.BATTERY -> {
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

            Perm.ALARMS -> {
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

            Perm.STORAGE -> {
                folderLauncher.registerForCallback { refreshStatuses() }
                folderLauncher.launch()
            }

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
            if (page.kind == Kind.PERMISSIONS) pageAdapter.notifyItemChanged(index, PAYLOAD_STATUS)
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
            b.noteCard, b.permissionList, b.themeGrid, b.themeName, b.themeHint, b.actionButton
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
        b.iconGlow.animate().cancel()
        b.iconGlow.scaleX = 0.5f
        b.iconGlow.scaleY = 0.5f
        b.iconGlow.animate().scaleX(1f).scaleY(1f).setDuration(700)
            .setInterpolator(DecelerateInterpolator()).start()
        // start the idle motion once the pop-in has settled
        holder.startIdle(pages[position])
    }

    // endregion

    private inner class PageViewHolder(val b: ItemOnboardingPageBinding) :
        RecyclerView.ViewHolder(b.root) {
        private var glowAnimator: Animator? = null
        private val idleAnimators = mutableListOf<Animator>()

        fun bind(page: Page) {
            b.iconContainer.updateLayoutParams { width = dp(page.containerDp); height = dp(page.containerDp) }
            b.pageIcon.updateLayoutParams { width = dp(page.iconDp); height = dp(page.iconDp) }
            b.pageIcon.setImageResource(page.icon)
            b.pageIcon.imageTintList =
                if (page.tintIcon) ColorStateList.valueOf(Color.WHITE) else null
            b.pageEyebrow.setText(page.eyebrow)
            b.pageTitle.setText(page.title)
            b.pageDescription.setText(page.description)

            b.noteCard.isVisible = page.kind == Kind.DISCLAIMER
            b.noteCard.setText(R.string.onboarding_disclaimer_note)
            b.permissionList.isVisible = page.kind == Kind.PERMISSIONS
            b.themeGrid.isVisible = page.kind == Kind.THEMES
            b.themeName.isVisible = page.kind == Kind.THEMES
            b.themeHint.isVisible = page.kind == Kind.THEMES
            b.statusChip.isVisible = false

            when (page.kind) {
                Kind.RESTORE -> {
                    b.actionButton.isVisible = true
                    b.actionButton.setText(R.string.onboarding_restore_action)
                    b.actionButton.setOnClickListener { restoreLauncher.launch(arrayOf("*/*")) }
                }

                Kind.PERMISSIONS -> {
                    b.actionButton.isVisible = false
                    buildPermissionRows()
                }

                Kind.THEMES -> {
                    b.actionButton.isVisible = false
                    buildThemeGrid()
                }

                else -> b.actionButton.isVisible = false
            }
        }

        private fun buildPermissionRows() {
            b.permissionList.removeAllViews()
            relevantPerms().forEach { perm ->
                val row = ItemOnboardingPermissionBinding.inflate(
                    LayoutInflater.from(b.root.context), b.permissionList, false
                )
                row.permIcon.setImageResource(perm.icon)
                row.permTitle.setText(perm.title)
                row.permDesc.setText(perm.desc)
                row.permButton.setText(perm.action)
                row.permButton.setOnClickListener { performAction(perm) }
                row.root.tag = perm
                b.permissionList.addView(row.root)
            }
            refreshPermissionRows()
        }

        fun bindStatus(page: Page) {
            if (page.kind == Kind.PERMISSIONS) refreshPermissionRows()
        }

        private fun refreshPermissionRows() {
            for (i in 0 until b.permissionList.childCount) {
                val row = b.permissionList.getChildAt(i)
                val perm = row.tag as? Perm ?: continue
                val granted = isGranted(perm)
                row.findViewById<View>(R.id.permButton).isVisible = !granted
                row.findViewById<View>(R.id.permCheck).isVisible = granted
            }
        }

        private fun buildThemeGrid() {
            val grid = b.themeGrid
            grid.removeAllViews()
            val current: String = PrefManager.getVal(PrefName.Theme)
            val entries = themeSwatches.keys.toList() + "MONOCHROME (BETA)"
            val circles = mutableMapOf<String, View>()

            fun style(key: String, selected: Boolean) {
                val color = themeSwatches[key]?.let { getColor(it) } ?: 0xFF9E9E9E.toInt()
                circles[key]?.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(
                        dp(if (selected) 4 else 1),
                        if (selected) Color.WHITE else 0x66FFFFFF
                    )
                }
                circles[key]?.animate()?.scaleX(if (selected) 1.15f else 1f)
                    ?.scaleY(if (selected) 1.15f else 1f)?.setDuration(180)?.start()
            }

            fun label(key: String) = key.substring(0, 1) + key.substring(1).lowercase()

            entries.forEach { key ->
                val circle = View(b.root.context).apply {
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = dp(48); height = dp(48)
                        setMargins(dp(8), dp(8), dp(8), dp(8))
                        setGravity(Gravity.CENTER)
                    }
                    contentDescription = label(key)
                    setOnClickListener {
                        // A hand-picked theme should win over Material You / custom colours
                        PrefManager.setVal(PrefName.Theme, key)
                        PrefManager.setVal(PrefName.UseMaterialYou, false)
                        PrefManager.setVal(PrefName.UseCustomTheme, false)
                        entries.forEach { style(it, it == key) }
                        b.themeName.text = label(key)
                    }
                }
                circles[key] = circle
                grid.addView(circle)
                style(key, key == current)
            }
            b.themeName.text = label(if (current in themeSwatches) current else "BLUE")
        }

        /** Soft light behind the icon that slowly breathes. */
        fun startGlow() {
            glowAnimator?.cancel()
            glowAnimator = ObjectAnimator.ofPropertyValuesHolder(
                b.iconGlow,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.92f, 1.06f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.92f, 1.06f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.6f, 1f)
            ).apply {
                duration = 2600
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }

        fun startIdle(page: Page) {
            stopIdle()
            val icon = b.pageIcon
            val ease = AccelerateDecelerateInterpolator()

            fun keyframes(
                property: android.util.Property<View, Float>, vararg frames: Pair<Float, Float>
            ) = PropertyValuesHolder.ofKeyframe(
                property,
                *frames.map { (fraction, value) ->
                    Keyframe.ofFloat(fraction, value).apply { interpolator = ease }
                }.toTypedArray()
            )

            fun play(vararg holders: PropertyValuesHolder, ms: Long, reverse: Boolean = false) {
                idleAnimators.add(ObjectAnimator.ofPropertyValuesHolder(icon, *holders).apply {
                    duration = ms
                    startDelay = 750
                    repeatCount = ValueAnimator.INFINITE
                    if (reverse) repeatMode = ValueAnimator.REVERSE
                    interpolator = if (reverse) ease else LinearInterpolator()
                    start()
                })
            }

            icon.pivotX = icon.width / 2f
            icon.pivotY = icon.height / 2f
            when (page.motion) {
                Motion.FLOAT -> play(
                    PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -dp(12).toFloat()),
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.05f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.05f),
                    ms = 2400, reverse = true
                )

                Motion.BELL -> {
                    // swings from the top like a real bell, then rests
                    icon.pivotY = icon.height * 0.14f
                    play(
                        keyframes(
                            View.ROTATION,
                            0f to 0f, 0.07f to 20f, 0.15f to -17f, 0.23f to 12f,
                            0.31f to -8f, 0.39f to 4f, 0.46f to 0f, 1f to 0f
                        ),
                        ms = 3400
                    )
                }

                Motion.SHAKE -> play(
                    keyframes(
                        View.ROTATION,
                        0f to 0f, 0.05f to 10f, 0.10f to -10f, 0.15f to 9f, 0.20f to -9f,
                        0.25f to 6f, 0.30f to -6f, 0.35f to 0f, 1f to 0f
                    ),
                    ms = 2800
                )

                Motion.PULSE -> play(
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 0.94f, 1.12f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.94f, 1.12f),
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0.78f, 1f),
                    ms = 1100, reverse = true
                )

                Motion.HOP -> play(
                    keyframes(
                        View.TRANSLATION_Y,
                        0f to 0f, 0.12f to -dp(22).toFloat(), 0.26f to 0f,
                        0.34f to -dp(8).toFloat(), 0.42f to 0f, 1f to 0f
                    ),
                    ms = 2600
                )

                Motion.SPIN, Motion.SPIN_CCW -> {
                    // SPIN_CCW (restore page) turns anti-clockwise, like the arrow on its icon
                    val end = if (page.motion == Motion.SPIN_CCW) -360f else 360f
                    play(PropertyValuesHolder.ofFloat(View.ROTATION, 0f, end), ms = 8000)
                    play(
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 0.9f, 1.1f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.9f, 1.1f),
                        ms = 1600, reverse = true
                    )
                }
            }
        }

        private fun stopIdle() {
            idleAnimators.forEach { it.cancel() }
            idleAnimators.clear()
            b.pageIcon.rotation = 0f
            b.pageIcon.translationY = 0f
            b.pageIcon.alpha = 1f
            // scale is left alone: the pop-in animation owns it
        }

        fun stopAnimations() {
            glowAnimator?.cancel()
            glowAnimator = null
            stopIdle()
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
            holder.startGlow()
        }

        override fun onViewDetachedFromWindow(holder: PageViewHolder) {
            holder.stopAnimations()
        }
    }

    companion object {
        private const val PAYLOAD_STATUS = "status"
    }
}
