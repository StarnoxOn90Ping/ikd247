package ani.dantotsu.download.hub

import ani.dantotsu.download.anime.AnimeServiceDataSingleton
import ani.dantotsu.download.manga.MangaServiceDataSingleton
import ani.dantotsu.download.novel.NovelServiceDataSingleton

/**
 * A cheap, allocation-light snapshot of everything the three downloader services are doing right now.
 * Used by the home screen's downloads button (animation + badge) and by the Downloads hub banner.
 */
data class DownloadActivityState(
    /** queued + running tasks across anime, manga and novels */
    val total: Int,
    /** tasks that are being worked on right now */
    val running: Int,
    /** average percent of the running tasks, or -1 when no task has reported progress yet */
    val percent: Int
) {
    val isActive: Boolean get() = total > 0

    companion object {
        val IDLE = DownloadActivityState(0, 0, -1)

        fun snapshot(): DownloadActivityState {
            return try {
                val queued = AnimeServiceDataSingleton.downloadQueue.size +
                        MangaServiceDataSingleton.downloadQueue.size +
                        NovelServiceDataSingleton.downloadQueue.size
                val running = AnimeServiceDataSingleton.currentTasks.size +
                        MangaServiceDataSingleton.currentTasks.size +
                        NovelServiceDataSingleton.currentTasks.size

                var sum = 0
                var n = 0
                for (map in listOf(
                    AnimeServiceDataSingleton.progress,
                    MangaServiceDataSingleton.progress,
                    NovelServiceDataSingleton.progress
                )) {
                    for (v in map.values) {
                        sum += v.coerceIn(0, 100)
                        n++
                    }
                }
                DownloadActivityState(
                    total = queued + running,
                    running = running,
                    percent = if (n == 0) -1 else sum / n
                )
            } catch (e: Exception) {
                IDLE
            }
        }
    }
}
