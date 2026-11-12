package ani.kiwami

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.Animatable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnticipateInterpolator
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.view.doOnAttach
import androidx.core.view.updateLayoutParams
import androidx.core.view.updateMargins
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.viewpager2.adapter.FragmentStateAdapter
import ani.kiwami.torrent.TorrentServerManager
import ani.kiwami.addons.torrent.TorrentServerService
import ani.kiwami.connections.anilist.Anilist
import ani.kiwami.connections.anilist.AnilistHomeViewModel
import ani.kiwami.databinding.ActivityMainBinding
import ani.kiwami.databinding.DialogUserAgentBinding
import ani.kiwami.databinding.SplashScreenBinding
import ani.kiwami.home.AnimeFragment
import ani.kiwami.home.HomeFragment
import ani.kiwami.home.LoginFragment
import ani.kiwami.home.MangaFragment
import ani.kiwami.home.OfflineScreen
import ani.kiwami.home.OfflineLaunchDialog
import ani.kiwami.home.OfflinePill
import ani.kiwami.media.MediaDetailsActivity
import ani.kiwami.notifications.TaskScheduler
import ani.kiwami.others.CustomBottomDialog
import ani.kiwami.others.calc.CalcActivity
import ani.kiwami.profile.ProfileActivity
import ani.kiwami.profile.activity.FeedActivity
import ani.kiwami.profile.notification.NotificationActivity
import ani.kiwami.settings.AddRepositoryBottomSheet
import ani.kiwami.settings.ExtensionsActivity
import ani.kiwami.settings.saving.PrefManager
import ani.kiwami.settings.saving.PrefManager.asLiveBool
import ani.kiwami.settings.saving.PrefName
import ani.kiwami.settings.saving.SharedPreferenceBooleanLiveData
import ani.kiwami.settings.saving.internal.PreferenceKeystore
import ani.kiwami.settings.saving.internal.PreferencePackager
import ani.kiwami.themes.ThemeManager
import ani.kiwami.util.AudioHelper
import ani.kiwami.util.Logger
import ani.kiwami.util.customAlertDialog
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import eu.kanade.domain.source.service.SourcePreferences
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.joery.animatedbottombar.AnimatedBottomBar
import tachiyomi.core.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.Serializable


