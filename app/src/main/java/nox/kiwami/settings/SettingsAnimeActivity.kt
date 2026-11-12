package nox.kiwami.settings

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import nox.kiwami.R
import nox.kiwami.databinding.ActivitySettingsAnimeBinding
import nox.kiwami.download.DownloadsManager
import nox.kiwami.initActivity
import nox.kiwami.media.MediaType
import nox.kiwami.navBarHeight
import nox.kiwami.restartApp
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import nox.kiwami.statusBarHeight
import nox.kiwami.themes.ThemeManager
import nox.kiwami.util.customAlertDialog
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SettingsAnimeActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsAnimeBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        val context = this
        binding = ActivitySettingsAnimeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.apply {

            settingsAnimeLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                bottomMargin = navBarHeight
            }
            animeSettingsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
            val highlightKey = intent.getStringExtra(nox.kiwami.settings.search.SettingsSearchAdapter.EXTRA_HIGHLIGHT_KEY)
            settingsRecyclerView.adapter = SettingsAdapter(
                arrayListOf(
                    Settings(
                        type = 1,
                        name = getString(R.string.player_settings),
                        desc = getString(R.string.player_settings_desc),
                        icon = R.drawable.ic_round_video_settings_24,
                        onClick = {
                            startActivity(Intent(context, PlayerSettingsActivity::class.java))
                        },
                        isActivity = true
                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.purge_anime_downloads),
                        desc = getString(R.string.purge_anime_downloads_desc),
                        icon = R.drawable.ic_round_delete_24,
                        onClick = {
                            context.customAlertDialog().apply {
                                setTitle(R.string.purge_anime_downloads)
                                setMessage(R.string.purge_confirm, getString(R.string.anime))
                                setPosButton(R.string.yes, onClick = {
                                    val downloadsManager = Injekt.get<DownloadsManager>()
                                    downloadsManager.purgeDownloads(MediaType.ANIME)
                                })
                                setNegButton(R.string.no)
                                show()
                            }
                        }

                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.default_anime_extension),
                        desc = DefaultSourceDialog.summary(context, true),
                        icon = R.drawable.ic_extension,
                        onClick = { item ->
                            DefaultSourceDialog.show(context, true) {
                                item.settingsDesc.text = DefaultSourceDialog.summary(context, true)
                            }
                        }
                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.preferred_download_resolutions),
                        desc = (PrefManager.getVal<List<String>>(PrefName.PreferredDownloadResolutions)).joinToString(", "),
                        icon = R.drawable.ic_round_high_quality_24,
                        onClick = {
                            ResolutionPriorityDialog.show(context, null) {
                                (settingsRecyclerView.adapter as? SettingsAdapter)?.notifyDataSetChanged()
                            }
                        }
                    ),
                    Settings(
                        type = 2,
                        name = getString(R.string.auto_select_server_title),
                        desc = getString(R.string.auto_select_server_desc),
                        icon = R.drawable.ic_round_auto_awesome_24,
                        isChecked = PrefManager.getVal(PrefName.AutoSelectServer),
                        switch = { isChecked, _ ->
                            PrefManager.setVal(PrefName.AutoSelectServer, isChecked)
                        }
                    ),
                    Settings(
                        type = 2,
                        name = getString(R.string.smart_download_anime),
                        desc = getString(R.string.smart_download_anime_desc),
                        icon = R.drawable.ic_download_24,
                        isChecked = PrefManager.getVal(PrefName.SmartDownloadAnime),
                        switch = { isChecked, _ ->
                            PrefManager.setVal(PrefName.SmartDownloadAnime, isChecked)
                        }
                    ),
                    Settings(
                        type = 2,
                        name = getString(R.string.prefer_dub),
                        desc = getString(R.string.prefer_dub_desc),
                        icon = R.drawable.ic_round_audiotrack_24,
                        isChecked = PrefManager.getVal(PrefName.SettingsPreferDub),
                        switch = { isChecked, _ ->
                            PrefManager.setVal(PrefName.SettingsPreferDub, isChecked)
                        }
                    ),
                    Settings(
                        type = 2,
                        name = getString(R.string.show_yt),
                        desc = getString(R.string.show_yt_desc),
                        icon = R.drawable.ic_round_play_circle_24,
                        isChecked = PrefManager.getVal(PrefName.ShowYtButton),
                        switch = { isChecked, _ ->
                            PrefManager.setVal(PrefName.ShowYtButton, isChecked)
                        }
                    ),
                    Settings(
                        type = 2,
                        name = getString(R.string.include_list),
                        desc = getString(R.string.include_list_anime_desc),
                        icon = R.drawable.view_list_24,
                        isChecked = PrefManager.getVal(PrefName.IncludeAnimeList),
                        switch = { isChecked, _ ->
                            PrefManager.setVal(PrefName.IncludeAnimeList, isChecked)
                            restartApp()
                        }
                    ),
                ),
                highlightKey = highlightKey
            )
            settingsRecyclerView.apply {
                layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
                setHasFixedSize(true)
            }

            var previousEp: View = when (PrefManager.getVal<Int>(PrefName.AnimeDefaultView)) {
                0 -> settingsEpList
                1 -> settingsEpGrid
                2 -> settingsEpCompact
                else -> settingsEpList
            }
            previousEp.alpha = 1f
            fun uiEp(mode: Int, current: View) {
                previousEp.alpha = 0.33f
                previousEp = current
                current.alpha = 1f
                PrefManager.setVal(PrefName.AnimeDefaultView, mode)
            }

            settingsEpList.setOnClickListener {
                uiEp(0, it)
            }

            settingsEpGrid.setOnClickListener {
                uiEp(1, it)
            }

            settingsEpCompact.setOnClickListener {
                uiEp(2, it)
            }

        }
    }
}

