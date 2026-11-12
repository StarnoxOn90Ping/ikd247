package nox.kiwami.di

import android.app.Application
import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import nox.kiwami.App
import nox.kiwami.MainActivity
import nox.kiwami.addons.download.DownloadAddonManager
import nox.kiwami.di.injekt.MetroInteropModule
import nox.kiwami.download.DownloadsManager
import nox.kiwami.media.manga.MangaCache
import nox.kiwami.parsers.novel.NovelExtensionManager
import nox.kiwami.torrent.TorrentServerManager
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import tachiyomi.core.preference.PreferenceStore
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.service.MangaSourceManager

@OptIn(ExperimentalSerializationApi::class)
@DependencyGraph(
    scope = AppScope::class,
    bindingContainers = [AppBindings::class],
)
interface AppGraph {
    fun inject(app: App)
    fun inject(mainActivity: MainActivity)

    val context: Context
    val application: Application

    val preferenceStore: PreferenceStore
    val sourcePreferences: SourcePreferences
    val basePreferences: BasePreferences

    val downloadsManager: DownloadsManager
    val networkHelper: NetworkHelper
    val javaScriptEngine: JavaScriptEngine
    val animeExtensionManager: AnimeExtensionManager
    val mangaExtensionManager: MangaExtensionManager
    val novelExtensionManager: NovelExtensionManager
    val torrentServerManager: TorrentServerManager
    val downloadAddonManager: DownloadAddonManager
    val animeSourceManager: AnimeSourceManager
    val mangaSourceManager: MangaSourceManager
    val mangaCache: MangaCache
    val json: Json
    val protoBuf: ProtoBuf
    val metroInteropModule: MetroInteropModule

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides context: Context): AppGraph
    }
}
