package ani.dantotsu.connections

import ani.dantotsu.R
import ani.dantotsu.Refresh
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.currContext
import ani.dantotsu.media.Media
import ani.dantotsu.media.emptyMedia
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.DownloadedType
import ani.dantotsu.download.findValidName
import ani.dantotsu.media.MediaType
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
        val targetNum = ani.dantotsu.media.MediaNameAdapter.findChapterNumber(number)
        val downloadedType = downloadedTypes.find {
            it.titleName == media.mainName().findValidName() &&
            (it.chapterName == number.findValidName() ||
             (targetNum != null && ani.dantotsu.media.MediaNameAdapter.findChapterNumber(it.chapterName) == targetNum))
        }
        if (downloadedType != null) {
            downloadsManager.removeDownload(downloadedType, toast = false) {}
        }
    }

    val progressInt = ani.dantotsu.media.MediaNameAdapter.findEpisodeNumber(number)?.toInt()
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
