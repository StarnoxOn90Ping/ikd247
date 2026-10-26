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

/** Sync all pending progress updates to AniList. */
fun syncPendingProgressUpdates() {
    if (Anilist.userid == null) return
    val pending: List<PendingProgressUpdate> = try {
        PrefManager.getVal(PrefName.PendingProgressUpdates, listOf())
    } catch (e: Exception) {
        PrefManager.setVal(PrefName.PendingProgressUpdates, listOf<PendingProgressUpdate>())
        return
    }
    if (pending.isEmpty()) return
    toast(currContext()?.getString(R.string.syncing_progress, pending.size))
    CoroutineScope(Dispatchers.IO).launch {
        val remaining = pending.toMutableList()
        for (update in pending) {
            try {
                val anilistId: Int = update.mediaId
                Anilist.mutation.editList(
                    mediaID = anilistId,
                    progress = update.progress,
                    score = update.score,
                    repeat = update.rewatch,
                    notes = update.notes,
                    status = update.status,
                    private = update.isPrivate,
                    startedAt = update.startDate,
                    completedAt = update.endDate,
                    customList = update.customLists,
                )
                if (!Anilist.anilistDisabledSignal) {
                    remaining.remove(update)
                } else {
                    break
                }
            } catch (_: Exception) {
            }
        }
        PrefManager.setVal(PrefName.PendingProgressUpdates, remaining)
        if (remaining.isEmpty()) {
            toast(currContext()?.getString(R.string.sync_complete))
        } else {
            toast(currContext()?.getString(R.string.sync_partial, remaining.size))
        }
        Refresh.all()
    }
}

// sync changes to anilist in background 
fun syncPendingDeletions() {
    if (Anilist.userid == null) return
    val pending: List<PendingDeletion> = try {
        PrefManager.getVal(PrefName.PendingDeletions, listOf())
    } catch (e: Exception) {
        PrefManager.setVal(PrefName.PendingDeletions, listOf<PendingDeletion>())
        return
    }
    if (pending.isEmpty()) return
    toast(currContext()?.getString(R.string.syncing_deletions, pending.size))
    CoroutineScope(Dispatchers.IO).launch {
        val remaining = pending.toMutableList()
        for (deletion in pending) {
            if (Anilist.anilistDisabledSignal) break
            try {
                val anilistId: Int = deletion.mediaId
                val fakeMedia = emptyMedia().copy(id = anilistId)
                val listId = Anilist.query.userMediaDetails(fakeMedia).userListId
                if (listId != null) {
                    Anilist.mutation.deleteList(listId)
                }
                val removeList = PrefManager.getCustomVal<Set<String>>("removeList", emptySet())
                PrefManager.setCustomVal("removeList", removeList.minus(anilistId.toString()))
                val progressUpdates: List<PendingProgressUpdate> =
                    PrefManager.getVal(PrefName.PendingProgressUpdates, listOf())
                val filteredUpdates = progressUpdates.filterNot { update ->
                    update.mediaId == deletion.mediaId
                }
                if (filteredUpdates.size != progressUpdates.size) {
                    PrefManager.setVal(PrefName.PendingProgressUpdates, filteredUpdates)
                }
                if (!Anilist.anilistDisabledSignal) {
                    remaining.remove(deletion)
                }
            } catch (_: Exception) {
            }
        }
        PrefManager.setVal(PrefName.PendingDeletions, remaining)
        if (remaining.isEmpty()) {
            toast(currContext()?.getString(R.string.sync_complete))
        } else {
            toast(currContext()?.getString(R.string.sync_partial, remaining.size))
        }

        Refresh.all()
    }
}