object ResolutionPriorityDialog {
    val ALL_RESOLUTIONS = listOf("2160p", "1440p", "1080p", "720p", "480p", "360p", "240p", "144p")

    private fun number(res: String) = Regex("""\d+""").find(res)?.value?.toIntOrNull() ?: 0

    /** Saved order first; resolutions missing from it are slotted in by size (never dumped last). */
    private fun buildList(saved: List<String>): MutableList<String> {
        val list = saved.toMutableList()
        ALL_RESOLUTIONS.filter { it !in list }.forEach { missing ->
            val idx = list.indexOfFirst { number(it) < number(missing) }
            if (idx >= 0) list.add(idx, missing) else list.add(missing)
        }
        return list
    }

    fun show(
        context: android.content.Context,
        extensionName: String? = null,
        onUpdated: ((List<String>) -> Unit)? = null
    ) {
        val items = buildList(PrefManager.getPreferredDownloadResolutions(extensionName))

        val listAdapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<ResViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ResViewHolder(
                android.view.LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_resolution_priority, parent, false)
            )

            override fun getItemCount() = items.size

            override fun onBindViewHolder(holder: ResViewHolder, position: Int) {
                holder.position.text = "${position + 1}."
                holder.name.text = items[position]
            }
        }

        val touchHelper = androidx.recyclerview.widget.ItemTouchHelper(
            object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                androidx.recyclerview.widget.ItemTouchHelper.UP or
                        androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0
            ) {
                // long press anywhere on a row starts the drag
                override fun isLongPressDragEnabled() = true

                override fun onMove(
                    recyclerView: androidx.recyclerview.widget.RecyclerView,
                    viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                    target: androidx.recyclerview.widget.RecyclerView.ViewHolder
                ): Boolean {
                    val from = viewHolder.bindingAdapterPosition
                    val to = target.bindingAdapterPosition
                    if (from < 0 || to < 0) return false
                    items.add(to, items.removeAt(from))
                    listAdapter.notifyItemMoved(from, to)
                    return true
                }

                override fun onSwiped(
                    viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                    direction: Int
                ) {
                }

                override fun onSelectedChanged(
                    viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder?,
                    actionState: Int
                ) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG) {
                        viewHolder?.itemView?.alpha = 0.6f
                    }
                }

                override fun clearView(
                    recyclerView: androidx.recyclerview.widget.RecyclerView,
                    viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder
                ) {
                    super.clearView(recyclerView, viewHolder)
                    viewHolder.itemView.alpha = 1f
                    // refresh the 1. 2. 3. numbers once the item is dropped
                    recyclerView.post { listAdapter.notifyDataSetChanged() }
                }
            })

        val recycler = androidx.recyclerview.widget.RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = listAdapter
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        touchHelper.attachToRecyclerView(recycler)

        val density = context.resources.displayMetrics.density
        val hint = android.widget.TextView(context).apply {
            setText(R.string.resolution_priority_hint)
            textSize = 13f
            setTextColor(
                androidx.core.content.ContextCompat.getColor(context, android.R.color.darker_gray)
            )
        }
        val content = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, (8 * density).toInt(), pad, 0)
            addView(hint)
            addView(
                recycler,
                android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * density).toInt() }
            )
        }
        val scroll = android.widget.ScrollView(context).apply { addView(content) }

        context.customAlertDialog().apply {
            setTitle(R.string.preferred_download_resolutions)
            setCustomView(scroll)
            setPosButton(R.string.ok) {
                val toSave = items.toList()
                PrefManager.setPreferredDownloadResolutions(extensionName, toSave)
                onUpdated?.invoke(toSave)
            }
            setNeutralButton(R.string.reset) {
                PrefManager.setPreferredDownloadResolutions(extensionName, ALL_RESOLUTIONS)
                onUpdated?.invoke(ALL_RESOLUTIONS)
            }
            setNegButton(R.string.cancel)
            show()
        }
    }

    private class ResViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val position: android.widget.TextView = view.findViewById(R.id.resolutionPosition)
        val name: android.widget.TextView = view.findViewById(R.id.resolutionName)
    }
}

/** "Default extension" picker shared by the anime and manga settings pages. */
object DefaultSourceDialog {
    fun show(
        context: android.content.Context,
        anime: Boolean,
        onUpdated: () -> Unit
    ) {
        val pref = if (anime) PrefName.DefaultAnimeSource else PrefName.DefaultMangaSource
        val special = setOf("Torrent", "Local", "Downloaded")
        val names = (if (anime) nox.kiwami.parsers.AnimeSources.list else nox.kiwami.parsers.MangaSources.list)
            .map { it.name }
            .filter { it !in special }
        val current = PrefManager.getVal<String>(pref)
        val items = (listOf(context.getString(R.string.default_extension_none)) + names).toTypedArray()
        val selected = names.indexOf(current).let { if (it < 0) 0 else it + 1 }
        context.customAlertDialog().apply {
            setTitle(if (anime) R.string.default_anime_extension else R.string.default_manga_extension)
            singleChoiceItems(items, selected) { which ->
                PrefManager.setVal(pref, if (which == 0) "" else names[which - 1])
                onUpdated()
            }
            setNegButton(R.string.cancel)
            show()
        }
    }

    fun summary(context: android.content.Context, anime: Boolean): String {
        val name = PrefManager.getVal<String>(if (anime) PrefName.DefaultAnimeSource else PrefName.DefaultMangaSource)
        return name.ifBlank { context.getString(R.string.default_extension_none) }
    }
}
