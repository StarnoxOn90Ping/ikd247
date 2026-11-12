package nox.kiwami.media.anime

import nox.kiwami.parsers.Video
import nox.kiwami.parsers.VideoExtractor
import nox.kiwami.settings.saving.PrefManager

/**
 * Orders streams by the user's "resolution priority" list (Settings > Anime, or the per-extension
 * setting). The resolution at the top of that list comes first; streams whose resolution is not in
 * the list go last, highest quality first.
 */
object ResolutionPriority {

    private fun number(res: String) = Regex("""\d+""").find(res)?.value?.toIntOrNull()

    private fun rank(video: Video, priority: List<String>): Int {
        priority.forEachIndexed { index, res ->
            val n = number(res) ?: return@forEachIndexed
            if (video.quality == n) return index
            val note = video.extraNote
            if (!note.isNullOrBlank() && Regex("""(?<!\d)$n(?!\d)""").containsMatchIn(note)) return index
        }
        return priority.size
    }

    private fun bestRank(extractor: VideoExtractor, priority: List<String>): Int =
        extractor.videos.minOfOrNull { rank(it, priority) } ?: priority.size

    private fun bestQuality(extractor: VideoExtractor): Int =
        extractor.videos.maxOfOrNull { it.quality ?: 0 } ?: 0

    /** Comparator for streams of the given extension (null = the global priority). */
    fun comparator(sourceName: String?): Comparator<VideoExtractor> {
        val priority = PrefManager.getPreferredDownloadResolutions(sourceName)
        return compareBy<VideoExtractor> { bestRank(it, priority) }
            .thenByDescending { bestQuality(it) }
            .thenByDescending { it.isPreferred }
    }

    fun sort(extractors: List<VideoExtractor>, sourceName: String?): List<VideoExtractor> =
        extractors.sortedWith(comparator(sourceName))

    /** Sorts the videos inside a single extractor the same way. */
    fun sortVideos(videos: List<Video>, sourceName: String?): List<Video> {
        val priority = PrefManager.getPreferredDownloadResolutions(sourceName)
        return videos.sortedWith(
            compareBy<Video> { rank(it, priority) }.thenByDescending { it.quality ?: 0 }
        )
    }
}
