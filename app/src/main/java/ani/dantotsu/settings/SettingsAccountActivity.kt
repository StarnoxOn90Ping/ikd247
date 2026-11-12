package ani.dantotsu.settings

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.discord.Discord
import ani.dantotsu.databinding.ActivitySettingsAccountsBinding
import ani.dantotsu.initActivity
import ani.dantotsu.loadImage
import ani.dantotsu.navBarHeight
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.startMainActivity
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import kotlinx.coroutines.launch

class SettingsAccountActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsAccountsBinding
    private var highlightKey: String? = null
    private val restartMainActivity = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = startMainActivity(this@SettingsAccountActivity)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)

        binding = ActivitySettingsAccountsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        highlightKey =
            intent.getStringExtra(ani.dantotsu.settings.search.SettingsSearchAdapter.EXTRA_HIGHLIGHT_KEY)

        binding.apply {
            settingsAccountsLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                bottomMargin = navBarHeight
            }
            accountSettingsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
            settingsRecyclerView.layoutManager =
                LinearLayoutManager(this@SettingsAccountActivity, LinearLayoutManager.VERTICAL, false)
        }

        refreshPills()
        refreshSettingsList()

        // Older Discord logins never saved a banner, and avatars/banners can change,
        // so quietly refresh the profile and redraw the pill when new data arrives.
        if (Discord.token != null) {
            lifecycleScope.launch {
                if (Discord.refreshProfile()) refreshPills()
            }
        }
    }

    private fun refreshPills() {
        val context = this
        binding.apply {
            // ---------- AniList ----------
            if (Anilist.token != null) {
                settingsAnilistLogin.setText(R.string.logout)
                settingsAnilistLogin.setOnClickListener {
                    Anilist.removeSavedToken()
                    restartMainActivity.isEnabled = true
                    refreshPills()
                    refreshSettingsList()
                }
                settingsAnilistUsername.visibility = View.VISIBLE
                settingsAnilistUsername.text = Anilist.username
                settingsAnilistAvatar.loadImage(Anilist.avatar)
                settingsAnilistAvatar.setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    val anilistLink = getString(
                        R.string.anilist_link,
                        PrefManager.getVal<String>(PrefName.AnilistUserName)
                    )
                    openLinkInBrowser(anilistLink)
                }

                if (Anilist.bg != null) {
                    settingsAnilistBanner.visibility = View.VISIBLE
                    settingsAnilistScrim.visibility = View.VISIBLE
                    settingsAnilistBanner.loadImage(Anilist.bg)
                } else {
                    settingsAnilistBanner.visibility = View.GONE
                    settingsAnilistScrim.visibility = View.GONE
                }
            } else {
                settingsAnilistAvatar.setImageResource(R.drawable.ic_round_person_24)
                settingsAnilistAvatar.setOnClickListener(null)
                settingsAnilistUsername.visibility = View.GONE
                settingsAnilistBanner.visibility = View.GONE
                settingsAnilistScrim.visibility = View.GONE
                settingsAnilistLogin.setText(R.string.login)
                settingsAnilistLogin.setOnClickListener {
                    Anilist.loginIntent(context)
                }
            }

            // ---------- Discord ----------
            if (Discord.token != null) {
                val id = PrefManager.getVal(PrefName.DiscordId, null as String?)
                val avatar = PrefManager.getVal(PrefName.DiscordAvatar, null as String?)
                val banner = PrefManager.getVal(PrefName.DiscordBanner, null as String?)
                val bannerColor = PrefManager.getVal(PrefName.DiscordBannerColor, null as String?)
                val username = PrefManager.getVal(PrefName.DiscordUserName, null as String?)

                settingsDiscordLogo.visibility = View.VISIBLE
                settingsDiscordUsername.visibility = View.VISIBLE
                settingsDiscordUsername.text =
                    username?.takeIf { it.isNotBlank() } ?: getString(R.string.username)
                settingsDiscordLogin.setText(R.string.logout)
                settingsDiscordLogin.setOnClickListener {
                    Discord.removeSavedToken(context)
                    restartMainActivity.isEnabled = true
                    refreshPills()
                    refreshSettingsList()
                }

                // Profile picture (falls back to the default placeholder until the profile is fetched)
                val avatarUrl = Discord.avatarUrl(id, avatar)
                if (avatarUrl != null) {
                    ImageViewCompat.setImageTintList(settingsDiscordAvatar, null)
                    settingsDiscordAvatar.scaleType = ImageView.ScaleType.CENTER_CROP
                    settingsDiscordAvatar.loadImage(avatarUrl)
                    settingsDiscordAvatar.setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        openLinkInBrowser(getString(R.string.discord_link, id))
                    }
                } else {
                    showAvatarPlaceholder()
                    settingsDiscordAvatar.setOnClickListener(null)
                }

                // Banner: image if there is one, otherwise the banner colour
                val bannerUrl = Discord.bannerUrl(id, banner)
                val color = bannerColor?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { Color.parseColor(it) }.getOrNull() }
                when {
                    bannerUrl != null -> {
                        settingsDiscordBannerColor.visibility = View.GONE
                        settingsDiscordBanner.visibility = View.VISIBLE
                        settingsDiscordScrim.visibility = View.VISIBLE
                        settingsDiscordBanner.loadImage(bannerUrl)
                    }

                    color != null -> {
                        settingsDiscordBanner.visibility = View.GONE
                        settingsDiscordBannerColor.setBackgroundColor(color)
                        settingsDiscordBannerColor.visibility = View.VISIBLE
                        settingsDiscordScrim.visibility = View.VISIBLE
                    }

                    else -> {
                        settingsDiscordBanner.visibility = View.GONE
                        settingsDiscordBannerColor.visibility = View.GONE
                        settingsDiscordScrim.visibility = View.GONE
                    }
                }
            } else {
                // Logged out: same as the AniList pill - small logo, "Login", default avatar
                settingsDiscordLogo.visibility = View.VISIBLE
                settingsDiscordUsername.visibility = View.GONE
                settingsDiscordBanner.visibility = View.GONE
                settingsDiscordBannerColor.visibility = View.GONE
                settingsDiscordScrim.visibility = View.GONE
                showAvatarPlaceholder()
                settingsDiscordAvatar.setOnClickListener(null)
                settingsDiscordLogin.setText(R.string.login)
                settingsDiscordLogin.setOnClickListener {
                    Discord.warning(context).show(supportFragmentManager, "dialog")
                }
            }
        }
    }

    private fun showAvatarPlaceholder() {
        binding.settingsDiscordAvatar.apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(R.drawable.ic_round_person_24)
            ImageViewCompat.setImageTintList(this, null)
        }
    }

    private fun refreshSettingsList() {
        val context = this
        val key = highlightKey
        highlightKey = null // only highlight once, not on every refresh
        binding.settingsRecyclerView.adapter = SettingsAdapter(
            arrayListOf(
                Settings(
                    type = 1,
                    name = getString(R.string.anilist_settings),
                    desc = getString(R.string.alsettings_desc),
                    icon = R.drawable.ic_anilist,
                    onClick = {
                        lifecycleScope.launch {
                            Anilist.query.getUserData()
                            startActivity(Intent(context, AnilistSettingsActivity::class.java))
                        }
                    },
                    isActivity = true,
                    isVisible = Anilist.token != null
                ),
                Settings(
                    type = 1,
                    name = getString(R.string.discord_settings),
                    desc = getString(R.string.discord_settings_desc),
                    icon = R.drawable.ic_discord,
                    onClick = {
                        startActivity(Intent(context, DiscordSettingsActivity::class.java))
                    },
                    isActivity = true
                ),
                Settings(
                    type = 2,
                    name = getString(R.string.comments_button),
                    desc = getString(R.string.comments_button_desc),
                    icon = R.drawable.ic_round_comment_24,
                    isChecked = PrefManager.getVal<Int>(PrefName.CommentsEnabled) == 1,
                    switch = { isChecked, _ ->
                        PrefManager.setVal(PrefName.CommentsEnabled, if (isChecked) 1 else 2)
                        reload()
                    },
                    isVisible = Anilist.token != null
                ),
            ),
            highlightKey = key
        )
    }

    fun reload() {
        snackString(getString(R.string.restart_app_extra))
    }
}
