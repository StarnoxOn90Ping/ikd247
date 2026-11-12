package nox.kiwami.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import nox.kiwami.R
import nox.kiwami.databinding.ActivitySettingsMangaBinding
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

class SettingsMangaActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsMangaBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        val context = this
        binding = ActivitySettingsMangaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.apply {
            settingsMangaLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                bottomMargin = navBarHeight
            }
            mangaSettingsBack.setOnClickListener {
                onBackPressedDispatcher.onBackPressed()
            }

            var previousChp: View = when (PrefManager.getVal<Int>(PrefName.MangaDefaultView)) {
                0 -> settingsChpList
                1 -> settingsChpCompact
                else -> settingsChpList
            }
            previousChp.alpha = 1f
            fun uiChp(mode: Int, current: View) {
                previousChp.alpha = 0.33f
                previousChp = current
                current.alpha = 1f
                PrefManager.setVal(PrefName.MangaDefaultView, mode)
            }

            settingsChpList.setOnClickListener {
                uiChp(0, it)
            }

            settingsChpCompact.setOnClickListener {
                uiChp(1, it)
            }

            val highlightKey = intent.getStringExtra(nox.kiwami.settings.search.SettingsSearchAdapter.EXTRA_HIGHLIGHT_KEY)
            settingsRecyclerView.adapter = SettingsAdapter(
                arrayListOf(
                    Settings(
                        type = 1,
                        name = getString(R.string.reader_settings),
                        desc = getString(R.string.reader_settings_desc),
                        icon = R.drawable.ic_round_reader_settings,
                        onClick = {
                            startActivity(Intent(context, ReaderSettingsActivity::class.java))
                        },
                        isActivity = true
                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.default_manga_extension),
                        desc = DefaultSourceDialog.summary(context, false),
                        icon = R.drawable.ic_extension,
                        onClick = { item ->
                            DefaultSourceDialog.show(context, false) {
                                item.settingsDesc.text = DefaultSourceDialog.summary(context, false)
                            }
                        }
                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.purge_manga_downloads),
                        desc = getString(R.string.purge_manga_downloads_desc),
                        icon = R.drawable.ic_round_delete_24,
                        onClick = {
                            context.customAlertDialog().apply {
                                setTitle(R.string.purge_manga_downloads)
                                setMessage(R.string.purge_confirm, getString(R.string.manga))
                                setPosButton(R.string.yes, onClick = {
                                    val downloadsManager = Injekt.get<DownloadsManager>()
                                    downloadsManager.purgeDownloads(MediaType.MANGA)
                                })
                                setNegButton(R.string.no)
                                show()
                            }
                        }

                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.purge_novel_downloads),
                        desc = getString(R.string.purge_novel_downloads_desc),
                        icon = R.drawable.ic_round_delete_24,
                        onClick = {
                            context.customAlertDialog().apply {
                                setTitle(R.string.purge_novel_downloads)
                                setMessage(R.string.purge_confirm, getString(R.string.novels))
                                setPosButton(R.string.yes) {
                                    val downloadsManager = Injekt.get<DownloadsManager>()
                                    downloadsManager.purgeDownloads(MediaType.NOVEL)
                                }
                                setNegButton(R.string.no)
                                show()
                            }
                        }
                    ),
                    Settings(
                        type = 2,
                        name = getString(R.string.smart_download_manga),
                        desc = getString(R.string.smart_download_manga_desc),
                        icon = R.drawable.ic_download_24,
                        isChecked = PrefManager.getVal(PrefName.SmartDownloadManga),
                        switch = { isChecked, _ ->
                            PrefManager.setVal(PrefName.SmartDownloadManga, isChecked)
                        }
                    ),
                    Settings(
                        type = 2,
                        name = getString(R.string.include_list),
                        desc = getString(R.string.include_list_desc),
                        icon = R.drawable.view_list_24,
                        isChecked = PrefManager.getVal(PrefName.IncludeMangaList),
                        switch = { isChecked, _ ->
                            PrefManager.setVal(PrefName.IncludeMangaList, isChecked)
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
        }
    }
}
