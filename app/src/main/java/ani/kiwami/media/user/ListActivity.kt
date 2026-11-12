package ani.kiwami.media.user

import android.os.Bundle
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.InputMethodManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import ani.kiwami.R
import ani.kiwami.Refresh
import ani.kiwami.databinding.ActivityListBinding
import ani.kiwami.getThemeColor
import ani.kiwami.hideSystemBarsExtendView
import ani.kiwami.settings.saving.PrefManager
import ani.kiwami.settings.saving.PrefName
import ani.kiwami.navBarHeight
import ani.kiwami.statusBarHeight
import ani.kiwami.themes.ThemeManager
import ani.kiwami.util.fadeInDown
import ani.kiwami.util.setGradientText
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class ListActivity : AppCompatActivity() {
    private lateinit var binding: ActivityListBinding
    private val scope = lifecycleScope
    private var selectedTabIdx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        binding = ActivityListBinding.inflate(layoutInflater)

        val primaryColor = getThemeColor(com.google.android.material.R.attr.colorSurface)
        val primaryTextColor = getThemeColor(androidx.appcompat.R.attr.colorPrimary)
        val secondaryTextColor = getThemeColor(com.google.android.material.R.attr.colorOutline)

        window.statusBarColor = primaryColor
        window.navigationBarColor = primaryColor
        binding.listed.visibility = View.GONE
        binding.listTabLayout.setBackgroundColor(primaryColor)
        binding.listAppBar.setBackgroundColor(primaryColor)
        binding.listTitle.setTextColor(primaryTextColor)
        binding.listTabLayout.setTabTextColors(secondaryTextColor, primaryTextColor)
        binding.listTabLayout.setSelectedTabIndicatorColor(primaryTextColor)
        // the stock tab strip is replaced by the floating carousel at the bottom; it stays only as the
        // bookkeeping that keeps the pager and the selected category in sync
        binding.listTabLayout.visibility = View.GONE
        if (!PrefManager.getVal<Boolean>(PrefName.ImmersiveMode)) {
            this.window.statusBarColor =
                ContextCompat.getColor(this, R.color.nav_bg_inv)
            binding.root.fitsSystemWindows = true

        } else {
            binding.root.fitsSystemWindows = false
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            hideSystemBarsExtendView()
            binding.settingsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
            }
        }
        setContentView(binding.root)

        val anime = intent.getBooleanExtra("anime", true)
        binding.listTitle.text = getString(
            R.string.user_list, intent.getStringExtra("username"),
            if (anime) getString(R.string.anime) else getString(R.string.manga)
        )
        // heading: line 1 = "name's" (ellipsised if long), line 2 = "Anime List" / "Manga List"
        val owner = intent.getStringExtra("username")?.takeIf { it.isNotBlank() }
        val kind = getString(if (anime) R.string.anime_list else R.string.manga_list)
        binding.listTitle.visibility = View.GONE
        binding.listTitleBig.apply {
            visibility = View.VISIBLE
            text = if (owner != null) getString(R.string.list_owner, owner) else kind
            setGradientText(primaryTextColor, getThemeColor(com.google.android.material.R.attr.colorTertiary))
            fadeInDown()
        }
        if (owner != null) {
            binding.listTitleSub.apply {
                visibility = View.VISIBLE
                text = kind
                fadeInDown(120)
            }
        }
        (binding.listActionsRow as LinearLayout).gravity = Gravity.CENTER

        // floating category carousel
        val density = resources.displayMetrics.density
        val extraBottom = if (PrefManager.getVal<Boolean>(PrefName.ImmersiveMode)) navBarHeight else 0
        binding.listCategoryBar.apply {
            visibility = View.VISIBLE
            updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = (16 * density).toInt() + extraBottom
            }
            translationY = 140 * density
            animate().translationY(0f).setStartDelay(150).setDuration(650)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start()
        }
        binding.listViewPager.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = (96 * density).toInt() + extraBottom
        }
        binding.listCategoryBar.onSelected = { i -> binding.listTabLayout.getTabAt(i)?.select() }
        binding.listTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                this@ListActivity.selectedTabIdx = tab?.position ?: 0
                binding.listCategoryBar.select(tab?.position ?: 0)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        val model: ListViewModel by viewModels()
        model.getLists().observe(this) {
            val defaultKeys = listOf(
                "Reading",
                "Watching",
                "Completed",
                "Paused",
                "Dropped",
                "Planning",
                "Favourites",
                "Rewatching",
                "Rereading",
                "All"
            )
            val userKeys: Array<String> = resources.getStringArray(R.array.keys)

            if (it != null) {
                binding.listProgressBar.visibility = View.GONE
                binding.listViewPager.adapter = ListViewPagerAdapter(it.size, false, this)
                val keys = it.keys.toList()
                    .map { key -> userKeys.getOrNull(defaultKeys.indexOf(key)) ?: key }
                val values = it.values.toList()
                val savedTab = this.selectedTabIdx
                TabLayoutMediator(binding.listTabLayout, binding.listViewPager) { tab, position ->
                    tab.text = "${keys[position]} (${values[position].size})"
                }.attach()
                binding.listViewPager.setCurrentItem(savedTab, false)
                binding.listCategoryBar.setItems(
                    keys.indices.map { i -> "${keys[i]} (${values[i].size})" }, savedTab
                )
            }
        }

        val live = Refresh.activity.getOrPut(this.hashCode()) { MutableLiveData(true) }
        live.observe(this) {
            if (it) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        model.loadLists(
                            anime,
                            intent.getIntExtra("userId", 0)
                        )
                    }
                    live.postValue(false)
                }
            }
        }

        binding.listSort.setOnClickListener {
            val popup = PopupMenu(this, it)
            popup.setOnMenuItemClickListener { item ->
                val sort = when (item.itemId) {
                    R.id.score -> "score"
                    R.id.title -> "title"
                    R.id.updated -> "updatedAt"
                    R.id.release -> "release"
                    else -> null
                }
                PrefManager.setVal(
                    if (anime) PrefName.AnimeListSortOrder else PrefName.MangaListSortOrder,
                    sort ?: ""
                )
                binding.listProgressBar.visibility = View.VISIBLE
                binding.listViewPager.adapter = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        model.loadLists(
                            anime,
                            intent.getIntExtra("userId", 0),
                            sort
                        )
                    }
                }
                true
            }
            popup.inflate(R.menu.list_sort_menu)
            popup.show()
        }

        binding.filter.setOnClickListener {
            val popup = PopupMenu(this, it)
            popup.menu.add(Menu.NONE, 0, Menu.NONE, "All")

            val genres = model.getAllGenres()
            if (genres.isNotEmpty()) {
                val genreSubMenu = popup.menu.addSubMenu("Filter by Genre")
                genres.forEachIndexed { index, genre ->
                    genreSubMenu.add(1, index + 1, Menu.NONE, genre)
                }
            }

            val tags = model.getAllTags()
            if (tags.isNotEmpty()) {
                val tagSubMenu = popup.menu.addSubMenu("Filter by Tag")
                tags.forEachIndexed { index, tag ->
                    tagSubMenu.add(2, index + 10000, Menu.NONE, tag)
                }
            }

            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.groupId) {
                    0 -> model.unfilterLists()
                    1 -> model.filterLists(menuItem.title.toString())
                    2 -> model.filterListsByTag(menuItem.title.toString())
                    else -> {
                        if (menuItem.title == "All") model.unfilterLists()
                    }
                }
                true
            }
            popup.show()
        }

        binding.random.setOnClickListener {
            //get the current tab
            val currentTab =
                binding.listTabLayout.getTabAt(binding.listTabLayout.selectedTabPosition)
            val currentFragment =
                supportFragmentManager.findFragmentByTag("f" + currentTab?.position.toString()) as? ListFragment
            currentFragment?.randomOptionClick()
        }

        binding.search.setOnClickListener {
            toggleSearchView(binding.searchView.isVisible)
            if (!binding.searchView.isVisible) {
                model.unfilterLists()
            }
        }

        binding.searchViewText.addTextChangedListener {
            model.searchLists(binding.searchViewText.text.toString())
        }
    }

    private fun toggleSearchView(isVisible: Boolean) {
        if (isVisible) {
            binding.searchView.visibility = View.GONE
            binding.searchViewText.text.clear()
        } else {
            binding.searchView.visibility = View.VISIBLE
            binding.searchViewText.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.searchViewText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Refresh.activity.remove(this.hashCode())
        if (::binding.isInitialized) {
            binding.listViewPager.adapter = null
        }
    }
}
