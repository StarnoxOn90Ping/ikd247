package ani.kiwami.parsers

import ani.kiwami.Lazier
import ani.kiwami.parsers.novel.DynamicNovelParser
import ani.kiwami.parsers.novel.LnReaderNovelParser
import ani.kiwami.parsers.novel.NovelExtension
import ani.kiwami.settings.saving.PrefManager
import ani.kiwami.settings.saving.PrefName
import ani.kiwami.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

object NovelSources : NovelReadSources() {
    override var list: List<Lazier<BaseParser>> = emptyList()
    var pinnedNovelSources: List<String> = emptyList()
    suspend fun init(fromExtensions: Flow<List<NovelExtension>>) {
        pinnedNovelSources =
            PrefManager.getNullableVal<List<String>>(PrefName.NovelSourcesOrder, null)
                ?: emptyList()

        val initialExtensions = fromExtensions.first()
        list = createParsersFromExtensions(initialExtensions) + Lazier<BaseParser>(
            { LocalNovelParser() },
            "Local"
        ) + Lazier<BaseParser>(
            { OfflineNovelParser() },
            "Downloaded"
        )

        fromExtensions.collect { extensions ->
            list = sortPinnedNovelSources(
                createParsersFromExtensions(extensions),
                pinnedNovelSources
            ) + Lazier<BaseParser>(
                { LocalNovelParser() },
                "Local"
            ) + Lazier<BaseParser>(
                { OfflineNovelParser() },
                "Downloaded"
            )
        }
    }

    fun performReorderNovelSources() {
        //remove the downloaded source from the list to avoid duplicates
        list = list.filter { it.name != "Downloaded" && it.name != "Local" }
        list = sortPinnedNovelSources(list, pinnedNovelSources) + Lazier<BaseParser>(
            { LocalNovelParser() },
            "Local"
        ) + Lazier<BaseParser>(
            { OfflineNovelParser() },
            "Downloaded"
        )
    }

    private fun createParsersFromExtensions(
        extensions: List<NovelExtension>
    ): List<Lazier<BaseParser>> {
        Logger.log("NovelSources.createParsersFromExtensions: ${extensions.size} extensions")
        return extensions.mapNotNull { ext ->
            when (ext) {
                is NovelExtension.Installed -> {
                    val name = ext.name
                    Lazier({ DynamicNovelParser(ext) }, name)
                }
                is NovelExtension.JsPlugin -> {
                    val plugin = ext.plugin
                    Lazier({ LnReaderNovelParser(plugin) }, plugin.name)
                }
                is NovelExtension.Available -> null
            }
        }
    }

    private fun sortPinnedNovelSources(
        parsers: List<Lazier<BaseParser>>,
        pinnedSources: List<String>
    ): List<Lazier<BaseParser>> {
        val pinnedMap = parsers.filter { pinnedSources.contains(it.name) }
            .associateBy { it.name }
        val ordered  = pinnedSources.mapNotNull { pinnedMap[it] }
        val unpinned = parsers.filterNot { pinnedSources.contains(it.name) }
        return ordered + unpinned
    }
}
