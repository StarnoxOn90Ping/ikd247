package ani.dantotsu.media

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ani.dantotsu.R
import ani.dantotsu.client
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.currContext
import ani.dantotsu.media.anime.Episode
import ani.dantotsu.media.anime.SelectorDialogFragment
import ani.dantotsu.media.anime.getEpisode
import ani.dantotsu.media.manga.MangaChapter
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import ani.dantotsu.media.mangaupdates.MangaAnimeUtil
import ani.dantotsu.others.AniSkip
import ani.dantotsu.others.Anify
import ani.dantotsu.others.AniskipCsvFallback
import ani.dantotsu.others.IdMappers
import ani.dantotsu.others.IntroDbService
import ani.dantotsu.others.Kitsu
import ani.dantotsu.others.TmdbService
import ani.dantotsu.media.novel.NovelStorage
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.Book
import ani.dantotsu.parsers.MangaImage
import ani.dantotsu.parsers.MangaReadSources
import ani.dantotsu.parsers.MangaSources
import ani.dantotsu.parsers.NovelSources
import ani.dantotsu.parsers.OfflineAnimeParser
import ani.dantotsu.parsers.OfflineMangaParser
import ani.dantotsu.parsers.OfflineNovelParser
import ani.dantotsu.parsers.ShowResponse
import ani.dantotsu.parsers.VideoExtractor
import ani.dantotsu.parsers.WatchSources
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull

class MediaDetailsViewModel : ViewModel() {
    val scrolledToTop = MutableLiveData(true)

    fun saveSelected(id: Int, data: Selected) {
        PrefManager.setCustomVal("Selected-$id", data)
    }


    /** Index (in the source list) of the extension chosen as default in settings, or null if none/not installed. */
    private fun defaultSourceIndex(media: Media): Int? {
        val (pref, list) = when {
            media.anime != null -> PrefName.DefaultAnimeSource to AnimeSources.list
            media.format == "MANGA" || media.format == "ONE_SHOT" -> PrefName.DefaultMangaSource to MangaSources.list
            else -> return null
        }
        val name = PrefManager.getVal<String>(pref)
        if (name.isBlank()) return null
        return list.indexOfFirst { it.name == name }.takeIf { it >= 0 }
    }

    fun loadSelected(media: Media, isDownload: Boolean = false): Selected {
        if ((media.format == "LOCAL" || media.format == "LOCAL_NOVEL") && media.selected != null) {
            return media.selected!!
        }
        val data =
            PrefManager.getNullableCustomVal("Selected-${media.id}", null, Selected::class.java)
                ?: Selected().let {
                    it.sourceIndex = 0
                    it.preferDub = PrefManager.getVal(PrefName.SettingsPreferDub)
                    saveSelected(media.id, it)
                    it
                }
        if (!isDownload && !data.sourceChosen) {
            // no source picked by hand for this title: use the default extension from settings
            defaultSourceIndex(media)?.let { idx ->
                if (data.sourceIndex != idx) {
                    data.sourceIndex = idx
                    data.server = null
                    data.scanlators = null
                    saveSelected(media.id, data)
                }
            }
        }
        if (isDownload) {
            data.sourceIndex = when {
                media.anime != null -> {
                    AnimeSources.list.size - 1
                }

                media.format == "MANGA" || media.format == "ONE_SHOT" -> {
                    MangaSources.list.size - 1
                }

                else -> {
                    NovelSources.list.size - 1
                }
            }
        }
        return data
    }

    var continueMedia: Boolean? = null
    var loading = false

    private val media: MutableLiveData<Media> = MutableLiveData<Media>(null)
    fun getMedia(): LiveData<Media> = media

