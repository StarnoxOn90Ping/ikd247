package ani.kiwami.connections

import ani.kiwami.R
import ani.kiwami.Refresh
import ani.kiwami.connections.anilist.Anilist
import ani.kiwami.currContext
import ani.kiwami.media.Media
import ani.kiwami.media.emptyMedia
import ani.kiwami.settings.saving.PrefManager
import ani.kiwami.settings.saving.PrefName
import ani.kiwami.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ani.kiwami.download.DownloadsManager
import ani.kiwami.download.DownloadedType
import ani.kiwami.download.findValidName
import ani.kiwami.media.MediaType
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

fun updateProgress(media: Media, number: String) {
    val incognito: Boolean = PrefManager.getVal(PrefName.Incognito)

    val autoDelete = PrefManager.getCustomVal("auto_delete_downloads", false)
    if (autoDelete) {
        val type = if (media.anime != null) MediaType.ANIME else if (media.format == "NOVEL") MediaType.NOVEL else MediaType.MANGA
        val downloadsManager = Injekt.get<DownloadsManager>()
        val downloadedTypes = when (type) {
            MediaType.ANIME -> downloadsManager.animeDownloadedTypes
            MediaType.MANGA -> downloadsManager.mangaDownloadedTypes
            MediaType.NOVEL -> downloadsManager.novelDownloadedTypes
        }
        val targetNum = ani.kiwami.media.MediaNameAdapter.findChapterNumber(number)
        val downloadedType = downloadedTypes.find {
            it.titleName == media.mainName().findValidName() &&
            (it.chapterName == number.findValidName() ||
             (targetNum != null && ani.kiwami.media.MediaNameAdapter.findChapterNumber(it.chapterName) == targetNum))
        }
        if (downloadedType != null) {
            downloadsManager.removeDownload(downloadedType, toast = false) {}
        }
    }

    val progressInt = ani.kiwami.media.MediaNameAdapter.findEpisodeNumber(number)?.toInt()
        ?: number.toFloatOrNull()?.toInt()
        ?: return

    if (!incognito) {
        if (Anilist.userid != null) {
            CoroutineScope(Dispatchers.IO).launch {
                val a = progressInt
                if (a > (media.userProgress ?: -1)) {
                    Anilist.applyLocalProgressChange(media.anime != null, a - (media.userProgress ?: 0))
                    Anilist.mutation.editList(
                        media.id,
                        a,
                        status = if (media.userStatus == "REPEATING") media.userStatus else "CURRENT"
                    )
                    toast(currContext()?.getString(R.string.setting_progress, a))
                    media.userProgress = a
                }
                Anilist.query.invalidateHomePageCache()
                Anilist.query.invalidateUserStatusCache()
                Refresh.all()
            }
        } else {
            toast(currContext()?.getString(R.string.login_anilist_account))
        }
    } else {
        toast("Sneaky sneaky :3")
    }
}
