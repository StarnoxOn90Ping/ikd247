package nox.kiwami.di

import android.app.Application
import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import nox.kiwami.addons.download.DownloadAddonManager
import nox.kiwami.download.DownloadsManager
import nox.kiwami.media.manga.MangaCache
import nox.kiwami.parsers.novel.NovelExtensionManager
import nox.kiwami.torrent.TorrentServerManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.core.preference.AndroidPreferenceStore
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.anime.AndroidAnimeSourceManager
import eu.kanade.tachiyomi.source.manga.AndroidMangaSourceManager
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import tachiyomi.core.preference.PreferenceStore
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.service.MangaSourceManager

@OptIn(ExperimentalSerializationApi::class)
@BindingContainer
object AppBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun providesApplication(context: Context): Application = context.applicationContext as Application

    @Provides
    @SingleIn(AppScope::class)
    fun providesAnimeSourceManager(context: Context, extensionManager: AnimeExtensionManager): AnimeSourceManager =
        AndroidAnimeSourceManager(context, extensionManager)

    @Provides
    @SingleIn(AppScope::class)
    fun providesMangaSourceManager(context: Context, extensionManager: MangaExtensionManager): MangaSourceManager =
        AndroidMangaSourceManager(context, extensionManager)

    @Provides
    @SingleIn(AppScope::class)
    fun providesMangaCache(): MangaCache = MangaCache()

    @Provides
    @SingleIn(AppScope::class)
    fun providesDatabaseProvider(context: Context): StandaloneDatabaseProvider = StandaloneDatabaseProvider(context)

    @Provides
    @SingleIn(AppScope::class)
    fun providesJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providesProtoBuf(): ProtoBuf = ProtoBuf
}
