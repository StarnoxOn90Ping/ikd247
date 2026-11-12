package ani.kiwami.settings

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import ani.kiwami.R
import ani.kiwami.databinding.ActivitySettingsDiscordBinding
import ani.kiwami.initActivity
import ani.kiwami.navBarHeight
import ani.kiwami.settings.saving.PrefManager
import ani.kiwami.settings.saving.PrefName
import ani.kiwami.settings.search.SettingsSearchAdapter
import ani.kiwami.statusBarHeight
import ani.kiwami.themes.ThemeManager
import ani.kiwami.util.customAlertDialog

class DiscordSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsDiscordBinding

    private val statuses = listOf("online", "idle", "dnd", "invisible")

    private fun statusIcon(status: String) = when (status) {
        "idle" -> R.drawable.discord_status_idle
        "dnd" -> R.drawable.discord_status_dnd
        "invisible" -> R.drawable.discord_status_invisible
        else -> R.drawable.discord_status_online
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivitySettingsDiscordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.apply {
            settingsDiscordLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                bottomMargin = navBarHeight
            }
            discordSettingsBack.setOnClickListener {
                onBackPressedDispatcher.onBackPressed()
            }
        }

        val statusLabels = arrayOf(
            getString(R.string.discord_status_label_online),
            getString(R.string.discord_status_label_idle),
            getString(R.string.discord_status_label_dnd),
            getString(R.string.discord_status_label_invisible)
        )
        val currentStatus = PrefManager.getVal<String>(PrefName.DiscordStatus)
        val currentIndex = statuses.indexOf(currentStatus).coerceAtLeast(0)

        val highlightKey = intent.getStringExtra(SettingsSearchAdapter.EXTRA_HIGHLIGHT_KEY)
        binding.settingsRecyclerView.adapter = SettingsAdapter(
            arrayListOf(
                Settings(
                    type = 2,
                    name = getString(R.string.enable_rpc),
                    desc = getString(R.string.enable_rpc_desc),
                    icon = R.drawable.interests_24,
                    isChecked = PrefManager.getVal(PrefName.rpcEnabled),
                    switch = { isChecked, _ ->
                        PrefManager.setVal(PrefName.rpcEnabled, isChecked)
                    }
                ),
                Settings(
                    type = 1,
                    name = getString(R.string.discord_status_title),
                    desc = statusLabels[currentIndex],
                    icon = statusIcon(statuses[currentIndex]),
                    onClick = { b ->
                        val selected = statuses.indexOf(PrefManager.getVal<String>(PrefName.DiscordStatus))
                            .coerceAtLeast(0)
                        customAlertDialog().apply {
                            setTitle(R.string.discord_status_title)
                            singleChoiceItems(statusLabels, selected) { which ->
                                val status = statuses[which]
                                PrefManager.setVal(PrefName.DiscordStatus, status)
                                b.settingsDesc.text = statusLabels[which]
                                b.settingsIcon.setImageResource(statusIcon(status))
                            }
                            show()
                        }
                    }
                ),
                Settings(
                    type = 1,
                    name = getString(R.string.discord_rpc_display),
                    desc = getString(R.string.discord_rpc_display_desc),
                    icon = R.drawable.ic_discord,
                    onClick = {
                        DiscordDialogFragment().show(supportFragmentManager, "dialog")
                    },
                    isActivity = true
                ),
            ),
            highlightKey = highlightKey
        )
        binding.settingsRecyclerView.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
    }
}
