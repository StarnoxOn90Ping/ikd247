package ani.dantotsu.media

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.text.bold
import androidx.core.text.color
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updateMargins
import androidx.core.view.doOnLayout
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import ani.dantotsu.GesturesListener
import ani.dantotsu.MainActivity
import ani.dantotsu.R
import ani.dantotsu.Refresh
import ani.dantotsu.ZoomOutPageTransformer
import ani.dantotsu.blurImage
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ActivityMediaBinding
import ani.dantotsu.getThemeColor
import ani.dantotsu.initActivity
import ani.dantotsu.loadImage
import ani.dantotsu.media.anime.AnimeWatchFragment
import ani.dantotsu.media.comments.CommentsFragment
import ani.dantotsu.notifications.comment.MediaNameFetch
import ani.dantotsu.media.manga.MangaReadFragment
import ani.dantotsu.media.novel.NovelReadFragment
import ani.dantotsu.navBarHeight
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.others.AndroidBug5497Workaround
import ani.dantotsu.others.ImageViewDialog
import ani.dantotsu.others.getSerialized
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.LauncherWrapper
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.google.android.material.appbar.AppBarLayout
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import nl.joery.animatedbottombar.AnimatedBottomBar
import kotlin.math.abs
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds


class MediaDetailsActivity : AppCompatActivity(), AppBarLayout.OnOffsetChangedListener {
    lateinit var launcher: LauncherWrapper
    lateinit var binding: ActivityMediaBinding

    /** False while the activity is bailing out of [onCreate] without any media. */
    val bindingReady get() = ::binding.isInitialized
    val safeBinding get() = if (::binding.isInitialized) binding else null
    private val scope = lifecycleScope
    private val model: MediaDetailsViewModel by viewModels()
    var selected = 0
    lateinit var navBar: AnimatedBottomBar
    var anime = true
    private var adult = false
    lateinit var media: Media