class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var incognitoLiveData: SharedPreferenceBooleanLiveData
    private val scope = lifecycleScope
    private var load = false
    private var sideButtons: ani.kiwami.home.HomeSideButtons? = null
    private var offlinePill: OfflinePill? = null
    private var offlineDialog: OfflineLaunchDialog? = null
    private var offlineScreen: OfflineScreen? = null
    private var offlineModeChosen = false

    /** True when the app was launched without internet: only Home (cached) is available. */
    private var offlineMode = false


    @kotlin.OptIn(DelicateCoroutinesApi::class)
    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!isTaskRoot && intent.hasCategory(Intent.CATEGORY_LAUNCHER) && intent.action == Intent.ACTION_MAIN) {
            finish()
            return
        }

        // First launch: show the animated intro + permission setup before anything else
        if (!PrefManager.getVal<Boolean>(PrefName.OnboardingDone)) {
            startActivity(
                Intent(this, ani.kiwami.onboarding.OnboardingActivity::class.java)
            )
            finish()
            return
        }

        ThemeManager(this).applyTheme()

        //get FRAGMENT_CLASS_NAME from intent
        val fragment = intent.getStringExtra("FRAGMENT_CLASS_NAME")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!CalcActivity.hasPermission) {
            val pin: String = PrefManager.getVal(PrefName.AppPassword)
            if (pin.isNotEmpty()) {
                ContextCompat.startActivity(
                    this@MainActivity,
                    Intent(this@MainActivity, CalcActivity::class.java)
                        .putExtra("code", pin)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
                    null
                )
                finish()
                return
            }
        }
        TaskScheduler.scheduleSingleWork(this)
        // keep the episode-release alarms armed (cheap; refreshes airing times from AniList)
        ani.kiwami.notifications.airing.AiringAlarmScheduler.restoreAll(applicationContext)

        if (Intent.ACTION_VIEW == intent.action) {
            handleViewIntent(intent)
        }

        val bottomNavBar = findViewById<AnimatedBottomBar>(R.id.navbar)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {

            val backgroundDrawable = bottomNavBar.background as GradientDrawable
            val currentColor = backgroundDrawable.color?.defaultColor ?: 0
            val semiTransparentColor = (currentColor and 0x00FFFFFF) or 0xF9000000.toInt()
            backgroundDrawable.setColor(semiTransparentColor)
            bottomNavBar.background = backgroundDrawable
        }
        bottomNavBar.background = ContextCompat.getDrawable(this, R.drawable.bottom_nav_gray)

        val offset = try {
            val statusBarHeightId = resources.getIdentifier("status_bar_height", "dimen", "android")
            resources.getDimensionPixelSize(statusBarHeightId)
        } catch (e: Exception) {
            statusBarHeight
        }
        val layoutParams = binding.incognito.layoutParams as ViewGroup.MarginLayoutParams
        layoutParams.topMargin = 11 * offset / 12
        binding.incognito.layoutParams = layoutParams

        incognitoLiveData = PrefManager.getLiveVal(
            PrefName.Incognito,
            false
        ).asLiveBool()
        incognitoLiveData.observe(this) {
            if (it) {
                val slideDownAnim = ObjectAnimator.ofFloat(
                    binding.incognito,
                    View.TRANSLATION_Y,
                    -(200f + statusBarHeight),
                    0f
                )
                slideDownAnim.duration = 200
                slideDownAnim.start()
                binding.incognito.visibility = View.VISIBLE
            } else {
                val slideUpAnim = ObjectAnimator.ofFloat(
                    binding.incognito,
                    View.TRANSLATION_Y,
                    0f,
                    -(200f + statusBarHeight)
                )
                slideUpAnim.duration = 200
                slideUpAnim.start()
                Handler(Looper.getMainLooper()).postDelayed({
                    binding.incognito.visibility = View.GONE
                }, 200)
            }
        }

        incognitoNotification(this)

        var doubleBackToExitPressedOnce = false
        onBackPressedDispatcher.addCallback(this) {
            if (doubleBackToExitPressedOnce) {
                finish()
            }
            doubleBackToExitPressedOnce = true
            snackString(this@MainActivity.getString(R.string.back_to_exit)).apply {
                this?.addCallback(object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                        super.onDismissed(transientBottomBar, event)
                        doubleBackToExitPressedOnce = false
                    }
                })
            }
        }

        binding.root.isMotionEventSplittingEnabled = false

        lifecycleScope.launch {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                val splash = SplashScreenBinding.inflate(layoutInflater)
                binding.root.addView(splash.root)
                (splash.splashImage.drawable as Animatable).start()

                delay(200)

                ObjectAnimator.ofFloat(
                    splash.root,
                    View.TRANSLATION_Y,
                    0f,
                    -splash.root.height.toFloat()
                ).apply {
                    interpolator = AnticipateInterpolator()
                    duration = 200L
                    doOnEnd { binding.root.removeView(splash.root) }
                    start()
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener { splashScreenView ->
                ObjectAnimator.ofFloat(
                    splashScreenView,
                    View.TRANSLATION_Y,
                    0f,
                    -splashScreenView.height.toFloat()
                ).apply {
                    interpolator = AnticipateInterpolator()
                    duration = 200L
                    doOnEnd { splashScreenView.remove() }
                    start()
                }
            }
        }


        binding.root.doOnAttach {
            initActivity(this)
            val preferences: SourcePreferences = Injekt.get()
            if (preferences.animeExtensionUpdatesCount()
                    .get() > 0 || preferences.mangaExtensionUpdatesCount().get() > 0
            ) {
                snackString(R.string.extension_updates_available)
                    ?.setDuration(Snackbar.LENGTH_SHORT)
                    ?.setAction(R.string.review) {
                        startActivity(Intent(this, ExtensionsActivity::class.java))
                    }
            }
            window.navigationBarColor = ContextCompat.getColor(this, android.R.color.transparent)
            selectedOption = if (intent.getBooleanExtra("goToHome", false)) {
                1
            } else if (fragment != null) {
                when (fragment) {
                    AnimeFragment::class.java.name -> 0
                    HomeFragment::class.java.name -> 1
                    MangaFragment::class.java.name -> 2
                    else -> 1
                }
            } else {
                PrefManager.getVal(PrefName.DefaultStartUpTab)
            }
            // launched without internet -> no Anime/Manga pages (they would just load forever),
            // the pill becomes an "Offline" badge and only the cached Home page is shown
            offlineMode = !isOnline(this)
            if (offlineMode) selectedOption = 1
            // The token used to be loaded only by loadMain() on a background thread, so the pager
            // could build Home before it was set and fall back to the Login page. Read it right now.
            Anilist.getSavedToken()
            val navbar = binding.includedNavbar.navbar
            if (offlineMode) {
                navbar.visibility = View.GONE
                offlinePill = OfflinePill(this, binding.includedNavbar).also { it.show() }
            } else {
                bottomBar = navbar
                navbar.visibility = View.VISIBLE
            }
            // calendar (left) + downloads (right) circles that flank the pill
            sideButtons = ani.kiwami.home.HomeSideButtons(this, binding.includedNavbar).also {
                it.attach()
                it.reveal()
            }
            binding.mainProgressBar.visibility = View.GONE
            val mainViewPager = binding.viewpager
            mainViewPager.isUserInputEnabled = false
            mainViewPager.adapter =
                ViewPagerAdapter(supportFragmentManager, lifecycle, offlineMode)
            mainViewPager.setPageTransformer(ZoomOutPageTransformer())
            if (!offlineMode) {
                navbar.selectTabAt(selectedOption)
                navbar.setOnTabSelectListener(object :
                    AnimatedBottomBar.OnTabSelectListener {
                    override fun onTabSelected(
                        lastIndex: Int,
                        lastTab: AnimatedBottomBar.Tab?,
                        newIndex: Int,
                        newTab: AnimatedBottomBar.Tab
                    ) {
                        navbar.animate().translationZ(12f).setDuration(200).start()
                        selectedOption = newIndex
                        mainViewPager.setCurrentItem(newIndex, false)
                    }
                })
            }
            val startPage = if (offlineMode) 0 else selectedOption
            if (mainViewPager.currentItem != startPage) {
                mainViewPager.post {
                    mainViewPager.setCurrentItem(
                        startPage,
                        false
                    )
                }
            }
            binding.includedNavbar.navbarContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = navBarHeight
            }
            // offline at launch: ask whether to retry, use the cached Home, or open Downloads.
            // Only once per launch (not again after a rotation / recreate).
            if (offlineMode) {
                if (savedInstanceState?.getBoolean(KEY_OFFLINE_CHOSEN) == true) {
                    showOfflineBanner() // rotated while in Offline mode: keep the banner
                } else if (savedInstanceState == null) {
                    offlineDialog = OfflineLaunchDialog(this) { showOfflineBanner() }
                        .also { it.show() }
                }
            }
        }

        var launched = false
        intent.extras?.let { extras ->
            val fragmentToLoad = extras.getString("FRAGMENT_TO_LOAD")
            val mediaId = extras.getInt("mediaId", -1)
            val commentId = extras.getInt("commentId", -1)
            val activityId = extras.getInt("activityId", -1)

            if (fragmentToLoad != null && mediaId != -1 && commentId != -1) {
                val detailIntent = Intent(this, MediaDetailsActivity::class.java).apply {
                    putExtra("FRAGMENT_TO_LOAD", fragmentToLoad)
                    putExtra("mediaId", mediaId)
                    putExtra("commentId", commentId)
                }
                launched = true
                startActivity(detailIntent)
            } else if (fragmentToLoad == "FEED" && activityId != -1) {
                val feedIntent = Intent(this, FeedActivity::class.java).apply {
                    putExtra("FRAGMENT_TO_LOAD", "NOTIFICATIONS")
                    putExtra("activityId", activityId)
                }
                launched = true
                startActivity(feedIntent)
            } else if (fragmentToLoad == "NOTIFICATIONS" && activityId != -1) {
                Logger.log("MainActivity, onCreate: $activityId")
                val notificationIntent = Intent(this, NotificationActivity::class.java).apply {
                    putExtra("activityId", activityId)
                }
                launched = true
                startActivity(notificationIntent)
            }
        }
        val model: AnilistHomeViewModel by viewModels()

        //Load Data
        if (!load && !launched) {
            scope.launch(Dispatchers.IO) {
                model.loadMain(this@MainActivity)
                val id = intent.extras?.getInt("mediaId", 0)?.takeIf { it != 0 }
                    ?: intent.extras?.getInt("media", 0) ?: 0
                val cont = intent.extras?.getBoolean("continue") ?: false
                val mediaType = intent.extras?.getString("mediaType")
                if (id != 0) {
                    val media = withContext(Dispatchers.IO) {
                        Anilist.query.getMedia(id, mediaType)
                    }
                    if (media != null) {
                        media.cameFromContinue = cont
                        startActivity(
                            Intent(this@MainActivity, MediaDetailsActivity::class.java)
                                .putExtra("media", media as Serializable)
                        )
                    } else {
                        snackString(this@MainActivity.getString(R.string.anilist_not_found))
                    }
                }
                val username = intent.extras?.getString("username")
                if (username != null) {
                    val nameInt = username.toIntOrNull()
                    if (nameInt != null) {
                        startActivity(
                            Intent(this@MainActivity, ProfileActivity::class.java)
                                .putExtra("userId", nameInt)
                        )
                    } else {
                        startActivity(
                            Intent(this@MainActivity, ProfileActivity::class.java)
                                .putExtra("username", username)
                        )
                    }
                }
            }
            load = true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!(PrefManager.getVal(PrefName.AllowOpeningLinks) as Boolean)) {
                CustomBottomDialog.newInstance().apply {
                    title = "Allow Kiwami to automatically open Anilist Links?"
                    val md = "Open settings & click +Add Links & select Anilist urls"
                    addView(TextView(this@MainActivity).apply {
                        val markWon =
                            Markwon.builder(this@MainActivity)
                                .usePlugin(SoftBreakAddsNewLinePlugin.create()).build()
                        markWon.setMarkdown(this, md)
                    })

                    setNegativeButton(this@MainActivity.getString(R.string.no)) {
                        PrefManager.setVal(PrefName.AllowOpeningLinks, true)
                        dismiss()
                    }

                    setPositiveButton(this@MainActivity.getString(R.string.yes)) {
                        PrefManager.setVal(PrefName.AllowOpeningLinks, true)
                        tryWith(true) {
                            startActivity(
                                Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS)
                                    .setData(Uri.parse("package:$packageName"))
                            )
                        }
                        dismiss()
                    }
                }.show(supportFragmentManager, "dialog")
            }
        }
        if (PrefManager.getVal(PrefName.OC)) {
            AudioHelper.run(this, R.raw.audio)
            PrefManager.setVal(PrefName.OC, false)
        }
        val torrentManager = Injekt.get<TorrentServerManager>()
        if (torrentManager.isAvailable() && PrefManager.getVal(PrefName.TorrentEnabled)) {
            launchIO {
                if (!TorrentServerService.isRunning()) {
                    TorrentServerService.start()
                }
            }
        }
    }

    override fun onRestart() {
        super.onRestart()
        window.navigationBarColor = ContextCompat.getColor(this, android.R.color.transparent)
        // back from Downloads / Calendar / etc. while in Offline mode: show a fresh line
        offlineScreen?.nextLine()
    }

    /**
     * "Offline mode" from the launch popup: the cached Home is covered by an empty, fancy offline
     * screen. It is inserted right above the pager, so the pill and the calendar/downloads buttons
     * (in the navbar include, added after it) stay on top and keep working.
     */
    private fun showOfflineBanner() {
        if (offlineScreen != null || !::binding.isInitialized) return
        offlineModeChosen = true
        val root = binding.root as ViewGroup
        val screenBinding = ani.kiwami.databinding.ViewOfflineScreenBinding
            .inflate(layoutInflater, root, false)
        root.addView(screenBinding.root, root.indexOfChild(binding.viewpager) + 1)
        // keep the centred content clear of the pill / buttons at the bottom
        screenBinding.offlineScreenContent.setPadding(
            screenBinding.offlineScreenContent.paddingLeft,
            screenBinding.offlineScreenContent.paddingTop,
            screenBinding.offlineScreenContent.paddingRight,
            (96 * resources.displayMetrics.density).toInt() + navBarHeight
        )
        offlineScreen = OfflineScreen(screenBinding).also { screen ->
            screen.show()
            offlinePill?.onRecoveredChanged = { screen.setRecovered(it) }
            if (isOnline(this)) screen.setRecovered(true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_OFFLINE_CHOSEN, offlineModeChosen)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val margin = if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) 8 else 32
        val params: ViewGroup.MarginLayoutParams =
            binding.includedNavbar.navbar.layoutParams as ViewGroup.MarginLayoutParams
        params.updateMargins(bottom = margin.toPx)
        sideButtons?.syncBottomMargin(margin.toPx)
        binding.includedNavbar.offlinePill.let { pill ->
            pill.layoutParams = (pill.layoutParams as ViewGroup.MarginLayoutParams)
                .apply { updateMargins(bottom = margin.toPx) }
        }
    }

    private fun handleViewIntent(intent: Intent) {
        val uri: Uri? = intent.data
        try {
            if (uri == null) {
                throw Exception("Uri is null")
            }
            val isRepoHost = uri.host == "add-repo" || uri.host == "extension-store"
            val schemeLower = uri.scheme?.lowercase() ?: ""
            if (schemeLower == "magnet" || uri.toString().endsWith(".torrent", ignoreCase = true) || intent.type == "application/x-bittorrent") {
                ani.kiwami.torrent.DirectTorrentBottomSheet.newInstance(uri.toString())
                    .show(supportFragmentManager, "DirectTorrentBottomSheet")
                return
            }
            if (isRepoHost && (schemeLower == "tachiyomi" || schemeLower == "aniyomi" || schemeLower == "novelyomi" || schemeLower == "mihon" || schemeLower == "kiwami")) {
                val url = uri.getQueryParameter("url") ?: throw Exception("No url for repo import")
                val (prefName, name) = when (schemeLower) {
                    "tachiyomi", "mihon" -> PrefName.MangaExtensionRepos to "Manga"
                    "aniyomi" -> PrefName.AnimeExtensionRepos to "Anime"
                    "novelyomi" -> PrefName.NovelExtensionRepos to "Novel"
                    "kiwami" -> {
                        val type = uri.getQueryParameter("type")?.lowercase()
                        when (type) {
                            "anime" -> PrefName.AnimeExtensionRepos to "Anime"
                            "novel" -> PrefName.NovelExtensionRepos to "Novel"
                            else -> PrefName.MangaExtensionRepos to "Manga"
                        }
                    }
                    else -> throw Exception("Invalid scheme")
                }
                val savedRepos: Set<String> = PrefManager.getVal(prefName)
                val newRepos = savedRepos.toMutableSet()
                AddRepositoryBottomSheet.addRepoWarning(this) {
                    newRepos.add(url)
                    PrefManager.setVal(prefName, newRepos)
                    toast("$name Extension Repo added")
                }
                return
            }
            if (uri.scheme.equals("kiwami", ignoreCase = true)) {
                startActivity(Intent(this, ani.kiwami.connections.anilist.UrlMedia::class.java).setData(uri))
                return
            }
            if (intent.type == null) return
            val jsonString =
                contentResolver.openInputStream(uri)?.readBytes()
                    ?: throw Exception("Error reading file")
            val name =
                DocumentFile.fromSingleUri(this, uri)?.name ?: "settings"
            //.sani is encrypted, .ani is not
            if (name.endsWith(".sani")) {
                passwordAlertDialog { password ->
                    if (password != null) {
                        val salt = jsonString.copyOfRange(0, 16)
                        val encrypted = jsonString.copyOfRange(16, jsonString.size)
                        val decryptedJson = try {
                            PreferenceKeystore.decryptWithPassword(
                                password,
                                encrypted,
                                salt
                            )
                        } catch (e: Exception) {
                            toast("Incorrect password")
                            return@passwordAlertDialog
                        }
                        if (PreferencePackager.unpack(decryptedJson)) {
                            val newIntent = Intent(this, this.javaClass)
                            this.finish()
                            startActivity(newIntent)
                        }
                    } else {
                        toast("Password cannot be empty")
                    }
                }
            } else if (name.endsWith(".ani")) {
                val decryptedJson = jsonString.toString(Charsets.UTF_8)
                if (PreferencePackager.unpack(decryptedJson)) {
                    val newIntent = Intent(this, this.javaClass)
                    this.finish()
                    startActivity(newIntent)
                }
            } else {
                toast("Invalid file type")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            toast("Error importing settings")
        }
    }

    private fun passwordAlertDialog(callback: (CharArray?) -> Unit) {
        val password = CharArray(16).apply { fill('0') }

        // Inflate the dialog layout
        val dialogView = DialogUserAgentBinding.inflate(layoutInflater).apply {
            userAgentTextBox.hint = "Password"
            subtitle.visibility = View.VISIBLE
            subtitle.text = getString(R.string.enter_password_to_decrypt_file)
        }
        customAlertDialog().apply {
            setTitle("Enter Password")
            setCustomView(dialogView.root)
            setPosButton(R.string.yes) {
                val editText = dialogView.userAgentTextBox
                if (editText.text?.isNotBlank() == true) {
                    editText.text?.toString()?.trim()?.toCharArray(password)
                    callback(password)
                } else {
                    toast("Password cannot be empty")
                }
            }
            setNegButton(R.string.cancel) {
                password.fill('0')
                callback(null)
            }
            show()
        }
    }

    //ViewPager
    private class ViewPagerAdapter(
        fragmentManager: FragmentManager,
        lifecycle: Lifecycle,
        private val offline: Boolean
    ) : FragmentStateAdapter(fragmentManager, lifecycle) {

        // offline: a single page (Home, served from the local cache)
        override fun getItemCount(): Int = if (offline) 1 else 3

        // Offline and online pages get different ids. Otherwise, after recreate() on reconnect,
        // FragmentStateAdapter restores the old offline Home fragment into page 0 (Anime).
        override fun getItemId(position: Int): Long =
            if (offline) OFFLINE_ITEM_ID else position.toLong()

        override fun containsItem(itemId: Long): Boolean =
            if (offline) itemId == OFFLINE_ITEM_ID else itemId in 0L..2L

        private fun homeOrLogin(): Fragment =
            if (!Anilist.token.isNullOrEmpty() || Anilist.getSavedToken()) HomeFragment() else LoginFragment()

        override fun createFragment(position: Int): Fragment {
            if (offline) return homeOrLogin()
            when (position) {
                0 -> return AnimeFragment()
                1 -> return homeOrLogin()
                2 -> return MangaFragment()
            }
            return LoginFragment()
        }

        private companion object {
            const val OFFLINE_ITEM_ID = 1000L
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("goToHome", false)) {
            selectedOption = 1
            if (offlineMode) {
                binding.viewpager.setCurrentItem(0, false)
            } else {
                binding.includedNavbar.navbar.selectTabAt(1)
                binding.viewpager.setCurrentItem(1, false)
            }
        }
        if (Intent.ACTION_VIEW == intent.action) {
            handleViewIntent(intent)
        }
        handleIncomingExtras(intent)
    }

    private fun handleIncomingExtras(targetIntent: Intent) {
        targetIntent.extras?.let { extras ->
            val fragmentToLoad = extras.getString("FRAGMENT_TO_LOAD")
            val mediaId = extras.getInt("mediaId", -1)
            val commentId = extras.getInt("commentId", -1)
            val activityId = extras.getInt("activityId", -1)

            if (fragmentToLoad != null && mediaId != -1 && commentId != -1) {
                val detailIntent = Intent(this, MediaDetailsActivity::class.java).apply {
                    putExtra("FRAGMENT_TO_LOAD", fragmentToLoad)
                    putExtra("mediaId", mediaId)
                    putExtra("commentId", commentId)
                }
                startActivity(detailIntent)
                return
            } else if (fragmentToLoad == "FEED" && activityId != -1) {
                val feedIntent = Intent(this, FeedActivity::class.java).apply {
                    putExtra("FRAGMENT_TO_LOAD", "NOTIFICATIONS")
                    putExtra("activityId", activityId)
                }
                startActivity(feedIntent)
                return
            } else if (fragmentToLoad == "NOTIFICATIONS" && activityId != -1) {
                val notificationIntent = Intent(this, NotificationActivity::class.java).apply {
                    putExtra("activityId", activityId)
                }
                startActivity(notificationIntent)
                return
            }

            val id = extras.getInt("mediaId", 0).takeIf { it != 0 }
                ?: extras.getInt("media", 0)
            val cont = extras.getBoolean("continue", false)
            val mediaType = extras.getString("mediaType")
            if (id > 0) {
                scope.launch(Dispatchers.IO) {
                    val media = Anilist.query.getMedia(id, mediaType)
                    if (media != null) {
                        media.cameFromContinue = cont
                        startActivity(
                            Intent(this@MainActivity, MediaDetailsActivity::class.java)
                                .putExtra("media", media as java.io.Serializable)
                        )
                    } else {
                        snackString(this@MainActivity.getString(R.string.anilist_not_found))
                    }
                }
            }
            val username = extras.getString("username")
            if (username != null) {
                val nameInt = username.toIntOrNull()
                if (nameInt != null) {
                    startActivity(
                        Intent(this@MainActivity, ProfileActivity::class.java)
                            .putExtra("userId", nameInt)
                    )
                } else {
                    startActivity(
                        Intent(this@MainActivity, ProfileActivity::class.java)
                            .putExtra("username", username)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        offlinePill?.release()
        offlinePill = null
        offlineDialog?.dismiss()
        offlineDialog = null
        offlineScreen?.release()
        offlineScreen = null
        if (::binding.isInitialized) {
            binding.viewpager.adapter = null
        }
        clearBottomBar()
    }
}

private const val KEY_OFFLINE_CHOSEN = "offline_mode_chosen"
