package ani.dantotsu.download.hub

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityDownloadsBinding
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.anime.OfflineAnimeFragment
import ani.dantotsu.download.findValidName
import ani.dantotsu.download.manga.OfflineMangaFragment
import ani.dantotsu.getThemeColor
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.settings.DownloadQueueActivity
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.fadeInDown
import ani.dantotsu.util.setGradientText
import ani.dantotsu.util.setPressBounce
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Downloads hub – everything the offline mode shows (downloaded anime + manga) behind a
 * swipeable Anime | Manga switch, plus a live banner while something is downloading.
 * The grid/list view options and search live in the embedded pages themselves.
 */
class DownloadsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDownloadsBinding
    private val downloadsManager = Injekt.get<DownloadsManager>()

    private lateinit var bannerArrow: DownloadArrowAnimator
    private var lastAnimeCount = -1
    private var lastMangaCount = -1
    private var currentPage = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initActivity(this)
        binding.downloadsRoot.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
        }
        // the embedded lists scroll behind the system navigation bar otherwise
        binding.downloadsPager.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }

        // fancy heading: gradient text that drops in, plus springy buttons
        binding.downloadsTitle.setGradientText(
            getThemeColor(androidx.appcompat.R.attr.colorPrimary),
            getThemeColor(com.google.android.material.R.attr.colorTertiary)
        )
        binding.downloadsTitle.fadeInDown()
        binding.downloadsSubtitle.fadeInDown(120)
        binding.downloadsBack.setPressBounce()
        binding.downloadsQueueButton.setPressBounce()
        binding.downloadsBanner.setPressBounce(0.97f)

        binding.downloadsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        val openQueue = View.OnClickListener {
            startActivity(Intent(this, DownloadQueueActivity::class.java))
        }
        binding.downloadsQueueButton.setOnClickListener(openQueue)
        binding.downloadsBanner.setOnClickListener(openQueue)

        bannerArrow = DownloadArrowAnimator(
            binding.downloadsBannerIcon,
            binding.downloadsBannerIconGhost,
            24f * resources.displayMetrics.density
        )
        setupPager()
        refreshCounts()
        startLiveUpdates()
    }

    // region pager + segmented switch

    private fun setupPager() {
        val pager = binding.downloadsPager
        pager.adapter = HubAdapter()
        pager.offscreenPageLimit = 1
        // pages fade and shrink slightly as they slide, so the switch feels like depth rather than a slide
        pager.setPageTransformer { page, position ->
            val d = abs(position).coerceAtMost(1f)
            page.alpha = 1f - 0.65f * d
            val scale = 1f - 0.08f * d
            page.scaleX = scale
            page.scaleY = scale
        }

        val startPage = PrefManager.getCustomVal("downloads_hub_last_tab", 0).coerceIn(0, 1)
        currentPage = startPage
        pager.setCurrentItem(startPage, false)

        binding.downloadsTabAnime.setOnClickListener { pager.setCurrentItem(0, true) }
        binding.downloadsTabManga.setOnClickListener { pager.setCurrentItem(1, true) }

        val segment = binding.downloadsSegment
        val thumb = binding.downloadsSegmentThumb
        val inset = (4 * resources.displayMetrics.density)

        fun thumbWidth(): Float = (segment.width - inset * 2) / 2f

        fun place(position: Int, offset: Float) {
            val w = thumbWidth()
            if (w <= 0f) return
            thumb.translationX = (position + offset) * w
            // liquid feel: the thumb stretches while travelling and squashes back into place
            val travel = (position + offset)
            val bump = sin(PI * (travel - floor(travel))).toFloat().coerceAtLeast(0f)
            thumb.scaleX = 1f + 0.22f * bump
            thumb.scaleY = 1f - 0.12f * bump
            // text colours follow the thumb so they cross-fade while swiping
            val progress = (position + offset).coerceIn(0f, 1f)
            tint(progress)
        }

        segment.post {
            thumb.updateLayoutParams<ViewGroup.LayoutParams> { width = thumbWidth().toInt() }
            place(currentPage, 0f)
        }

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                place(position, positionOffset)
            }

            override fun onPageSelected(position: Int) {
                currentPage = position
                PrefManager.setCustomVal("downloads_hub_last_tab", position)
            }
        })
    }

    /** progress 0 = anime selected, 1 = manga selected */
    private fun tint(progress: Float) {
        val on = getThemeColor(com.google.android.material.R.attr.colorOnPrimary)
        val off = getThemeColor(com.google.android.material.R.attr.colorOutline)
        binding.downloadsTabAnime.setTextColor(blend(on, off, progress))
        binding.downloadsTabManga.setTextColor(blend(off, on, progress))
        // the active label grows a little
        val a = 1f + 0.1f * (1f - progress)
        val m = 1f + 0.1f * progress
        binding.downloadsTabAnime.scaleX = a
        binding.downloadsTabAnime.scaleY = a
        binding.downloadsTabManga.scaleX = m
        binding.downloadsTabManga.scaleY = m
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        val r = (android.graphics.Color.red(from) * (1 - t) + android.graphics.Color.red(to) * t).toInt()
        val g = (android.graphics.Color.green(from) * (1 - t) + android.graphics.Color.green(to) * t).toInt()
        val b = (android.graphics.Color.blue(from) * (1 - t) + android.graphics.Color.blue(to) * t).toInt()
        return android.graphics.Color.rgb(r, g, b)
    }

    private inner class HubAdapter : FragmentStateAdapter(supportFragmentManager, lifecycle) {
        override fun getItemCount() = 2
        override fun createFragment(position: Int): Fragment {
            val fragment: Fragment = if (position == 0) OfflineAnimeFragment() else OfflineMangaFragment()
            fragment.arguments = Bundle().apply { putBoolean(ARG_EMBEDDED, true) }
            return fragment
        }
    }

    // endregion

    // region live state

    override fun onResume() {
        super.onResume()
        refreshCounts()
    }

    private fun refreshCounts() {
        val anime = downloadsManager.animeDownloadedTypes.map { it.titleName.findValidName() }.distinct().size
        val manga = downloadsManager.mangaDownloadedTypes.map { it.titleName.findValidName() }.distinct().size
        if (anime == lastAnimeCount && manga == lastMangaCount) return
        lastAnimeCount = anime
        lastMangaCount = manga
        binding.downloadsSubtitle.text = getString(R.string.hub_downloads_subtitle, anime, manga)
        binding.downloadsTabAnime.text = getString(R.string.hub_tab_count, getString(R.string.anime), anime)
        binding.downloadsTabManga.text = getString(R.string.hub_tab_count, getString(R.string.manga), manga)
    }

    private fun startLiveUpdates() {
        lifecycleScope.launch {
            while (true) {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    renderState(DownloadActivityState.snapshot())
                    refreshCounts()
                }
                delay(600)
            }
        }
    }

    private fun renderState(state: DownloadActivityState) {
        val banner = binding.downloadsBanner
        val count = binding.downloadsQueueCount
        if (state.isActive) {
            val pct = state.percent
            binding.downloadsBannerText.text =
                if (pct >= 0) getString(R.string.hub_downloading_pct, state.total, pct)
                else getString(R.string.hub_downloading, state.total)
            if (pct >= 0) {
                binding.downloadsBannerProgress.isIndeterminate = false
                binding.downloadsBannerProgress.setProgressCompat(pct, true)
            } else if (!binding.downloadsBannerProgress.isIndeterminate) {
                binding.downloadsBannerProgress.isIndeterminate = true
            }
            count.text = state.total.toString()
            count.visibility = View.VISIBLE

            if (banner.visibility != View.VISIBLE) {
                banner.alpha = 0f
                banner.visibility = View.VISIBLE
                banner.animate().alpha(1f).setDuration(250).start()
            }
            bannerArrow.start()
        } else {
            count.visibility = View.GONE
            bannerArrow.stop()
            if (banner.visibility == View.VISIBLE) {
                banner.animate().alpha(0f).setDuration(200)
                    .withEndAction { banner.visibility = View.GONE }.start()
            }
        }
    }

    // endregion

    override fun onDestroy() {
        if (::bannerArrow.isInitialized) bannerArrow.stop()
        super.onDestroy()
        if (::binding.isInitialized) binding.downloadsPager.adapter = null
    }

    companion object {
        const val ARG_EMBEDDED = "embedded"
    }
}
