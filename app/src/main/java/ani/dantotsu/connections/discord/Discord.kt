package ani.dantotsu.connections.discord

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.TextView
import ani.dantotsu.R
import ani.dantotsu.others.CustomBottomDialog
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast
import ani.dantotsu.tryWith
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import java.io.File

object Discord {

    var token: String? = null
    var userid: String? = null
    var avatar: String? = null

    fun getSavedToken(): Boolean {
        token = PrefManager.getVal(PrefName.DiscordToken, null as String?)
        return token != null
    }

    fun saveToken(newToken: String) {
        PrefManager.setVal(PrefName.DiscordToken, newToken)
        token = newToken  // keep in-memory token in sync
    }

    fun removeSavedToken(context: Context) {
        PrefManager.removeVal(PrefName.DiscordToken)
        token = null
        userid = null
        avatar = null
        clearProfile()
        RPCManager.reset()
        tryWith(true) {
            // Clear the actual Discord tokens cached by TokenManager
            val discordDir = File(context.filesDir, "discord")
            if (discordDir.deleteRecursively())
                toast(context.getString(R.string.discord_logout_success))
                
            // Clear WebView cookies and storage on the main thread so auto-login doesn't happen
            Handler(Looper.getMainLooper()).post {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
            }
        }
    }

    private var lastProfileRefresh = 0L
    private const val PROFILE_REFRESH_INTERVAL = 10 * 60 * 1000L

    /** Saves the profile fields of a Discord `/users/@me` response. */
    fun saveProfile(json: JSONObject) {
        fun str(key: String) = if (json.isNull(key)) "" else json.optString(key)
        PrefManager.setVal(PrefName.DiscordId, str("id"))
        PrefManager.setVal(PrefName.DiscordUserName, str("username"))
        PrefManager.setVal(PrefName.DiscordAvatar, str("avatar"))
        PrefManager.setVal(PrefName.DiscordBanner, str("banner"))
        // Users without a banner image (no Nitro) can still have a banner colour
        val color = str("banner_color").ifEmpty {
            if (json.isNull("accent_color")) ""
            else String.format("#%06X", json.optInt("accent_color") and 0xFFFFFF)
        }
        PrefManager.setVal(PrefName.DiscordBannerColor, color)
    }

    private fun clearProfile() {
        PrefManager.removeVal(PrefName.DiscordId)
        PrefManager.removeVal(PrefName.DiscordUserName)
        PrefManager.removeVal(PrefName.DiscordAvatar)
        PrefManager.removeVal(PrefName.DiscordBanner)
        PrefManager.removeVal(PrefName.DiscordBannerColor)
        lastProfileRefresh = 0L
    }

    /**
     * Re-fetches the logged in user's profile (avatar, banner, username).
     * Runs at most every 10 minutes, unless the banner was never fetched (older logins).
     * Returns true when fresh data was saved.
     */
    suspend fun refreshProfile(force: Boolean = false): Boolean {
        val current = token ?: return false
        val neverFetched = PrefManager.getVal(PrefName.DiscordBanner, null as String?) == null
        val now = System.currentTimeMillis()
        if (!force && !neverFetched && now - lastProfileRefresh < PROFILE_REFRESH_INTERVAL) return false
        lastProfileRefresh = now
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("https://discord.com/api/v9/users/@me")
                    .header("Authorization", current)
                    .build()
                val body = DiscordHttpClient.instance.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Failed to fetch user: ${response.code}" }
                    response.body.string()
                }
                saveProfile(JSONObject(body))
                true
            }.getOrElse {
                Logger.log("Discord: profile refresh failed - ${it.message}")
                false
            }
        }
    }

    /** Profile picture URL, falling back to one of Discord's default avatars. */
    fun avatarUrl(id: String?, hash: String?): String? {
        if (id.isNullOrBlank()) return null
        if (hash.isNullOrBlank() || hash == "null") {
            return "https://cdn.discordapp.com/embed/avatars/${((id.toLongOrNull() ?: 0L) shr 22) % 6}.png"
        }
        return "https://cdn.discordapp.com/avatars/$id/$hash.png?size=256"
    }

    /** Banner image URL, or null when the user has no banner image. */
    fun bannerUrl(id: String?, hash: String?): String? {
        if (id.isNullOrBlank() || hash.isNullOrBlank() || hash == "null") return null
        return "https://cdn.discordapp.com/banners/$id/$hash.png?size=600"
    }

    fun warning(context: Context) = CustomBottomDialog().apply {
        title = context.getString(R.string.warning)
        val md = context.getString(R.string.discord_warning)
        addView(TextView(context).apply {
            val markWon =
                Markwon.builder(context).usePlugin(SoftBreakAddsNewLinePlugin.create()).build()
            markWon.setMarkdown(this, md)
        })

        setNegativeButton(context.getString(R.string.cancel)) {
            dismiss()
        }

        setPositiveButton(context.getString(R.string.login)) {
            dismiss()
            loginIntent(context)
        }
    }

    private fun loginIntent(context: Context) {
        val intent = Intent(context, Login::class.java)
        context.startActivity(intent)
    }

    const val application_Id = "1163925779692912771"
    const val small_Image: String =
        "https://cdn.discordapp.com/emojis/1167344924874784828.webp?size=128&quality=lossless"
    const val small_Image_AniList: String =
        "https://anilist.co/img/icons/android-chrome-512x512.png"
    const val small_Image_Simkl: String =
        "https://eu.simkl.in/img_favicon/v2/favicon-192x192.png"
}
