package nox.kiwami.media.anime

import nox.kiwami.media.MediaDetailsViewModel
import nox.kiwami.media.SourceAdapter
import nox.kiwami.media.SourceSearchDialogFragment
import nox.kiwami.parsers.ShowResponse
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