    private val detailsOutdated = MutableLiveData(false)
    fun getDetailsOutdated(): LiveData<Boolean> = detailsOutdated
    fun clearDetailsOutdated() = detailsOutdated.postValue(false)
    fun loadMedia(m: Media) {
        if (!loading) {
            loading = true
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                if (m.id == 0 && m.format?.startsWith("LOCAL") == true) {
                    m.folderName = m.folderName ?: m.name
                    media.postValue(m)

                    val mapKeyStr = m.folderName ?: m.name
                    var mappedId = PrefManager.getCustomVal<Int>("local_mapping_$mapKeyStr", 0)
                    if (mappedId == 0) {
                        try {
                            val searchType = if (m.manga != null) "MANGA" else "ANIME"
                            val searchFormat = if (m.format == "LOCAL_NOVEL") "NOVEL" else null
                            var results = Anilist.query.searchAniManga(searchType, search = m.name, format = searchFormat)
                            if (results == null || results.results.isEmpty()) {
                                if (m.folderName != null && m.folderName != m.name) {
                                    results = Anilist.query.searchAniManga(searchType, search = m.folderName!!, format = searchFormat)
                                }
                            }
                            if (results != null && results.results.isNotEmpty()) {
                                mappedId = results.results[0].id
                                PrefManager.setCustomVal("local_mapping_$mapKeyStr", mappedId)
                            }
                        } catch (e: Exception) {
                            ani.dantotsu.util.Logger.log(e)
                        }
                    }

                    if (mappedId != 0) {
                        val newMedia = m.copy(id = mappedId)
                        val fetchedMedia = Anilist.query.mediaDetails(newMedia)
                        fetchedMedia?.format = m.format 
                        
                        // Cache
                        fetchedMedia?.cover?.let { ani.dantotsu.settings.saving.PrefManager.setCustomVal("local_cover_$mapKeyStr", it) }
                        fetchedMedia?.banner?.let { ani.dantotsu.settings.saving.PrefManager.setCustomVal("local_banner_$mapKeyStr", it) }

                        fetchedMedia?.folderName = m.folderName ?: m.name
                        fetchedMedia?.selected = m.selected
                        media.postValue(fetchedMedia)
                    }
                } else if (m.id == 0) {
                    m.folderName = m.folderName ?: m.name
                    media.postValue(m)
                } else {
                    val servedFromCache = ani.dantotsu.connections.anilist.MediaDetailsCache.has(m.id)
                    media.postValue(Anilist.query.mediaDetails(m))
                    if (servedFromCache) {
                        // Check quietly in the background whether AniList has newer info.
                        viewModelScope.launch(Dispatchers.IO) {
                            val changed = runCatching { Anilist.query.detailsChangedSinceCache(m.id) }
                                .getOrDefault(false)
                            if (changed) detailsOutdated.postValue(true)
                        }
                    }
                }
                loading = false
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (m.idIMDB == null) {
                    m.idIMDB = ani.dantotsu.others.IdMappers.getImdbId(m.id)
                }
            } catch (e: Exception) {
                ani.dantotsu.util.Logger.log(e)
            }
        }
    }

    fun setMedia(m: Media) {
        media.postValue(m)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (m.idIMDB == null) {
                    m.idIMDB = ani.dantotsu.others.IdMappers.getImdbId(m.id)
                }
            } catch (e: Exception) {
                ani.dantotsu.util.Logger.log(e)
            }
        }
    }

    val responses = MutableLiveData<List<ShowResponse>?>(null)


    //Anime
    private val kitsuEpisodes: MutableLiveData<Map<String, Episode>> =
        MutableLiveData<Map<String, Episode>>(null)

    fun getKitsuEpisodes(): LiveData<Map<String, Episode>> = kitsuEpisodes
    suspend fun loadKitsuEpisodes(s: Media, force: Boolean = false) {
        tryWithSuspend {
            if (kitsuEpisodes.value == null || force) kitsuEpisodes.postValue(Kitsu.getKitsuEpisodesDetails(s))
        }
    }

    private val anifyEpisodes: MutableLiveData<Map<String, Episode>> =
        MutableLiveData<Map<String, Episode>>(null)

    fun getAnifyEpisodes(): LiveData<Map<String, Episode>> = anifyEpisodes
    suspend fun loadAnifyEpisodes(s: Media, force: Boolean = false) {
        tryWithSuspend {
            if (anifyEpisodes.value == null || force) {
                val anilistId = s.id.takeIf { it != 0 }
                val malId = s.idMAL
                var episodes = Anify.fetchAndParseMetadata(anilistId = anilistId, malId = malId)
                if (episodes.isEmpty()) {
                    val tmdbId = s.idTMDB?.toIntOrNull()
                        ?: runCatching { IdMappers.getIds(s.id)?.tmdbId }.getOrNull()
                    if (tmdbId != null && tmdbId > 0) {
                        val tmdbEpisodes = TmdbService.getEpisodeDetails(tmdbId)
                        if (!tmdbEpisodes.isNullOrEmpty()) {
                            episodes = tmdbEpisodes
                        }
                    }
                }
                anifyEpisodes.postValue(episodes)
            }
        }
    }
    suspend fun loadAnifyEpisodes(s: Int, force: Boolean = false) {
        tryWithSuspend {
            if (anifyEpisodes.value == null || force) anifyEpisodes.postValue(Anify.fetchAndParseMetadata(anilistId = s, malId = null))
        }
    }

    private val fillerEpisodes: MutableLiveData<Map<String, Episode>> =
        MutableLiveData<Map<String, Episode>>(null)

    fun getFillerEpisodes(): LiveData<Map<String, Episode>> = fillerEpisodes
    // Jikan (MyAnimeList) was the only filler-flag source and was removed with MAL.
    @Suppress("UNUSED_PARAMETER")
    suspend fun loadFillerEpisodes(s: Media, force: Boolean = false) {
    }

    var watchSources: WatchSources? = null

    private val episodes = MutableLiveData<MutableMap<Int, MutableMap<String, Episode>>>(null)
    private val epsLoaded = mutableMapOf<Int, MutableMap<String, Episode>>()
    fun getEpisodes(): LiveData<MutableMap<Int, MutableMap<String, Episode>>> = episodes
    fun invalidateSource(sourceIndex: Int) {
        epsLoaded.remove(sourceIndex)
    }

    suspend fun loadEpisodes(media: Media, i: Int, invalidate: Boolean = false) {
        val isOffline = watchSources?.get(i) is OfflineAnimeParser
        val current = epsLoaded[i]
        if (current.isNullOrEmpty() || invalidate || isOffline) {
            val loaded = watchSources?.loadEpisodesFromMedia(
                i = i,
                media = media,
                invalidate = invalidate,
                onCachedLoaded = { cached ->
                    epsLoaded[i] = cached
                    episodes.postValue(epsLoaded)
                }
            ) ?: mutableMapOf()
            if (loaded.isNotEmpty() || invalidate) {
                epsLoaded[i] = loaded
            }
        }
        episodes.postValue(epsLoaded)
    }

    suspend fun forceLoadEpisode(media: Media, i: Int) {
        val loaded = watchSources?.loadEpisodesFromMedia(i, media, true) ?: mutableMapOf()
        if (loaded.isNotEmpty()) {
            epsLoaded[i] = loaded
        }
        episodes.postValue(epsLoaded)
    }

    suspend fun overrideEpisodes(i: Int, source: ShowResponse, id: Int) {
        if (source.sAnime == null) {
            source.sAnime = SAnime.create().apply {
                url = source.link
                title = source.name
                thumbnail_url = source.coverUrl.url
            }
        }
        watchSources?.saveResponse(i, id, source)
        epsLoaded[i] =
            watchSources?.loadEpisodes(i, source.link, source.extra, source.sAnime) ?: return
        episodes.postValue(epsLoaded)
    }

    private var episode = MutableLiveData<Episode?>(null)
    fun getEpisode(): LiveData<Episode?> = episode

    suspend fun loadEpisodeVideos(ep: Episode, i: Int, post: Boolean = true) {
        val link = ep.link ?: return
        if (!ep.allStreams || ep.extractors.isNullOrEmpty()) {
            val existingExtractors = ep.extractors?.toMutableList() ?: mutableListOf()
            val list: MutableList<VideoExtractor> =
                java.util.Collections.synchronizedList(mutableListOf<VideoExtractor>())
            ep.extractors = list
            watchSources?.get(i)?.apply {
                if (!post && !allowsPreloading) return@apply
                ep.sEpisode?.let {
                    loadByVideoServers(link, ep.extra, it) { extractor ->
                        if (extractor.videos.isNotEmpty()) {
                            list.add(extractor)
                            ep.extractorCallback?.invoke(extractor)
                        }
                    }
                }
                ep.extractorCallback = null
                // streams arrive in whatever order the servers answer; put them in the user's priority order
                synchronized(list) {
                    val sorted = ani.dantotsu.media.anime.ResolutionPriority.sort(list, name)
                    list.clear()
                    list.addAll(sorted)
                }
                if (list.isNotEmpty())
                    ep.allStreams = true
                else if (existingExtractors.isNotEmpty())
                    ep.extractors = existingExtractors
            }
        }


        if (post) {
            episode.postValue(ep)
            MainScope().launch(Dispatchers.Main) {
                episode.value = null
            }
        }
    }

    val timeStamps = MutableLiveData<List<AniSkip.Stamp>?>()
    private val timeStampsMap: MutableMap<Int, List<AniSkip.Stamp>?> = mutableMapOf()
    suspend fun loadTimeStamps(
        malId: Int?,
        episodeNum: Int?,
        duration: Long,
        useProxyForTimeStamps: Boolean,
        extensionTimestamps: List<eu.kanade.tachiyomi.animesource.model.TimeStamp> = emptyList()
    ) {
        episodeNum ?: return
        if (timeStampsMap.containsKey(episodeNum) && !timeStampsMap[episodeNum].isNullOrEmpty()) {
            return timeStamps.postValue(timeStampsMap[episodeNum])
        }
        if (duration <= 0 && extensionTimestamps.isEmpty()) return

        // Extension timestamps take priority; fall back to AniSkip when the extension has none
        val result: List<AniSkip.Stamp>? = if (extensionTimestamps.isNotEmpty()) {
            extensionTimestamps.map { it.toAniSkipStamp() }
        } else {
            var stamps: List<AniSkip.Stamp>? = null
            // 1. Try Live AniSkip API
            if (malId != null) {
                stamps = AniSkip.getResult(malId, episodeNum, duration, useProxyForTimeStamps)
            }
            // 2. Try Offline AniSkip CSV Dump DB
            if (stamps.isNullOrEmpty() && malId != null) {
                val ctx = currContext()
                if (ctx != null) {
                    stamps = AniskipCsvFallback.getInstance(ctx).lookup(malId, episodeNum)
                }
            }
            // 3. Try IntroDB API Fallback
            if (stamps.isNullOrEmpty()) {
                val mediaVal = media.value
                val imdbId = mediaVal?.idIMDB ?: mediaVal?.id?.let { runCatching { IdMappers.getImdbId(it) }.getOrNull() }
                val tmdbId = mediaVal?.idTMDB?.toIntOrNull() ?: mediaVal?.id?.let { runCatching { IdMappers.getIds(it)?.tmdbId }.getOrNull() }
                val tvdbId = mediaVal?.id?.let { runCatching { IdMappers.getIds(it)?.tvdbId }.getOrNull() }
                stamps = IntroDbService.getSkipTimes(imdbId = imdbId, tmdbId = tmdbId, tvdbId = tvdbId, episode = episodeNum.toString())
            }
            stamps
        }
        if (result != null || duration > 0) {
            timeStampsMap[episodeNum] = result
        }
        timeStamps.postValue(result)
    }

    private fun eu.kanade.tachiyomi.animesource.model.TimeStamp.toAniSkipStamp(): AniSkip.Stamp {
        val skipType = when (type) {
            eu.kanade.tachiyomi.animesource.model.ChapterType.Opening -> "op"
            eu.kanade.tachiyomi.animesource.model.ChapterType.Ending -> "ed"
            eu.kanade.tachiyomi.animesource.model.ChapterType.Recap -> "recap"
            eu.kanade.tachiyomi.animesource.model.ChapterType.MixedOp -> "mixed-op"
            eu.kanade.tachiyomi.animesource.model.ChapterType.Other ->
                name.lowercase().replace(" ", "-").ifEmpty { "other" }
        }
        return AniSkip.Stamp(
            interval = AniSkip.AniSkipInterval(start, end),
            skipType = skipType,
            skipId = name,
            // episodeLength represents total episode duration; use 0.0 as a sentinel since
            // extension timestamps don't carry the full episode length
            episodeLength = 0.0
        )
    }

    suspend fun loadEpisodeSingleVideo(
        ep: Episode,
        selected: Selected,
        post: Boolean = true,
        selectedServerName: String? = null
    ): Boolean {

        val server = selectedServerName ?: selected.server ?: return false
        val link = ep.link ?: return false

        ep.extractors?.removeAll { java.util.Objects.isNull(it) }
        if (ep.extractors?.find { it.server.name == server || it.isPreferred } == null) {
            Log.d("AnimeDownloader", "Loading Video Server for episode: ${ep.number}, selected server: $server")
            if(ep.extractors == null){
                ep.extractors = mutableListOf(watchSources?.get(selected.sourceIndex)?.let {
                    selected.sourceIndex = selected.sourceIndex
                    if (!post && !it.allowsPreloading) null
                    else ep.sEpisode?.let { it1 ->
                        it.loadSingleVideoServer(
                            server, link, ep.extra,
                            it1, post
                        )
                    }
                } ?: return false)
            }
            else{
                ep.extractors!!.add(watchSources?.get(selected.sourceIndex)?.let {
                    selected.sourceIndex = selected.sourceIndex
                    if (!post && !it.allowsPreloading) null
                    else ep.sEpisode?.let { it1 ->
                        it.loadSingleVideoServer(
                            server, link, ep.extra,
                            it1, post
                        )
                    }
                } ?: return false)
            }
            //ep.extractors?.forEach { Log.d("AnimeDownloader", "Extractor episode ${ep.number}: ${it.server.name}") }
            ep.allStreams = false
        }
        if (post) {
            episode.postValue(ep)
            MainScope().launch(Dispatchers.Main) {
                episode.value = null
            }
        }
        return true
    }

    fun setEpisode(ep: Episode?, who: String) {
        Logger.log("set episode ${ep?.number} - $who")
        episode.postValue(ep)
        MainScope().launch(Dispatchers.Main) {
            episode.value = null
        }
    }

    val epChanged = MutableLiveData(true)
    fun onEpisodeClick(
        media: Media,
        i: String = "",
        manager: FragmentManager,
        launch: Boolean = true,
        prevEp: String? = null,
        isDownload: Boolean = false,
        episodes: ArrayList<String> = arrayListOf() // used for handling an array of episodes to download or to view a single episode
    ) {
        Handler(Looper.getMainLooper()).post {
            if (manager.findFragmentByTag("dialog") == null && !manager.isDestroyed && !manager.isStateSaved) {
                if(episodes.isEmpty()){
                    episodes.add(i)
                }
                for (ep in episodes){
                    if (media.anime?.episodes?.getEpisode(ep) == null) {
                        snackString(currContext()?.getString(R.string.episode_not_found, ep))
                        return@post
                    }
                }
                media.selected = this.loadSelected(media)
                val selector =
                    SelectorDialogFragment.newInstance(
                        media.selected!!.server,
                        launch,
                        prevEp,
                        isDownload,
                        episodes
                    )
                try {
                    val ft = manager.beginTransaction()
                    ft.add(selector, "dialog")
                    ft.commitAllowingStateLoss()
                } catch (e: Exception) {
                    Logger.log(e)
                }
            }
        }
    }

    //Manga
    var mangaReadSources: MangaReadSources? = null

    private val mangaChapters =
        MutableLiveData<MutableMap<Int, MutableMap<String, MangaChapter>>>(null)
    private val mangaLoaded = mutableMapOf<Int, MutableMap<String, MangaChapter>>()
    fun getMangaChapters(): LiveData<MutableMap<Int, MutableMap<String, MangaChapter>>> =
        mangaChapters

    fun invalidateMangaSource(sourceIndex: Int) {
        mangaLoaded.remove(sourceIndex)
    }

    suspend fun loadMangaChapters(media: Media, i: Int, invalidate: Boolean = false) {
        Logger.log("Loading Manga Chapters : $mangaLoaded")
        val isOffline = mangaReadSources?.get(i) is OfflineMangaParser
        val current = mangaLoaded[i]
        if (current.isNullOrEmpty() || invalidate || isOffline) tryWithSuspend {
            val loaded = mangaReadSources?.loadChaptersFromMedia(
                i = i,
                media = media,
                invalidate = invalidate,
                onCachedLoaded = { cached ->
                    mangaLoaded[i] = cached
                    mangaChapters.postValue(mangaLoaded)
                }
            ) ?: mutableMapOf()
            if (loaded.isNotEmpty() || invalidate) {
                mangaLoaded[i] = loaded
            }
        }
        mangaChapters.postValue(mangaLoaded)
    }

    suspend fun overrideMangaChapters(i: Int, source: ShowResponse, id: Int) {
        if (source.sManga == null) {
            source.sManga = SManga.create().apply {
                url = source.link
                title = source.name
                thumbnail_url = source.coverUrl.url
            }
        }
        mangaReadSources?.saveResponse(i, id, source)
        tryWithSuspend {
            mangaLoaded[i] = mangaReadSources?.loadChapters(i, source) ?: return@tryWithSuspend
        }
        mangaChapters.postValue(mangaLoaded)
    }

    private val mangaChapter = MutableLiveData<MangaChapter?>(null)
    fun getMangaChapter(): LiveData<MangaChapter?> = mangaChapter
    suspend fun loadMangaChapterImages(
        chapter: MangaChapter,
        selected: Selected,
        post: Boolean = true
    ): Boolean {

        return tryWithSuspend(true) {
            val parser = mangaReadSources?.get(selected.sourceIndex) ?: return@tryWithSuspend false

            // Downloaded chapters always win over the online source.
            val downloaded = if (parser !is OfflineMangaParser) findDownloadedMangaChapter(chapter) else null
            if (downloaded != null) {
                val offlineImages = OfflineMangaParser().loadImages(
                    "${downloaded.titleName}/${downloaded.chapterName}",
                    chapter.sChapter
                )
                if (offlineImages.isNotEmpty()) {
                    chapter.resetImages()
                    chapter.addImages(offlineImages)
                    chapter.loadedFromDownload = true
                    if (post) mangaChapter.postValue(chapter)
                    return@tryWithSuspend true
                }
            }
            // The download may have been deleted since the images were cached.
            if (chapter.loadedFromDownload && parser !is OfflineMangaParser) {
                chapter.resetImages()
                chapter.loadedFromDownload = false
            }

            chapter.addImages(parser.loadImages(chapter.link, chapter.sChapter))
            if (post) mangaChapter.postValue(chapter)
            true
        } ?: false
    }

    private fun findDownloadedMangaChapter(chapter: MangaChapter): ani.dantotsu.download.DownloadedType? {
        val current = media.value ?: return null
        if (current.format == "LOCAL") return null
        val candidates = listOfNotNull(
            chapter.number,
            chapter.title,
            chapter.uniqueNumber(),
            chapter.title?.let { "$it-${chapter.scanlator ?: "Unknown"}" }
        )
        return Injekt.get<ani.dantotsu.download.DownloadsManager>().mangaDownloadedTypes.firstOrNull { d ->
            with(ani.dantotsu.download.DownloadsManager.Companion) { current.compareName(d.titleName) } &&
                    candidates.any {
                        it.equals(d.chapterName, ignoreCase = true) ||
                                it.equals(d.uniqueName, ignoreCase = true)
                    }
        }
    }

    fun loadTransformation(mangaImage: MangaImage, source: Int): BitmapTransformation? {
        return if (mangaImage.useTransformation) mangaReadSources?.get(source)
            ?.getTransformation() else null
    }

    val novelSources = NovelSources
    val novelResponses = MutableLiveData<List<ShowResponse>>(null)

    private val novelChapters = MutableLiveData<MutableMap<Int, List<ShowResponse>>>(null)
    private val novelLoaded = mutableMapOf<Int, List<ShowResponse>>()
    fun getNovelChapters(): LiveData<MutableMap<Int, List<ShowResponse>>> = novelChapters

    suspend fun searchNovels(query: String, i: Int) {
        val position = if (i >= novelSources.list.size) 0 else i
        val source = novelSources[position]
        tryWithSuspend(post = true) {
            if (source != null) {
                novelResponses.postValue(source.search(query))
            }
        }
    }

    suspend fun autoSearchNovels(media: Media) {
        val source = novelSources[media.selected?.sourceIndex ?: 0]
        tryWithSuspend(post = true) {
            if (source != null) {
                novelResponses.postValue(source.sortedSearch(media))
            }
        }
    }

    suspend fun loadNovelChapters(media: Media, i: Int, invalidate: Boolean = false) {
        val isOffline = novelSources[i] is OfflineNovelParser
        val current = novelLoaded[i]
        if (current.isNullOrEmpty() || invalidate || isOffline) {
            tryWithSuspend {
                val source = novelSources[i]
                if (source == null) {
                    novelLoaded[i] = emptyList()
                    return@tryWithSuspend
                }
                val sourceKey = source.saveName.ifBlank { source.name }

                var book: Book? = null
                var novelCover = media.cover?.let { ani.dantotsu.FileUrl(it) } ?: ani.dantotsu.FileUrl("")

                fun postBookChapters(targetBook: Book, cover: ani.dantotsu.FileUrl) {
                    val chapterResponses = targetBook.links.mapIndexed { index, fileUrl ->
                        val chapterName = fileUrl.headers?.get("X-Chapter-Name") ?: "Chapter ${index + 1}"
                        val releaseTime = fileUrl.headers?.get("X-Release-Time")
                        val chapterNumber = fileUrl.headers?.get("X-Chapter-Number")
                        ShowResponse(
                            name = chapterName,
                            link = fileUrl.url,
                            coverUrl = cover,
                            extra = mutableMapOf<String, String>().apply {
                                releaseTime?.let { put("releaseTime", it) }
                                chapterNumber?.let { put("chapterNumber", it) }
                                put("sourceName", source.name)
                            }
                        )
                    }
                    novelLoaded[i] = chapterResponses
                    novelChapters.postValue(novelLoaded)
                }

                if (!invalidate && !isOffline) {
                    val savedResponse = source.loadSavedShowResponse(media.id)
                    if (savedResponse != null && savedResponse.link.isNotBlank()) {
                        novelCover = savedResponse.coverUrl
                        book = NovelStorage.loadBook(sourceKey, savedResponse.link)
                        if (book != null && book.links.isNotEmpty()) {
                            postBookChapters(book, novelCover)
                        }
                    }
                }

                val novelResponse = source.autoSearch(media)
                if (novelResponse == null) {
                    if (novelLoaded[i] == null) novelLoaded[i] = emptyList()
                    return@tryWithSuspend
                }
                novelCover = novelResponse.coverUrl

                if (book == null && !invalidate && !isOffline) {
                    book = NovelStorage.loadBook(sourceKey, novelResponse.link)
                    if (book != null && book.links.isNotEmpty()) {
                        postBookChapters(book, novelCover)
                    }
                }

                val fetchedBook = source.loadBook(novelResponse.link, novelResponse.extra)
                if (fetchedBook != null && fetchedBook.links.isNotEmpty() && !isOffline) {
                    NovelStorage.saveBook(sourceKey, novelResponse.link, fetchedBook)
                    book = fetchedBook
                }

                if (book == null || book.links.isEmpty()) {
                    if (novelLoaded[i] == null) novelLoaded[i] = emptyList()
                    return@tryWithSuspend
                }
                postBookChapters(book, novelCover)
            }
        }
        novelChapters.postValue(novelLoaded)
    }

    suspend fun overrideNovelChapters(i: Int, source: ShowResponse, mediaId: Int) {
        novelSources.saveResponse(i, mediaId, source)
        novelLoaded.remove(i)
    }

    val book: MutableLiveData<Book> = MutableLiveData(null)
    suspend fun loadBook(novel: ShowResponse, i: Int) {
        tryWithSuspend {
            val source = novelSources[i] ?: return@tryWithSuspend
            val sourceKey = source.saveName.ifBlank { source.name }
            val isOffline = source is OfflineNovelParser
            if (!isOffline) {
                val cached = NovelStorage.loadBook(sourceKey, novel.link)
                if (cached != null && cached.links.isNotEmpty()) {
                    book.postValue(cached)
                }
            }
            val loaded = source.loadBook(novel.link, novel.extra) ?: return@tryWithSuspend
            if (loaded.links.isNotEmpty() && !isOffline) {
                NovelStorage.saveBook(sourceKey, novel.link, loaded)
            }
            book.postValue(loaded)
        }
    }

    private val fetchedOnlineSubtitles = mutableMapOf<String, List<Any>>()

    fun saveFetchedSubtitles(id: String, subs: List<Any>) {
        fetchedOnlineSubtitles[id] = subs
    }

    fun getFetchedSubtitles(id: String): List<Any>? {
        return fetchedOnlineSubtitles[id]
    }

    fun clearFetchedSubtitles(id: String) {
        fetchedOnlineSubtitles.remove(id)
    }

    private val localSubtitlesMap = mutableMapOf<String, MutableList<Any>>()

    fun saveLocalSubtitle(id: String, sub: Any) {
        val list = localSubtitlesMap.getOrPut(id) { mutableListOf() }
        val isDuplicate = list.any { existing ->
            existing is ani.dantotsu.parsers.Subtitle &&
            sub is ani.dantotsu.parsers.Subtitle &&
            existing.file.url == sub.file.url
        }
        if (!isDuplicate) list.add(sub)
    }

    fun getLocalSubtitles(id: String): List<Any> {
        return localSubtitlesMap[id] ?: emptyList()
    }

    fun removeLocalSubtitle(id: String, sub: Any) {
        val list = localSubtitlesMap[id] ?: return
        list.removeAll { existing ->
            if (existing is ani.dantotsu.parsers.Subtitle && sub is ani.dantotsu.parsers.Subtitle) {
                existing.file.url == sub.file.url
            } else {
                existing == sub
            }
        }
        if (list.isEmpty()) {
            localSubtitlesMap.remove(id)
        }
    }

    fun clearLocalSubtitles(id: String) {
        localSubtitlesMap.remove(id)
    }

    val adaptation = MutableLiveData<MangaAnimeUtil.AnimeAdaptation?>()
    val nextRelease = MutableLiveData<MangaAnimeUtil.NextRelease?>()
    fun loadMangaExtras(media: Media) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val seriesDeferred = async {
                    MangaAnimeUtil.getSeriesFromMedia(media)
                }

                val adaptationDeferred = async {
                    MangaAnimeUtil.getAnimeAdaptation(seriesDeferred.await())
                }

                val nextReleaseDeferred = async {
                    MangaAnimeUtil.getNextChapterPrediction(
                        media,
                        seriesDeferred.await()
                    )
                }

                adaptation.postValue(adaptationDeferred.await())
                nextRelease.postValue(nextReleaseDeferred.await())

            } catch (e: Exception) {
                Logger.log("MangaExtras error: $e")
            }
        }
    }

    // Watch Order and News integration
    data class WatchOrderItem(
        val id: String,
        val anilistId: String,
        val image: String,
        val name: String,
        val relationType: String,
        val isCurrent: Boolean = false,
        val airDate: String = "",
        val mediaType: String = "",
        val episodes: String = "",
        val rating: String = ""
    ) : java.io.Serializable

    data class NewsItem(
        val title: String,
        val url: String,
        val date: java.util.Date?
    ) : java.io.Serializable

    suspend fun getWatchOrder(media: Media): List<WatchOrderItem> {
        return tryWithSuspend {
            val headers = mapOf(
                "Referer" to "https://chiaki.site/?/tools/watch_order",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "X-Requested-With" to "XMLHttpRequest"
            )

            val malId = media.idMAL ?: ani.dantotsu.others.IdMappers.getMalId(media.id)
            if (media.idMAL == null && malId != null) {
                media.idMAL = malId
            }
            var targetId = malId?.toString()

            if (targetId == null) {
                val searchName = media.userPreferredName.ifEmpty { media.mainName() }
                val encodedTerm = java.net.URLEncoder.encode(searchName, "UTF-8")
                val searchUrl = "https://chiaki.site/?/tools/autocomplete_series&term=$encodedTerm"
                val res = client.get(searchUrl, headers = headers).text
                val json = org.json.JSONArray(res)
                if (json.length() > 0) {
                    targetId = json.getJSONObject(0).opt("id")?.toString()
                }
            }

            if (targetId == null) return@tryWithSuspend emptyList()

            val orderUrl = "https://chiaki.site/?/tools/watch_order/id/$targetId"
            val html = client.get(orderUrl, headers = headers).text
            val doc = org.jsoup.Jsoup.parse(html)

            val rows = doc.select("tr[data-id]")
            val relationsMap = media.relations?.associateBy { it.id.toString() } ?: emptyMap()

            // Parse relations relative to targetId from data-related JSON attribute
            val targetRow = rows.find { it.attr("data-id") == targetId }
            val targetRelatedJson = targetRow?.attr("data-related") ?: "{}"
            val relatedMap = try {
                val jsonObj = org.json.JSONObject(targetRelatedJson)
                val map = mutableMapOf<String, String>()
                jsonObj.keys().forEach { key ->
                    map[key] = jsonObj.getString(key)
                }
                map
            } catch (_: Exception) {
                emptyMap<String, String>()
            }

            rows.mapIndexedNotNull { index, row ->
                val title = row.select("span.wo_title").text().trim()
                    .ifEmpty { row.select(".wo_title").text().trim() }
                if (title.isEmpty() || title.equals("Unknown title", ignoreCase = true)) return@mapIndexedNotNull null

                val id = row.attr("data-id").ifEmpty { targetId }
                val anilistId = row.attr("data-anilist-id")

                // 1. Cover image from style background-image
                val style = row.select("div.wo_avatar_big").attr("style")
                val imgMatch = Regex("""url\(['"]?([^'")]+)['"]?\)""").find(style)
                var imageUrl = imgMatch?.groupValues?.get(1)?.let { path ->
                    when {
                        path.startsWith("http") -> path
                        path.startsWith("/") -> "https://chiaki.site$path"
                        else -> "https://chiaki.site/$path"
                    }
                } ?: ""

                if (imageUrl.isEmpty()) {
                    if (anilistId == media.id.toString()) {
                        imageUrl = media.cover ?: ""
                    } else if (relationsMap.containsKey(anilistId)) {
                        imageUrl = relationsMap[anilistId]?.cover ?: ""
                    }
                }

                // 2. Metadata text: Air Date | Media Type | Episodes | Rating
                val metaText = row.select("span.uk-text-muted.uk-text-small").text().trim()
                    .ifEmpty { row.select(".wo_meta").text().trim() }
                val parts = metaText.split('|').map { it.trim() }
                val airDate = if (parts.isNotEmpty()) parts[0] else ""
                val format = if (parts.size > 1) parts[1] else ""
                val eps = if (parts.size > 2) parts[2].substringBefore("★").substringBefore("(").trim() else ""
                val rating = ""

                // 3. Is current anime
                val cleanId = id.trim()
                val cleanAnilistId = anilistId.trim()
                val currentMalId = media.idMAL ?: malId
                val isCurrent = (cleanAnilistId.isNotEmpty() && cleanAnilistId == media.id.toString()) ||
                        (cleanId.isNotEmpty() && currentMalId != null && cleanId == currentMalId.toString()) ||
                        (cleanId.isNotEmpty() && targetId != null && cleanId == targetId.trim() && (currentMalId == null || currentMalId.toString() == targetId.trim())) ||
                        (title.isNotEmpty() && (title.equals(media.userPreferredName, ignoreCase = true) ||
                                title.equals(media.mainName(), ignoreCase = true) ||
                                title.equals(media.name, ignoreCase = true) ||
                                title.equals(media.nameRomaji, ignoreCase = true)))

                // 4. Relation type
                val relationType = when {
                    isCurrent -> "Selected"
                    relatedMap.containsKey(id) -> relatedMap[id]!!
                    relationsMap.containsKey(anilistId) -> {
                        relationsMap[anilistId]?.relation?.replace("_", " ")?.lowercase()
                            ?.split(" ")?.joinToString(" ") { it.replaceFirstChar(Char::titlecase) } ?: ""
                    }
                    index == 0 -> "Main Story"
                    format.equals("TV", ignoreCase = true) -> "Sequel"
                    format.equals("OVA", ignoreCase = true) || format.equals("Special", ignoreCase = true) -> "Side Story"
                    format.equals("Movie", ignoreCase = true) -> "Movie"
                    else -> format.ifEmpty { "Entry" }
                }

                WatchOrderItem(
                    id = id,
                    anilistId = anilistId,
                    image = imageUrl,
                    name = title,
                    relationType = relationType,
                    isCurrent = isCurrent,
                    airDate = airDate,
                    mediaType = format,
                    episodes = eps,
                    rating = rating
                )
            }
        } ?: emptyList()
    }


    suspend fun getAnimeNews(media: Media): List<NewsItem> {
        return tryWithSuspend {
            var malId = media.idMAL
            if (malId == null || malId == 0) {
                malId = ani.dantotsu.others.IdMappers.getMalId(media.id)
            }
            if (malId == null || malId == 0) {
                malId = runCatching {
                    Anilist.query.getMedia(media.id)?.idMAL
                }.getOrNull()
                if (malId != null && malId != 0) {
                    media.idMAL = malId
                }
            }
            val targetId = malId?.takeIf { it != 0 } ?: return@tryWithSuspend emptyList()

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                "Accept" to "application/json, text/plain, */*"
            )

            suspend fun fetchKuroiruNews(id: Int): List<NewsItem> {
                val jsonStr = runCatching {
                    val res = client.get("https://kuroiru.co/api/anime/$id", headers = headers)
                    if (res.isSuccessful) res.text else null
                }.getOrNull() ?: return emptyList()

                val root = runCatching {
                    Json.parseToJsonElement(jsonStr).jsonObject
                }.getOrNull() ?: return emptyList()
                val newsArr = root["news"]?.jsonArray ?: return emptyList()
                val list = mutableListOf<NewsItem>()
                for (item in newsArr) {
                    val itemObj = item.jsonObject
                    val timeMs = itemObj["time"]?.jsonPrimitive?.longOrNull ?: 0L
                    val rawTitle = itemObj["title"]?.jsonPrimitive?.contentOrNull ?: ""
                    val title = org.jsoup.parser.Parser.unescapeEntities(rawTitle, false)
                    val rawLink = itemObj["link"]?.jsonPrimitive?.contentOrNull ?: ""
                    val link = normalizeKuroiruLink(rawLink)
                    if (title.isNotEmpty() && link.isNotEmpty()) {
                        list.add(
                            NewsItem(
                                title = title,
                                url = link,
                                date = if (timeMs > 0) java.util.Date(timeMs * 1000L) else null
                            )
                        )
                    }
                }
                return list
            }

            var result = fetchKuroiruNews(targetId)
            if (result.isEmpty()) {
                val candidateIds = mutableListOf<Int>()
                media.prequel?.idMAL?.let { candidateIds.add(it) }
                media.relations?.forEach { rel ->
                    rel.idMAL?.let { candidateIds.add(it) }
                }
                for (candId in candidateIds) {
                    if (candId != targetId && candId != 0) {
                        result = fetchKuroiruNews(candId)
                        if (result.isNotEmpty()) break
                    }
                }
            }

            result.sortedByDescending { it.date }
        } ?: emptyList()
    }

    private fun normalizeKuroiruLink(rawLink: String): String {
        val link = rawLink.trim()
        if (link.isEmpty()) return ""
        if (link.startsWith("http://", ignoreCase = true) ||
            link.startsWith("https://", ignoreCase = true)
        ) return link
       
        val digitId = link.removePrefix(".")
        if (digitId.isNotEmpty() && digitId.all { it.isDigit() }) {
            return "https://www.animenewsnetwork.com/news/.$digitId"
        }
        return link
    }

    suspend fun getMangaNovelNews(media: Media): List<NewsItem> {
        return tryWithSuspend {
            val bakaBase = "https://api.mangabaka.org/v1"
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                "Accept" to "application/json, text/plain, */*"
            )
            suspend fun getBakaId(): Int? {
                if (media.id != 0) {
                    val id = runCatching {
                        val res = client.get("$bakaBase/source/anilist/${media.id}", headers = headers)
                        if (!res.isSuccessful) return@runCatching null
                        val root = Json.parseToJsonElement(res.text).jsonObject
                        root["data"]?.jsonObject?.get("series")?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.intOrNull?.takeIf { it != 0 }
                    }.getOrNull()
                    if (id != null && id != 0) return id
                }

                if (media.idMAL != null && media.idMAL != 0) {
                    val id = runCatching {
                        val res = client.get("$bakaBase/source/my-anime-list/${media.idMAL}", headers = headers)
                        if (!res.isSuccessful) return@runCatching null
                        val root = Json.parseToJsonElement(res.text).jsonObject
                        root["data"]?.jsonObject?.get("series")?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.intOrNull?.takeIf { it != 0 }
                    }.getOrNull()
                    if (id != null && id != 0) return id
                }

                val titleToSearch = media.userPreferredName.ifEmpty { media.mainName() }
                if (titleToSearch.isNotEmpty()) {
                    val encodedTitle = java.net.URLEncoder.encode(titleToSearch, "UTF-8")
                    val id = runCatching {
                        val res = client.get("$bakaBase/series/search?q=$encodedTitle", headers = headers)
                        if (!res.isSuccessful) return@runCatching null
                        val root = Json.parseToJsonElement(res.text).jsonObject
                        root["data"]?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.intOrNull?.takeIf { it != 0 }
                    }.getOrNull()
                    if (id != null && id != 0) return id
                }

                return null
            }

            val bakaId = getBakaId() ?: return@tryWithSuspend emptyList()

            val jsonStr = runCatching {
                val res = client.get("$bakaBase/series/$bakaId/news", headers = headers)
                if (res.isSuccessful) res.text else null
            }.getOrNull() ?: return@tryWithSuspend emptyList()

            val root = runCatching { Json.parseToJsonElement(jsonStr).jsonObject }.getOrNull()
                ?: return@tryWithSuspend emptyList()
            val newsArr = root["data"]?.jsonArray ?: return@tryWithSuspend emptyList()
            val result = mutableListOf<NewsItem>()

            fun parseDate(dateStr: String): java.util.Date? {
                if (dateStr.isEmpty()) return null
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val instantDate = runCatching {
                        java.util.Date(java.time.Instant.parse(dateStr).toEpochMilli())
                    }.getOrNull()
                    if (instantDate != null) return instantDate
                }
                val formats = listOf(
                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss",
                    "yyyy-MM-dd"
                )
                for (fmt in formats) {
                    val parsed = runCatching {
                        val sdf = java.text.SimpleDateFormat(fmt, java.util.Locale.US).apply {
                            timeZone = java.util.TimeZone.getTimeZone("UTC")
                        }
                        sdf.parse(dateStr)
                    }.getOrNull()
                    if (parsed != null) return parsed
                }
                return null
            }

            for (item in newsArr) {
                val itemObj = item.jsonObject
                val pubAt = itemObj["published_at"]?.jsonPrimitive?.contentOrNull ?: ""
                val date = parseDate(pubAt)
                val rawTitle = itemObj["title"]?.jsonPrimitive?.contentOrNull ?: ""
                val title = org.jsoup.parser.Parser.unescapeEntities(rawTitle, false)
                val url = itemObj["url"]?.jsonPrimitive?.contentOrNull ?: ""
                if (title.isNotEmpty() && url.isNotEmpty()) {
                    result.add(
                        NewsItem(
                            title = title,
                            url = url,
                            date = date
                        )
                    )
                }
            }
            result.sortByDescending { it.date }
            result
        } ?: emptyList()
    }
}