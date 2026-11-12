package nox.kiwami.settings

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import nox.kiwami.R
import nox.kiwami.addons.AddonDownloader
import nox.kiwami.addons.download.DownloadAddonManager
import nox.kiwami.torrent.TorrentServerManager
import nox.kiwami.addons.torrent.TorrentServerService
import nox.kiwami.databinding.ActivitySettingsAddonsBinding
import nox.kiwami.databinding.ItemSettingsBinding
import nox.kiwami.initActivity
import nox.kiwami.navBarHeight
import nox.kiwami.settings.Settings
import nox.kiwami.settings.TorrentSettingsActivity
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import nox.kiwami.snackString
import nox.kiwami.statusBarHeight
import nox.kiwami.themes.ThemeManager
import nox.kiwami.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SettingsAddonActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsAddonsBinding
    private val downloadAddonManager: DownloadAddonManager = Injekt.get()
    private val torrentServerManager: TorrentServerManager = Injekt.get()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        val context = this
        binding = ActivitySettingsAddonsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.apply {
            settingsAddonsLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                bottomMargin = navBarHeight
            }

            addonSettingsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

            val settingsList = ArrayList<Settings>()

            val torrentSettingsItem = Settings(
                type = 1,
                name = getString(R.string.torrent_settings),
                desc = getString(R.string.torrent_settings_desc),
                icon = R.drawable.ic_round_settings_24,
                onClick = {
                    context.startActivity(android.content.Intent(context, TorrentSettingsActivity::class.java))
                },
                isActivity = true,
                isVisible = PrefManager.getVal(PrefName.TorrentEnabled)
            )

            /*
            settingsList.add(
                Settings(
                    type = 2,
                    name = getString(R.string.enable_torrent),
                    desc = getString(R.string.enable_torrent_desc),
                    icon = R.drawable.ic_round_dns_24,
                    isChecked = PrefManager.getVal(PrefName.TorrentEnabled),
                    switch = { isChecked, _ ->
                        PrefManager.setVal(PrefName.TorrentEnabled, isChecked)
                        if (isChecked) {
                            lifecycleScope.launchIO {
                                if (!TorrentServerService.isRunning()) {
                                    TorrentServerService.start()
                                }
                            }
                        } else {
                            lifecycleScope.launchIO {
                                if (TorrentServerService.isRunning()) {
                                    TorrentServerService.stop()
                                }
                            }
                        }
                        torrentSettingsItem.isVisible = isChecked
                        settingsRecyclerView.adapter?.notifyItemChanged(1)
                    },
                    isVisible = true
                )
            )
            */
            torrentSettingsItem.isVisible = true
            settingsList.add(torrentSettingsItem)

            val highlightKey = intent.getStringExtra(nox.kiwami.settings.search.SettingsSearchAdapter.EXTRA_HIGHLIGHT_KEY)
            settingsRecyclerView.adapter = SettingsAdapter(settingsList, highlightKey = highlightKey)
            settingsRecyclerView.layoutManager =
                LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)

        }
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadAddonManager.removeListenerAction()
    }

    private fun setStatus(
        view: ItemSettingsBinding,
        context: Context,
        status: String?,
        hasUpdate: Boolean
    ) {
        try {
            when (status) {
                context.getString(R.string.loaded_successfully) -> {
                    view.settingsIconRight.setImageResource(R.drawable.ic_round_delete_24)
                    view.settingsIconRight.rotation = 0f
                    view.settingsDesc.text = context.getString(R.string.installed)
                }

                null -> {
                    view.settingsIconRight.setImageResource(R.drawable.ic_download_24)
                    view.settingsIconRight.rotation = 0f
                    view.settingsDesc.text = context.getString(R.string.not_installed)
                }

                else -> {
                    view.settingsIconRight.setImageResource(R.drawable.ic_round_new_releases_24)
                    view.settingsIconRight.rotation = 0f
                    view.settingsDesc.text = context.getString(R.string.error_msg, status)
                }
            }
            if (hasUpdate) {
                view.settingsIconRight.setImageResource(R.drawable.ic_round_sync_24)
                view.settingsDesc.text = context.getString(R.string.update_addon)
            }
        } catch (e: Exception) {
            Logger.log(e)
        }
    }
}
