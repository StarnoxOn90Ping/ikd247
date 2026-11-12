package ani.kiwami.media.anime

import ani.kiwami.media.MediaDetailsViewModel
import ani.kiwami.media.SourceAdapter
import ani.kiwami.media.SourceSearchDialogFragment
import ani.kiwami.parsers.ShowResponse
import kotlinx.coroutines.CoroutineScope

class AnimeSourceAdapter(
    sources: List<ShowResponse>,
    val model: MediaDetailsViewModel,
    val i: Int,
    val id: Int,
    fragment: SourceSearchDialogFragment,
    scope: CoroutineScope
) : SourceAdapter(sources, fragment, scope) {

    override suspend fun onItemClick(source: ShowResponse) {
        model.overrideEpisodes(i, source, id)
    }
}