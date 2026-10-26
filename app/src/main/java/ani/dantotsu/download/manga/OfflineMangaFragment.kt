package ani.dantotsu.download.manga

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.LayoutAnimationController
import android.widget.AbsListView
import android.widget.AutoCompleteTextView
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.marginBottom
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.bottomBarOrNull
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.currActivity
import ani.dantotsu.currContext
import ani.dantotsu.download.DownloadCompat
import ani.dantotsu.download.DownloadCompat.Companion.loadOfflineMangaModelCompat
import ani.dantotsu.download.DownloadedType
import ani.dantotsu.download.OfflineLibraryCache
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.DownloadsManager.Companion.compareName
import ani.dantotsu.download.DownloadsManager.Companion.getSubDirectory
import ani.dantotsu.download.findValidName
import ani.dantotsu.getThemeColor
import ani.dantotsu.initActivity
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.media.MediaType
import ani.dantotsu.navBarHeight
import ani.dantotsu.setSafeOnClickListener
import ani.dantotsu.settings.SettingsDialogFragment
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import ani.dantotsu.util.customAlertDialog
import com.anggrayudi.storage.file.openInputStream
import com.google.android.material.card.MaterialCardView
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SChapterImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class OfflineMangaFragment : Fragment(), OfflineMangaSearchListener {

    private val downloadManager = Injekt.get<DownloadsManager>()
    private var downloads: List<OfflineMangaModel> = listOf()
    private lateinit var gridView: GridView
    private lateinit var emptyState: ani.dantotsu.offline.OfflineEmptyState
    private lateinit var adapter: OfflineMangaAdapter
    private lateinit var total: TextView
    private var downloadsJob: Job = Job()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_offline_page, container, false)

        val textInputLayout = view.findViewById<TextInputLayout>(R.id.offlineMangaSearchBar)
        textInputLayout.hint = "Manga"
        val currentColor = textInputLayout.boxBackgroundColor
        val semiTransparentColor = (currentColor and 0x00FFFFFF) or 0xA8000000.toInt()
        textInputLayout.boxBackgroundColor = semiTransparentColor
        val materialCardView = view.findViewById<MaterialCardView>(R.id.offlineMangaAvatarContainer)

        // Embedded in the Downloads hub: the hub already owns the header/settings, so slim this one down
        if (arguments?.getBoolean(ani.dantotsu.download.hub.DownloadsActivity.ARG_EMBEDDED, false) == true) {
            materialCardView.visibility = View.GONE
            view.findViewById<View>(R.id.animeTitleContainer).apply {
                (layoutParams as ViewGroup.MarginLayoutParams).apply {
                    marginStart = (20 * resources.displayMetrics.density).toInt()
                    marginEnd = (20 * resources.displayMetrics.density).toInt()
                    topMargin = (8 * resources.displayMetrics.density).toInt()
                    bottomMargin = (8 * resources.displayMetrics.density).toInt()
                }
            }
            view.findViewById<View>(R.id.offlineViewRow).apply {
                val pad = (20 * resources.displayMetrics.density).toInt()
                setPadding(pad, paddingTop, pad, paddingBottom)
            }
        }
        materialCardView.setCardBackgroundColor(semiTransparentColor)
        val color = requireContext().getThemeColor(android.R.attr.windowBackground)

        val animeUserAvatar = view.findViewById<ShapeableImageView>(R.id.offlineMangaUserAvatar)
        animeUserAvatar.setSafeOnClickListener {
            val dialogFragment =
                SettingsDialogFragment.newInstance(SettingsDialogFragment.Companion.PageType.MANGA)
            dialogFragment.show((it.context as AppCompatActivity).supportFragmentManager, "dialog")
        }
        if (!(PrefManager.getVal(PrefName.ImmersiveMode) as Boolean) &&
            arguments?.getBoolean(ani.dantotsu.download.hub.DownloadsActivity.ARG_EMBEDDED, false) != true
        ) {
            view.rootView.fitsSystemWindows = true
        }

        textInputLayout.boxBackgroundColor = (color and 0x00FFFFFF) or 0x28000000
        materialCardView.setCardBackgroundColor((color and 0x00FFFFFF) or 0x28000000)

        val searchView = view.findViewById<AutoCompleteTextView>(R.id.animeSearchBarText)
        searchView.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onSearchQuery(s.toString())
            }
        })
        var style: Int = PrefManager.getVal(PrefName.OfflineView)
        val layoutList = view.findViewById<ImageView>(R.id.downloadedList)
        val layoutcompact = view.findViewById<ImageView>(R.id.downloadedGrid)
        var selected = when (style) {
            0 -> layoutList
            1 -> layoutcompact
            else -> layoutList
        }
        selected.alpha = 1f

        fun selected(it: ImageView) {
            selected.alpha = 0.33f
            selected = it
            selected.alpha = 1f
        }

        layoutList.setOnClickListener {
            selected(it as ImageView)
            style = 0
            PrefManager.setVal(PrefName.OfflineView, style)
            gridView.visibility = View.GONE
            gridView.adapter = null
            gridView = view.findViewById(R.id.gridView)
            adapter.notifyNewGrid()
            grid()

        }

        layoutcompact.setOnClickListener {
            selected(it as ImageView)
            style = 1
            PrefManager.setVal(PrefName.OfflineView, style)
            gridView.visibility = View.GONE
            gridView.adapter = null
            gridView = view.findViewById(R.id.gridView1)
            adapter.notifyNewGrid()
            grid()
        }
        gridView =
            if (style == 0) view.findViewById(R.id.gridView) else view.findViewById(R.id.gridView1)
        total = view.findViewById(R.id.total)
        emptyState = ani.dantotsu.offline.OfflineEmptyState(view)

        val swipeRefresh = view.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.localRefresh)
        // canScrollList is exact; canScrollVertically on a GridView can report "at the top" while
        // still a few pixels down, which let a normal scroll turn into a pull-to-refresh
        swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            gridView.canScrollList(-1)
        }
        swipeRefresh.setOnRefreshListener {
            getDownloads(force = true)
            swipeRefresh.isRefreshing = false
        }

        grid()
        return view
    }

    private fun grid() {
        gridView.visibility = View.VISIBLE
        val fadeIn = AlphaAnimation(0f, 1f)
        fadeIn.duration = 300 // animations  pog
        gridView.layoutAnimation = LayoutAnimationController(fadeIn)
        // The adapter (and the list it holds) survives view-style switches, so changing the layout
        // just re-binds what is already loaded instead of reloading every download.
        if (!::adapter.isInitialized) {
            adapter = OfflineMangaAdapter(requireContext(), downloads, this)
            getDownloads()
        } else {
            adapter.notifyNewGrid()
        }
        gridView.adapter = adapter
        gridView.scheduleLayoutAnimation()
        gridView.setOnItemClickListener { _, _, position, _ ->
            // Get the OfflineMangaModel that was clicked
            val item = adapter.getItem(position) as OfflineMangaModel
            val media =
                downloadManager.mangaDownloadedTypes.firstOrNull { it.titleName.compareName(item.title) }
                    ?: downloadManager.novelDownloadedTypes.firstOrNull {
                        it.titleName.compareName(
                            item.title
                        )
                    }
            media?.let {
                lifecycleScope.launch {
                    ContextCompat.startActivity(
                        requireActivity(),
                        Intent(requireContext(), MediaDetailsActivity::class.java)
                            .putExtra("media", getMedia(it))
                            .putExtra("download", true),
                        null
                    )
                }
            } ?: run {
                snackString("no media found")
            }
        }

        gridView.setOnItemLongClickListener { _, _, position, _ ->
            // Get the OfflineMangaModel that was clicked
            val item = adapter.getItem(position) as OfflineMangaModel
            val type: MediaType =
                if (downloadManager.mangaDownloadedTypes.any { it.titleName == item.title }) {
                    MediaType.MANGA
                } else {
                    MediaType.NOVEL
                }
            // Alert dialog to confirm deletion
            requireContext().customAlertDialog().apply {
                setTitle("Delete ${item.title}?")
                setMessage("Are you sure you want to delete ${item.title}?")
                setPosButton(R.string.yes) {
                    downloadManager.removeMedia(item.title, type)
                    getDownloads()
                }
                setNegButton(R.string.no)
            }.show()
            true
        }
    }

    override fun onSearchQuery(query: String) {
        adapter.onSearchQuery(query)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initActivity(requireActivity())

        val scrollTop = view.findViewById<CardView>(R.id.mangaPageScrollTop)
        scrollTop.setOnClickListener {
            gridView.smoothScrollToPositionFromTop(0, 0)
        }

        // Assuming 'scrollTop' is a view that you want to hide/show
        scrollTop.visibility = View.GONE

        gridView.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {
            }

            override fun onScroll(
                view: AbsListView,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int
            ) {
                val first = view.getChildAt(0)
                val visibility = first != null && first.top < 0
                scrollTop.isVisible = visibility
                scrollTop.translationY =
                    -(navBarHeight + (bottomBarOrNull?.height ?: 0) + (bottomBarOrNull?.marginBottom ?: 0)).toFloat()
            }
        })


    }

    override fun onResume() {
        super.onResume()
        getDownloads()
    }


    /**
     * Shows the downloaded manga and novels. Cards that were built before come straight from the
     * cache, so the page appears at once; only titles without a card are loaded (a few at a time, in
     * parallel) and show up as they finish. [force] throws the cache away first (pull-to-refresh).
     */
    private fun getDownloads(force: Boolean = false) {
        if (downloadsJob.isActive) {
            downloadsJob.cancel()
        }
        downloadsJob = Job()
        val appContext = (context ?: currContext())?.applicationContext ?: return
        CoroutineScope(Dispatchers.IO + downloadsJob).launch {
            try {
                OfflineLibraryCache.ensureLoaded(appContext)
                if (force) OfflineLibraryCache.clearManga()
                // manga first, then novels, same order as before
                val entries = (downloadManager.mangaDownloadedTypes + downloadManager.novelDownloadedTypes)
                    .groupBy { it.type to it.titleName.findValidName() }
                    .map { (key, list) -> Triple(key.first.name, key.second, list.first()) }
                val results = ConcurrentHashMap<String, OfflineMangaModel>()
                fun snapshot() = entries.mapNotNull { (type, title, _) ->
                    results["$type|$title"] ?: OfflineLibraryCache.getManga(type, title)
                }

                val missing = entries.filter { (type, title, _) ->
                    OfflineLibraryCache.getManga(type, title) == null
                }
                withContext(Dispatchers.Main) { showDownloads(snapshot(), loading = missing.isNotEmpty()) }
                if (missing.isEmpty()) return@launch

                val permits = Semaphore(6)
                var lastPush = SystemClock.uptimeMillis()
                coroutineScope {
                    missing.map { (type, title, download) ->
                        async {
                            permits.withPermit {
                                results["$type|$title"] = OfflineLibraryCache.mangaOrLoad(type, title) {
                                    loadOfflineMangaModel(download)
                                }
                            }
                            val now = SystemClock.uptimeMillis()
                            if (now - lastPush > 300) {
                                lastPush = now
                                withContext(Dispatchers.Main) { showDownloads(snapshot(), loading = true) }
                            }
                        }
                    }.awaitAll()
                }
                withContext(Dispatchers.Main) { showDownloads(snapshot(), loading = false) }
                OfflineLibraryCache.persist(appContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.log(e)
            }
        }
    }

    private fun showDownloads(list: List<OfflineMangaModel>, loading: Boolean) {
        if (!isAdded || view == null) return
        // unchanged list: leave the grid alone so scroll position and images aren't disturbed
        if (list != downloads) {
            downloads = list
            adapter.setItems(list)
        }
        total.text = if (list.isNotEmpty()) "Manga and Novels (${list.size})" else "Empty List"
        when {
            list.isNotEmpty() || loading -> emptyState.hide()
            else -> emptyState.show(
                R.drawable.ic_round_library_books_24,
                "No downloaded manga or novels",
                "Chapters you download will appear here for offline reading."
            )
        }
    }

    /**
     * Load media.json file from the directory and convert it to Media class
     * @param downloadedType DownloadedType object
     * @return Media object
     */
    private suspend fun getMedia(downloadedType: DownloadedType): Media? {
        return try {
            val directory = getSubDirectory(
                context ?: currContext()!!, downloadedType.type,
                false, downloadedType.titleName
            )
            val gson = GsonBuilder()
                .registerTypeAdapter(SChapter::class.java, InstanceCreator<SChapter> {
                    SChapterImpl()
                })
                .create()
            val media = directory?.findFile("media.json")
            if (media == null) {
                Logger.log("No media.json found at ${directory?.uri?.path}")
                return DownloadCompat.loadMediaCompat(downloadedType)
            }
            val mediaJson =
                media.openInputStream(context ?: currContext()!!)?.bufferedReader().use {
                    it?.readText()
                }
            gson.fromJson(mediaJson, Media::class.java)
        } catch (e: Exception) {
            Logger.log("Error loading media.json: ${e.message}")
            Logger.log(e)
            Injekt.get<CrashlyticsInterface>().logException(e)
            null
        }
    }

    private suspend fun loadOfflineMangaModel(downloadedType: DownloadedType): OfflineMangaModel {
        val type = downloadedType.type.asText()
        try {
            val directory = getSubDirectory(
                context ?: currContext()!!, downloadedType.type,
                false, downloadedType.titleName
            )
            val mediaModel = getMedia(downloadedType)!!
            val cover = directory?.findFile("cover.jpg")
            val coverUri: Uri? = if (cover?.exists() == true) {
                cover.uri
            } else null
            val banner = directory?.findFile("banner.jpg")
            val bannerUri: Uri? = if (banner?.exists() == true) {
                banner.uri
            } else null
            if (coverUri == null && bannerUri == null) throw Exception("No cover or banner found, probably compat")
            val title = mediaModel.mainName()
            val score = ((if (mediaModel.userScore == 0) (mediaModel.meanScore
                ?: 0) else mediaModel.userScore) / 10.0).toString()
            val isOngoing =
                mediaModel.status == (context ?: currContext())?.getString(R.string.status_releasing)
            val isUserScored = mediaModel.userScore != 0
            val readChapter = (mediaModel.userProgress ?: "~").toString()
            val totalChapter = "${mediaModel.manga?.totalChapters ?: "??"}"
            val chapters = " Chapters"
            return OfflineMangaModel(
                title,
                score,
                totalChapter,
                readChapter,
                type,
                chapters,
                isOngoing,
                isUserScored,
                coverUri,
                bannerUri
            )
        } catch (e: Exception) {
            Logger.log(e)
            return try {
                loadOfflineMangaModelCompat(downloadedType)
            } catch (e: Exception) {
                Logger.log("Error loading media.json: ${e.message}")
                Logger.log(e)
                Injekt.get<CrashlyticsInterface>().logException(e)
                return OfflineMangaModel(
                    downloadedType.titleName,
                    "0",
                    "??",
                    "??",
                    "movie",
                    "hmm",
                    isOngoing = false,
                    isUserScored = false,
                    null,
                    null
                )
            }
        }
    }
}

interface OfflineMangaSearchListener {
    fun onSearchQuery(query: String)
}
