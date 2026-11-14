package nox.kiwami.media.anime.player

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Player
import nox.kiwami.R
import nox.kiwami.connections.discord.Discord
import nox.kiwami.connections.discord.RPC
import nox.kiwami.connections.discord.RPCManager
import nox.kiwami.isOnline
import nox.kiwami.media.Media
import nox.kiwami.media.anime.Episode
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import kotlinx.coroutines.launch

class PlayerDiscordManager(
    private val activity: AppCompatActivity
) {

    fun updatePresence(
        media: Media?,
        episode: Episode?,
        player: Player?,
        isPlaying: Boolean
    ) {
        if (media == null || episode == null || player == null) return
        val context = activity
        val incognito = PrefManager.getVal<Boolean>(PrefName.Incognito)
        val rpcEnabled = PrefManager.getVal<Boolean>(PrefName.rpcEnabled)

        if (RPCManager.shouldSuppressForAdultMedia(media.isAdult)) {
            RPCManager.clearPresence(context)
            return
        }

        if (isOnline(context) && Discord.token != null && !incognito && rpcEnabled) {
            activity.lifecycleScope.launch {
                val buttons = mutableListOf<RPC.Link>()
                buttons.add(RPC.Link("View Anime", "https://anilist.co/anime/${media.id}/"))

                val now = System.currentTimeMillis()
                val currentPosMs = if (player.currentPosition > 0) player.currentPosition else 0L
                val safeDurationMs = if (player.duration > 0 && player.duration != C.TIME_UNSET) {
                    player.duration
                } else {
                    1440000L // default 24 mins
                }

                val isPaused = !isPlaying
                val startTimestamp = if (isPaused) null else now - currentPosMs
                val endTimestamp = if (isPaused) null else (now - currentPosMs) + safeDurationMs

                val stateText = "Episode : ${episode.number}/${media.anime?.totalEpisodes ?: "??"}"
                val finalState = if (isPaused) "Paused - $stateText" else stateText

                val rpcData = RPC.Companion.RPCData(
                    applicationId = Discord.application_Id,
                    type = RPC.Type.WATCHING,
                    activityName = media.userPreferredName,
                    details = episode.title?.takeIf { it.isNotEmpty() }
                        ?: context.getString(R.string.episode_num, episode.number),
                    startTimestamp = startTimestamp,
                    stopTimestamp = endTimestamp,
                    state = finalState,
                    largeImage = media.cover?.let { RPC.Link(media.userPreferredName, it) },
                    smallImage = RPC.Link("Kiwami", Discord.small_Image),
                    buttons = buttons,
                )
                RPCManager.setPresence(context, rpcData)
            }
        }
    }

    fun clear() {
        RPCManager.clearPresence(activity)
    }
}