    // New episode / chapter alert button (next to the favourite button)
    private var notifyButton: PopImageButton? = null
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) snackString(getString(R.string.notifications_permission_denied))
        }

    // Custom cover/banner (kept on-device only; the Media object and tracking are never touched)
    private var pendingImageKind: ani.dantotsu.others.CustomMediaImages.Kind? = null
    private var pendingImageDialog: ImageViewDialog? = null
    private var applyMediaImages: (() -> Unit)? = null
    private val pickCustomImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val kind = pendingImageKind
            val dialog = pendingImageDialog
            pendingImageKind = null
            pendingImageDialog = null
            if (uri == null || kind == null || !::media.isInitialized) return@registerForActivityResult
            scope.launch {
                val ok = ani.dantotsu.others.CustomMediaImages.save(
                    this@MediaDetailsActivity, media.id, kind, uri
                )
                if (ok) {
                    applyMediaImages?.invoke()
                    runCatching { dialog?.dismissAllowingStateLoss() }
                    snackString("Image updated")
                } else {
                    snackString("Couldn't use that image")
                }
            }
        }

    private fun showImageViewer(kind: ani.dantotsu.others.CustomMediaImages.Kind) {
        val isCover = kind == ani.dantotsu.others.CustomMediaImages.Kind.COVER
        val title = getString(if (isCover) R.string.cover else R.string.banner, media.userPreferredName)
        val custom = ani.dantotsu.others.CustomMediaImages.path(this, media.id, kind)
        val current = custom ?: if (isCover) media.cover else (media.banner ?: media.cover)
        val canCustomize = ani.dantotsu.others.CustomMediaImages.canCustomize(media.id, media.cover, media.banner)
        ImageViewDialog.newInstance(
            this,
            title,
            current,
            if (canCustomize) { dialog ->
                pendingImageKind = kind
                pendingImageDialog = dialog
                pickCustomImage.launch("image/*")
            } else null,
            if (canCustomize && custom != null) { dialog ->
                ani.dantotsu.others.CustomMediaImages.remove(this, media.id, kind)
                applyMediaImages?.invoke()
                dialog.dismissAllowingStateLoss()
                snackString("Original image restored")
            } else null
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::media.isInitialized && media.name != "No media found") {
            outState.putInt("saved_media_id", media.id)
            mediaCache.put(media.id, media)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val savedId = savedInstanceState?.getInt("saved_media_id", -1)?.takeIf { it != -1 }
        var media: Media = intent.getSerialized("media")
            ?: savedId?.let { mediaCache.get(it) }
            ?: savedInstanceState?.getSerialized("saved_media")
            ?: mediaSingleton
            ?: emptyMedia()
        val id = intent.getIntExtra("mediaId", -1).takeIf { it != -1 }
            ?: savedId
            ?: -1
        if (id != -1 && media.name == "No media found") {
            runBlocking(Dispatchers.IO) {
                media = Anilist.query.getMedia(id) ?: emptyMedia()
            }
        }
        this.media = media
        if (media.name == "No media found") {
            snackString(media.name)
            finish()
            return
        }
        intent.putExtra("media", media as java.io.Serializable)
        intent.putExtra("mediaId", media.id)
        val contract = ActivityResultContracts.OpenDocumentTree()
        launcher = LauncherWrapper(this, contract)

        mediaSingleton = null
        ThemeManager(this).applyTheme(MediaSingleton.bitmap)
        initActivity(this)
        MediaSingleton.bitmap = null

        binding = ActivityMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        screenWidth = resources.displayMetrics.widthPixels.toFloat()
        navBar = binding.mediaBottomBar

        supportFragmentManager.addOnBackStackChangedListener {
            syncExtensionPrefsUi()
        }

        // Ui init


        binding.mediaBottomBarContainer?.setPadding(0, 0, 0, navBarHeight)

        AndroidBug5497Workaround.assistActivity(this) { keyboardVisible ->
            // Optionally hide nav bar when keyboard is visible
            navBar.visibility = if (keyboardVisible) View.GONE else View.VISIBLE
        }
        // Header geometry: cover/title sit 1.5x as far from the top as before, banner is a full backdrop behind them
        val density = resources.displayMetrics.density
        val coverTop = ((statusBarHeight + 58 * density) * 1.5f).toInt()
        binding.mediaCollapseContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = coverTop }
        binding.mediaBannerHolder.updateLayoutParams { height = coverTop + (72 * density).toInt() }
        setupBannerFade()
        binding.mediaClose.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin += statusBarHeight }
        binding.mediaHome.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin += statusBarHeight }
        binding.incognito.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin += statusBarHeight }
        binding.mediaCollapsing.minimumHeight = statusBarHeight

        binding.mediaTitle.isSelected = true

        mMaxScrollSize = binding.mediaAppBar.totalScrollRange
        binding.mediaAppBar.addOnOffsetChangedListener(this)

        binding.mediaHome.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("goToHome", true)
            }
            startActivity(intent)
            finish()
        }

        binding.mediaClose.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        val banner = binding.mediaBanner
        val viewPager = binding.mediaViewPager
        viewPager.offscreenPageLimit = 2
        viewPager.isUserInputEnabled = false
        viewPager.setPageTransformer(ZoomOutPageTransformer())


        val isDownload = intent.getBooleanExtra("download", false)
        media.selected = model.loadSelected(media, isDownload)

        applyMediaImages = {
            val customCover = ani.dantotsu.others.CustomMediaImages.path(
                this, media.id, ani.dantotsu.others.CustomMediaImages.Kind.COVER
            )
            val customBanner = ani.dantotsu.others.CustomMediaImages.path(
                this, media.id, ani.dantotsu.others.CustomMediaImages.Kind.BANNER
            )
            binding.mediaCoverImage.loadImage(customCover ?: media.cover)
            val realBanner = customBanner ?: media.banner
            if (!realBanner.isNullOrEmpty()) {
                // Real banner: shown whole, sharp and unanimated
                showSharpBanner(realBanner)
            } else {
                // No banner at all: fall back to the (blurred) cover so the strip is never empty
                showStaticBanner()
                blurImage(banner, customCover ?: media.cover)
            }
        }
        applyMediaImages?.invoke()
        binding.mediaCoverImage.setOnLongClickListener {
            showImageViewer(ani.dantotsu.others.CustomMediaImages.Kind.COVER)
            true
        }

        val gestureDetector = GestureDetector(this, object : GesturesListener() {
            override fun onLongClick(event: MotionEvent) {
                showImageViewer(ani.dantotsu.others.CustomMediaImages.Kind.BANNER)
                banner.performClick()
            }
        })
        banner.setOnTouchListener { _, motionEvent -> gestureDetector.onTouchEvent(motionEvent);true }
        if (PrefManager.getVal(PrefName.Incognito)) {
            val mediaTitle = "    ${media.userPreferredName}"
            binding.mediaTitle.text = mediaTitle
            binding.incognito.visibility = View.VISIBLE
        } else {
            binding.mediaTitle.text = media.userPreferredName
        }
        binding.mediaTitle.setOnLongClickListener {
            copyToClipboard(media.userPreferredName)
            true
        }
        binding.mediaTitleCollapse.text = media.userPreferredName
        binding.mediaTitleCollapse.setOnLongClickListener {
            copyToClipboard(media.userPreferredName)
            true
        }
        MediaNameFetch.cacheMedia(
            media.id,
            media.userPreferredName,
            media.cover ?: "",
            "#222222"
        )
        binding.mediaStatus.text = media.status ?: ""

        fun fav(media: Media):  PopImageButton? {
            //Fav Button
            return if (Anilist.userid != null) {
                if (media.isFav) binding.mediaFav.setImageDrawable(
                    AppCompatResources.getDrawable(
                        this,
                        R.drawable.ic_round_favorite_24
                    )
                )

                PopImageButton(
                    scope,
                    binding.mediaFav,
                    R.drawable.ic_round_favorite_24,
                    R.drawable.ic_round_favorite_border_24,
                    R.color.bg_opp,
                    R.color.violet_400,
                    media.isFav
                ) {
                    media.isFav = it
                    Anilist.mutation.toggleFav(media.anime != null, media.id)
                    Refresh.all()
                }
            } else {
                binding.mediaFav.visibility = View.GONE
                null
            }
        }
        fun setupNotifyButton(media: Media): PopImageButton {
            val subscribed =
                ani.dantotsu.notifications.subscription.SubscriptionHelper.getSubscriptions()
                    .containsKey(media.id)
            binding.mediaNotify.visibility = View.VISIBLE
            if (subscribed) {
                binding.mediaNotify.setImageDrawable(
                    AppCompatResources.getDrawable(this, R.drawable.ic_round_notifications_active_24)
                )
                binding.mediaNotify.setColorFilter(getColor(R.color.violet_400))
            }
            return PopImageButton(
                scope,
                binding.mediaNotify,
                R.drawable.ic_round_notifications_active_24,
                R.drawable.ic_round_notifications_none_24,
                R.color.bg_opp,
                R.color.violet_400,
                subscribed
            ) { enabled ->
                ani.dantotsu.notifications.subscription.SubscriptionHelper
                    .saveSubscription(media, enabled)
                withContext(Dispatchers.Main) { onNotifyToggled(media, enabled) }
            }
        }
        var isFavSyncRunning = false
        fun syncMediaFavStateIfNeeded(favButton: PopImageButton?) {
            if (Anilist.userid == null || favButton == null || media.isFav || isFavSyncRunning) return
            isFavSyncRunning = true
            scope.launch {
                try {
                    val favType = if (media.anime != null) {
                        ani.dantotsu.connections.anilist.AnilistMutations.FavType.ANIME
                    } else {
                        ani.dantotsu.connections.anilist.AnilistMutations.FavType.MANGA
                    }
                    val isUserFav = withContext(Dispatchers.IO) {
                        Anilist.query.isUserFav(favType, media.id)
                    }
                    if (isUserFav) {
                        media.isFav = true
                        if (!favButton.clicked) favButton.clicked()
                    }
                } finally {
                    isFavSyncRunning = false
                }
            }
        }

        // "WATCHING • 10/13": the count only makes sense while the entry is in progress
        fun statusLabel(userStatus: String): CharSequence {
            val showCount = when (media.userStatus) {
                "CURRENT", "REPEATING", "PAUSED", "DROPPED" -> true
                else -> false
            }
            if (!showCount) return userStatus
            val secondary = getThemeColor(com.google.android.material.R.attr.colorSecondary)
            val onBackground =
                getThemeColor(com.google.android.material.R.attr.colorOnBackground)
            val total = (if (media.anime != null) media.anime!!.totalEpisodes
            else media.manga?.totalChapters)?.toString() ?: "~"
            return SpannableStringBuilder().apply {
                append(userStatus)
                color(ColorUtils.setAlphaComponent(secondary, 140)) { append("  •  ") }
                bold { color(onBackground) { append("${media.userProgress ?: 0} / $total") } }
            }
        }

        fun progress() {
            val statuses: Array<String> = resources.getStringArray(R.array.status)
            val statusStrings =
                if (media.manga == null) resources.getStringArray(R.array.status_anime) else resources.getStringArray(
                    R.array.status_manga
                )
            val userStatus =
                if (media.userStatus != null) statusStrings[statuses.indexOf(media.userStatus).coerceAtLeast(0)] else statusStrings[0]

            if (media.userStatus != null) {
                binding.mediaAddToList.text = statusLabel(userStatus)
            } else {
                binding.mediaAddToList.setText(R.string.add_list)
            }
            binding.mediaAddToList.setOnClickListener {
                if (Anilist.userid != null) {
                    if (supportFragmentManager.findFragmentByTag("dialog") == null)
                        MediaListDialogFragment().show(supportFragmentManager, "dialog")
                } else snackString(getString(R.string.please_login_anilist))
            }
            binding.mediaAddToList.setOnLongClickListener {
                PrefManager.setCustomVal(
                    "${media.id}_progressDialog",
                    true,
                )
                snackString(getString(R.string.auto_update_reset))
                true
            }
        }
        progress()

        model.getDetailsOutdated().observe(this) { outdated ->
            if (outdated == true) {
                model.clearDetailsOutdated()
                com.google.android.material.snackbar.Snackbar.make(
                    binding.root,
                    "Info on this page is outdated",
                    com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE
                ).setAction("Refresh") {
                    Refresh.activity[this.hashCode()]?.postValue(true)
                }.show()
            }
        }

        model.getMedia().observe(this) {
            if (it != null) {
                val oldId = media.id
                media = it
                bindMediaStatsBar(binding, media)

                // mapping button for LOCAL media
                if (media.format?.startsWith("LOCAL") == true) {
                    binding.mediaMapping?.visibility = View.VISIBLE
                    binding.mediaMapping?.setOnClickListener {
                        val isAnime = media.anime != null
                        val isNovel = media.format == "LOCAL_NOVEL"
                        val folderName = media.folderName ?: media.name ?: media.nameRomaji
                        val dialog = LocalMappingSearchDialog.newInstance(
                            folderName = folderName,
                            isAnime = isAnime,
                            isNovel = isNovel
                        ) { _ ->
                            // Re-trigger it
                            val updatedMedia = media.copy(id = 0)
                            model.loading = false
                            model.loadMedia(updatedMedia)
                        }
                        dialog.show(supportFragmentManager, "localMapping")
                    }
                }

                notifyButton = setupNotifyButton(it)

                scope.launch {
                    val favIcon = fav(it)
                    syncMediaFavStateIfNeeded(favIcon)
                    if (media.isFav != favIcon?.clicked) favIcon?.clicked()
                }


                binding.mediaCover.setOnClickListener {
                    openLinkInBrowser(media.shareLink)
                }

                if (oldId == 0 && media.id != 0) {
                    if (media.format?.startsWith("LOCAL") == true) {
                        applyMediaImages?.invoke()
                    }
                }
                progress()
            }
        }
        adult = media.isAdult
        if (media.anime != null) {
            viewPager.adapter =
                ViewPagerAdapter(
                    supportFragmentManager,
                    lifecycle,
                    SupportedMedia.ANIME,
                    media,
                    intent.getIntExtra("commentId", -1)
                )
        } else if (media.manga != null) {
            viewPager.adapter = ViewPagerAdapter(
                supportFragmentManager,
                lifecycle,
                if (media.format == "NOVEL" || media.format == "LOCAL_NOVEL" || media.format == "LIGHT_NOVEL") SupportedMedia.NOVEL else SupportedMedia.MANGA,
                media,
                intent.getIntExtra("commentId", -1)
            )
            anime = false
        }
        // always open on the Info tab, whatever tab was used last
        selected = 0
        binding.mediaTitle.translationX = -screenWidth
        binding.mediaTitleContainer.visibility = View.GONE

        val infoTab = navBar.createTab(R.drawable.ic_round_info_24, R.string.info, R.id.info)
        val watchTab = if (anime) {
            navBar.createTab(R.drawable.ic_round_movie_filter_24, R.string.watch, R.id.watch)
        } else if (media.format == "NOVEL" || media.format == "LOCAL_NOVEL" || media.format == "LIGHT_NOVEL") {
            navBar.createTab(R.drawable.ic_round_book_24, R.string.read, R.id.read)
        } else {
            navBar.createTab(R.drawable.ic_round_import_contacts_24, R.string.read, R.id.read)
        }
        val commentTab =
            navBar.createTab(R.drawable.ic_round_comment_24, R.string.comments, R.id.comment)
        navBar.addTab(infoTab)
        navBar.addTab(watchTab)
        if (PrefManager.getVal<Int>(PrefName.CommentsEnabled) == 1) {
            navBar.addTab(commentTab)
        }
        if (model.continueMedia == null && media.cameFromContinue) {
            model.continueMedia = PrefManager.getVal(PrefName.ContinueMedia)
        }
        if (intent.getStringExtra("FRAGMENT_TO_LOAD") != null && PrefManager.getVal<Int>(PrefName.CommentsEnabled) == 1) selected = 2
        if (viewPager.currentItem != selected) viewPager.post {
            viewPager.setCurrentItem(selected, false)
        }
        binding.commentInputLayout.isVisible = selected == 2

        // Ensure that if we are returning from the comments tab, we go back to the media content tab
        if (selected == 2 && (PrefManager.getVal<Int>(PrefName.CommentsEnabled) != 1)) {
            selected = 1
        }
        navBar.selectTabAt(selected)
        navBar.setOnTabSelectListener(object : AnimatedBottomBar.OnTabSelectListener {
            override fun onTabSelected(
                lastIndex: Int,
                lastTab: AnimatedBottomBar.Tab?,
                newIndex: Int,
                newTab: AnimatedBottomBar.Tab
            ) {
                selected = newIndex
                binding.commentInputLayout.isVisible = selected == 2
                viewPager.setCurrentItem(selected, true)
                val sel = model.loadSelected(media, isDownload)
                sel.window = selected
                model.saveSelected(media.id, sel)
            }
        })

        val live = Refresh.activity.getOrPut(this.hashCode()) { MutableLiveData(true) }
        live.observe(this) {
            if (it) {
                scope.launch(Dispatchers.IO) {
                    model.loadMedia(media)
                    live.postValue(false)
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::navBar.isInitialized) return
        val rightMargin = if (resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        ) navBarHeight else 0
        val bottomMargin = if (resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        ) 0 else navBarHeight
        val params: ViewGroup.MarginLayoutParams =
            navBar.layoutParams as ViewGroup.MarginLayoutParams
        params.updateMargins(right = rightMargin, bottom = bottomMargin)
    }

    private fun onNotifyToggled(media: Media, enabled: Boolean) {
        if (!enabled) {
            snackString(getString(R.string.unsubscribed_notification))
            return
        }
        // Without these the alert could not reach the user while the app is closed
        if (!ani.dantotsu.hasNotificationPermission(this) &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!ani.dantotsu.notifications.airing.AiringAlarmScheduler.canScheduleExact(this) &&
            !PrefManager.getCustomVal("exact_alarm_prompted", false)
        ) {
            PrefManager.setCustomVal("exact_alarm_prompted", true)
            try {
                startActivity(
                    Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(android.net.Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
            }
        }
        val airingAt = media.anime?.nextAiringEpisodeTime
        val next = media.anime?.nextAiringEpisode
        snackString(
            when {
                media.anime == null -> getString(R.string.chapter_alert_enabled)
                airingAt != null && next != null ->
                    getString(R.string.episode_alert_enabled, next + 1)

                else -> getString(R.string.episode_alert_enabled_unscheduled)
            }
        )
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        if (::media.isInitialized) {
            notifyButton?.setState(
                ani.dantotsu.notifications.subscription.SubscriptionHelper.getSubscriptions()
                    .containsKey(media.id)
            )
        }
        syncExtensionPrefsUi()
        binding.root.requestLayout()
    }

    private fun syncExtensionPrefsUi() {
        if (!::binding.isInitialized) return
        val hasExtFragment =
            supportFragmentManager.findFragmentById(R.id.fragmentExtensionsContainer) != null

        binding.fragmentExtensionsContainer.isVisible = hasExtFragment
        binding.mediaAppBar.isVisible = !hasExtFragment
        binding.mediaViewPager.isVisible = !hasExtFragment
        binding.mediaCover.isVisible = !hasExtFragment
        binding.mediaClose.isVisible = !hasExtFragment
        binding.commentInputLayout.isVisible = !hasExtFragment && selected == 2
        if (::navBar.isInitialized) {
            navBar.isVisible = !hasExtFragment
            if (!hasExtFragment) navBar.selectTabAt(selected)
        }
    }

    private enum class SupportedMedia {
        ANIME, MANGA, NOVEL
    }

    // ViewPager
    private class ViewPagerAdapter(
        fragmentManager: FragmentManager,
        lifecycle: Lifecycle,
        private val mediaType: SupportedMedia,
        private val media: Media,
        private val commentId: Int
    ) :
        FragmentStateAdapter(fragmentManager, lifecycle) {

        override fun getItemCount(): Int = if (PrefManager.getVal<Int>(PrefName.CommentsEnabled) == 1) 3 else 2

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> MediaInfoFragment()
            1 -> when (mediaType) {
                SupportedMedia.ANIME -> AnimeWatchFragment()
                SupportedMedia.MANGA -> MangaReadFragment()
                SupportedMedia.NOVEL -> NovelReadFragment()
            }

            2 -> { // Index 2
                if (PrefManager.getVal<Int>(PrefName.CommentsEnabled) == 1) {
                    val fragment = CommentsFragment()
                    val bundle = Bundle()
                    bundle.putInt("mediaId", media.id)
                    bundle.putString("mediaName", media.mainName())
                    bundle.putString("mediaFormat", media.format)
                    if (commentId != -1) bundle.putInt("commentId", commentId)
                    fragment.arguments = bundle
                    fragment
                } else {
                    MediaInfoFragment() // Fallback to Info tab if comments are disabled
                }
            }

            else -> MediaInfoFragment()
        }
    }

    //Collapsing UI Stuff
    private var isCollapsed = false
    private val percent = 45
    private var mMaxScrollSize = 0

    private var bannerPan: ObjectAnimator? = null
    private var screenWidth: Float = 0f

    /** Page-background fade at the bottom of the banner so it melts into the content (no blur involved). */
    private fun setupBannerFade() {
        val bg = getThemeColor(android.R.attr.colorBackground)
        binding.mediaBannerFade.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                ColorUtils.setAlphaComponent(bg, 0),
                ColorUtils.setAlphaComponent(bg, 18),
                ColorUtils.setAlphaComponent(bg, 70),
                ColorUtils.setAlphaComponent(bg, 150),
                ColorUtils.setAlphaComponent(bg, 225),
                bg
            )
        )
    }

    /** Fallback (no banner): the image just fills the backdrop, no panning. */
    private fun showStaticBanner() {
        bannerPan?.cancel()
        bannerPan = null
        binding.mediaBanner.translationX = 0f
        binding.mediaBanner.scaleType = ImageView.ScaleType.CENTER_CROP
        binding.mediaBanner.updateLayoutParams<FrameLayout.LayoutParams> {
            width = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    /**
     * Scales the banner to the backdrop's height (so it is never zoomed beyond what's needed to fill it),
     * then slowly glides it left <-> right across the extra width.
     */
    private fun layoutBannerPan(imageW: Int, imageH: Int) {
        if (!::binding.isInitialized || imageW <= 0 || imageH <= 0) return
        binding.mediaBannerHolder.doOnLayout { holder ->
            val viewW = holder.width.toFloat()
            val viewH = holder.height.toFloat()
            if (viewW <= 0f || viewH <= 0f) return@doOnLayout
            val scale = max(viewH / imageH, viewW / imageW)
            val scaledW = (imageW * scale).toInt()
            val banner = binding.mediaBanner
            banner.scaleType = ImageView.ScaleType.CENTER_CROP
            banner.updateLayoutParams<FrameLayout.LayoutParams> {
                width = scaledW
                height = viewH.toInt()
                gravity = Gravity.START
            }
            bannerPan?.cancel()
            bannerPan = null
            banner.translationX = 0f
            val range = (scaledW - viewW).coerceAtLeast(0f)
            if (range < 1f) return@doOnLayout
            if (!(PrefManager.getVal(PrefName.BannerAnimations) as Boolean)) {
                banner.translationX = -range / 2f
                return@doOnLayout
            }
            val speed = PrefManager.getVal(PrefName.AnimationSpeed) as Float
            bannerPan = ObjectAnimator.ofFloat(banner, "translationX", 0f, -range).apply {
                duration = (10000 + 15000 * speed).toLong()
                interpolator = AccelerateDecelerateInterpolator()
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                start()
                if (isCollapsed) pause()
            }
        }
    }

    private fun showSharpBanner(url: String) {
        val source: Any = when {
            url.startsWith("content://") -> url.toUri()
            File(url).exists() -> File(url)
            else -> ani.dantotsu.others.CustomMediaImages.overrideFor(url)
                ?: ani.dantotsu.others.PinnedImages.fileFor(url)
                ?: GlideUrl(url)
        }
        showStaticBanner()
        Glide.with(this)
            .load(source)
            .override(Target.SIZE_ORIGINAL) // full resolution: it is shown wider than the screen, so it must stay crisp
            .transition(withCrossFade(400))
            .listener(object : RequestListener<Drawable> {
                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    layoutBannerPan(resource.intrinsicWidth, resource.intrinsicHeight)
                    return false
                }

                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean = false
            })
            .into(binding.mediaBanner)
    }

    override fun onOffsetChanged(appBar: AppBarLayout, i: Int) {
        if (!::binding.isInitialized) return
        if (mMaxScrollSize == 0) mMaxScrollSize = appBar.totalScrollRange
        val percentage = abs(i) * 100 / mMaxScrollSize

        binding.mediaCover.visibility =
            if (binding.mediaCover.scaleX == 0f) View.GONE else View.VISIBLE
        val duration = (200 * (PrefManager.getVal(PrefName.AnimationSpeed) as Float)).toLong()
        if (percentage >= percent && !isCollapsed) {
            isCollapsed = true
            binding.mediaTitleContainer.visibility = View.VISIBLE
            ObjectAnimator.ofFloat(binding.mediaTitle, "translationX", 0f).setDuration(duration)
                .start()
            ObjectAnimator.ofFloat(binding.mediaCover, "translationX", screenWidth)
                .setDuration(duration).start()
            ObjectAnimator.ofFloat(binding.mediaCollapseContainer, "translationX", screenWidth)
                .setDuration(duration).start()
            bannerPan?.pause()
        }
        if (percentage <= percent && isCollapsed) {
            isCollapsed = false
            binding.mediaTitleContainer.visibility = View.GONE
            ObjectAnimator.ofFloat(binding.mediaTitle, "translationX", -screenWidth)
                .setDuration(duration).start()
            ObjectAnimator.ofFloat(binding.mediaCover, "translationX", 0f).setDuration(duration)
                .start()
            ObjectAnimator.ofFloat(binding.mediaCollapseContainer, "translationX", 0f)
                .setDuration(duration).start()
            bannerPan?.resume()
        }
        if (percentage == 1 && model.scrolledToTop.value != false) model.scrolledToTop.postValue(
            false
        )
        if (percentage == 0 && model.scrolledToTop.value != true) model.scrolledToTop.postValue(true)
    }

    class PopImageButton(
        private val scope: CoroutineScope,
        private val image: ImageView,
        private val d1: Int,
        private val d2: Int,
        private val c1: Int,
        private val c2: Int,
        var clicked: Boolean,
        needsInitialClick: Boolean = false,
        callback: suspend (Boolean) -> (Unit)
    ) {
        private var disabled = false
        private val context = image.context
        private var pressable = true

        fun setState(state: Boolean) {
            if (clicked != state) {
                clicked = state
                scope.launch {
                    clicked()
                }
            }
        }

        init {
            enabled(true)
            if (needsInitialClick) {
                scope.launch {
                    clicked()
                }
            }
            image.setOnClickListener {
                if (pressable && !disabled) {
                    pressable = false
                    clicked = !clicked
                    scope.launch {
                        launch(Dispatchers.IO) {
                            callback.invoke(clicked)
                        }
                        clicked()
                        pressable = true
                    }
                }
            }
        }

        suspend fun clicked() {
            ObjectAnimator.ofFloat(image, "scaleX", 1f, 0f).setDuration(69).start()
            ObjectAnimator.ofFloat(image, "scaleY", 1f, 0f).setDuration(100).start()
            delay(100.milliseconds)

            if (clicked) {
                ObjectAnimator.ofArgb(
                    image,
                    "ColorFilter",
                    ContextCompat.getColor(context, c1),
                    ContextCompat.getColor(context, c2)
                ).setDuration(120).start()
                image.setImageDrawable(AppCompatResources.getDrawable(context, d1))
            } else image.setImageDrawable(AppCompatResources.getDrawable(context, d2))
            ObjectAnimator.ofFloat(image, "scaleX", 0f, 1.25f).setDuration(120).start()
            ObjectAnimator.ofFloat(image, "scaleY", 0f, 1.25f).setDuration(100).start()
            delay(120.milliseconds)
            ObjectAnimator.ofFloat(image, "scaleX", 1.25f, 1f).setDuration(100).start()
            ObjectAnimator.ofFloat(image, "scaleY", 1.25f, 1f).setDuration(100).start()
            delay(200.milliseconds)
            if (clicked) {
                ObjectAnimator.ofArgb(
                    image,
                    "ColorFilter",
                    ContextCompat.getColor(context, c2),
                    ContextCompat.getColor(context, c1)
                ).setDuration(200).start()
            }
        }

        fun enabled(enabled: Boolean) {
            disabled = !enabled
            image.alpha = if (disabled) 0.33f else 1f
        }
    }

    override fun onProvideAssistContent(outContent: android.app.assist.AssistContent?) {
        super.onProvideAssistContent(outContent)
        outContent?.intent = null
    }

    override fun onDestroy() {
        super.onDestroy()
        bannerPan?.cancel()
        bannerPan = null
        Refresh.activity.remove(this.hashCode())
        mediaSingleton = null
        MediaSingleton.bitmap = null
        MediaSingleton.media = null
        if (::binding.isInitialized) {
            binding.mediaViewPager.adapter = null
        }
    }

    companion object {
        var mediaSingleton: Media? = null
        private val mediaCache = android.util.LruCache<Int, Media>(10)
    }
}
