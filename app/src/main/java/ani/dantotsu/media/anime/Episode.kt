package ani.dantotsu.media.anime

import ani.dantotsu.FileUrl
import ani.dantotsu.parsers.VideoExtractor
import java.io.Serializable

data class Episode(
    val number: String,
    var link: String? = null,
    var title: String? = null,
    var desc: String? = null,
    var thumb: FileUrl? = null,
    var filler: Boolean = false,
    var date: String? = null,
    var rating: String? = null,
    var selectedExtractor: String? = null,
    var selectedVideo: Int = 0,
    var selectedSubtitle: Int? = -1,
    var downloadProgress: String? = null,
    @Transient var extractors: MutableList<VideoExtractor>? = null,
    @Transient var extractorCallback: ((VideoExtractor) -> Unit)? = null,
    var allStreams: Boolean = false,
    var watched: Long? = null,
    var maxLength: Long? = null,
    val extra: Map<String, String>? = null,
    val sEpisode: eu.kanade.tachiyomi.animesource.model.SEpisode? = null
) : Serializable



/**
 * Removes null entries from [Episode.extractors]. The list is filled by several loaders at once,
 * which can leave null slots behind; reading `it.server` on one of those crashes the player.
 */
fun Episode.dropNullExtractors() {
    val list = extractors ?: return
    runCatching {
        synchronized(list) { list.removeAll { java.util.Objects.isNull(it) } }
    }
}
