package nox.kiwami.connections.anilist

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import nox.kiwami.BuildConfig
import nox.kiwami.R
import nox.kiwami.connections.discord.Discord
import nox.kiwami.media.Media
import nox.kiwami.media.Character
import nox.kiwami.media.Studio
import nox.kiwami.media.Author
import nox.kiwami.profile.User
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import nox.kiwami.snackString
import nox.kiwami.media.anime.Anime
import nox.kiwami.media.manga.Manga
import nox.kiwami.tryWithSuspend
import nox.kiwami.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the home rows actually show for one item. Equal signatures = nothing visible changed. */
fun mediaSignature(m: Media): String = buildString {
    append(m.id).append('|').append(m.cover).append('|').append(m.userPreferredName).append('|')
    append(m.userProgress).append('|').append(m.userProgressVolumes).append('|')
    append(m.userStatus).append('|').append(m.userScore).append('|').append(m.userUpdatedAt).append('|')
    append(m.meanScore).append('|').append(m.status).append('|').append(m.format).append('|')
    append(m.isFav).append('|').append(m.timeUntilAiring).append('|')
    append(m.anime?.totalEpisodes).append('|').append(m.anime?.nextAiringEpisode).append('|')
    append(m.anime?.nextAiringEpisodeTime).append('|').append(m.manga?.totalChapters)
}

fun mediaListSignature(list: List<Media>): String = list.joinToString("\n") { mediaSignature(it) }

fun userStatusSignature(list: List<User>): String = list.joinToString("\n") {
    "${it.id}|${it.name}|${it.pfp}|${it.status}|${it.progress}|${it.nextAiringEpisode}|${it.activity.size}"
}

suspend fun getUserId(context: Context? = null, block: () -> Unit) {
    if ((!Anilist.initialized || Anilist.avatar == null) && PrefManager.getVal<String>(PrefName.AnilistToken) != "") {
        if (!Anilist.query.getUserData()) {
            context?.let { snackString(it.getString(R.string.error_loading_anilist_user_data)) }
        }
    }
    block.invoke()
}

class AnilistHomeViewModel : ViewModel() {
    private val listImages: MutableLiveData<ArrayList<String?>> =
        MutableLiveData<ArrayList<String?>>(arrayListOf())

    fun getListImages(): LiveData<ArrayList<String?>> = listImages
    suspend fun setListImages() {
        val images = Anilist.query.getBannerImages()
        // same banners as already shown: nothing to redraw
        if (images != listImages.value) listImages.postValue(images)
    }

    // Last signature posted per home row. The cached page and the fresh page usually hold the
    // same data; posting it again made every row throw its adapter away and animate in again.
    private val postedSigs = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun postIfChanged(
        key: String,
        live: MutableLiveData<ArrayList<Media>>,
        list: ArrayList<Media>
    ) {
        val sig = mediaListSignature(list)
        if (live.value != null && postedSigs[key] == sig) return
        postedSigs[key] = sig
        live.postValue(list)
    }

    private val animeContinue: MutableLiveData<ArrayList<Media>> =
        MutableLiveData<ArrayList<Media>>(null)

    fun getAnimeContinue(): LiveData<ArrayList<Media>> = animeContinue

    private val animeFav: MutableLiveData<ArrayList<Media>> =
        MutableLiveData<ArrayList<Media>>(null)

    fun getAnimeFav(): LiveData<ArrayList<Media>> = animeFav

    private val animePlanned: MutableLiveData<ArrayList<Media>> =
        MutableLiveData<ArrayList<Media>>(null)

    fun getAnimePlanned(): LiveData<ArrayList<Media>> = animePlanned

    private val mangaContinue: MutableLiveData<ArrayList<Media>> =
        MutableLiveData<ArrayList<Media>>(null)

    fun getMangaContinue(): LiveData<ArrayList<Media>> = mangaContinue

    private val mangaFav: MutableLiveData<ArrayList<Media>> =
        MutableLiveData<ArrayList<Media>>(null)

    fun getMangaFav(): LiveData<ArrayList<Media>> = mangaFav

    private val mangaPlanned: MutableLiveData<ArrayList<Media>> =
        MutableLiveData<ArrayList<Media>>(null)

    fun getMangaPlanned(): LiveData<ArrayList<Media>> = mangaPlanned

    private val recommendation: MutableLiveData<ArrayList<Media>> =
        MutableLiveData<ArrayList<Media>>(null)

    fun getRecommendation(): LiveData<ArrayList<Media>> = recommendation

    var recommendationPage: Int = 1
    var recommendationHasNextPage: Boolean = true
    var recommendationLoading: Boolean = false

    suspend fun loadMoreRecommendations(): ArrayList<Media>? {
        if (!recommendationHasNextPage || recommendationLoading) return null
        recommendationLoading = true
        return try {
            val currentList = recommendation.value ?: arrayListOf()
            val existingIds = currentList.map { it.id }.toMutableSet()
            val addedMedia = arrayListOf<Media>()
            var attempts = 0
            while (addedMedia.isEmpty() && recommendationHasNextPage && attempts < 3) {
                attempts++
                val nextPage = recommendationPage + 1
                val (newMedia, hasNext) = Anilist.query.getRecommendations(nextPage)
                recommendationPage = nextPage
                recommendationHasNextPage = hasNext
                val uniqueNew = newMedia.filter { it.id !in existingIds }
                if (uniqueNew.isNotEmpty()) {
                    addedMedia.addAll(uniqueNew)
                    existingIds.addAll(uniqueNew.map { it.id })
                }
            }
            if (addedMedia.isNotEmpty()) {
                currentList.addAll(addedMedia)
                postedSigs["recommendations"] = mediaListSignature(currentList)
                recommendation.postValue(currentList)
            }
            addedMedia
        } catch (e: Exception) {
            Logger.log("Failed to load more recommendations: ${e.message}")
            null
        } finally {
            recommendationLoading = false
        }
    }

    private val missingSequels: MutableLiveData<ArrayList<Media>> =
        MutableLiveData<ArrayList<Media>>(null)

    fun getMissingSequels(): LiveData<ArrayList<Media>> = missingSequels

    private val userStatus: MutableLiveData<ArrayList<User>?> =
        MutableLiveData(null)

    fun getUserStatus(): LiveData<ArrayList<User>?> = userStatus
    suspend fun initUserStatus(forceRefresh: Boolean = false) {
        // Showing data and not forced: a fresh result identical to it is simply dropped.
        val keepIfSame = !forceRefresh && userStatus.value != null
        val before = userStatus.value?.let { userStatusSignature(it) }
        if (!keepIfSame) {
            userStatus.postValue(null)
        }
        val res = try {
            Anilist.query.getUserStatus(forceRefresh)
        } catch (_: Exception) {
            null
        }
        val result = res ?: arrayListOf()
        if (keepIfSame && before == userStatusSignature(result)) return
        userStatus.postValue(result)
    }

    private val hidden: MutableLiveData<ArrayList<Media>> =
        MutableLiveData<ArrayList<Media>>(null)

    fun getHidden(): LiveData<ArrayList<Media>> = hidden

    suspend fun initHomePage(forceRefresh: Boolean = false) {
        recommendationPage = 1
        recommendationHasNextPage = true
        recommendationLoading = false
        if (!forceRefresh) {
            val cachedRes = Anilist.query.loadHomePageCache()
            if (cachedRes != null) {
                postHomePageData(cachedRes)
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val freshRes = Anilist.query.initHomePage()
                        if (freshRes.isNotEmpty()) {
                            Anilist.query.saveHomePageCache(freshRes)
                            withContext(Dispatchers.Main) {
                                postHomePageData(freshRes)
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                return
            }
        }
        val res = Anilist.query.initHomePage()
        if (res.isNotEmpty()) {
            Anilist.query.saveHomePageCache(res)
            postHomePageData(res)
        }
    }

    private fun postHomePageData(res: Map<String, ArrayList<Media>>) {
        val library = listOf("currentAnime", "currentAnimePlanned", "currentManga", "currentMangaPlanned")
            .map { res[it] }
        if (library.any { it != null }) {
            nox.kiwami.others.PinnedImages.setLibrary(library)
            nox.kiwami.connections.anilist.MediaDetailsCache.setLibrary(
                library.flatMap { it.orEmpty() }.map { it.id }.filter { it != 0 }
            )
        }
        res["currentAnime"]?.let { postIfChanged("currentAnime", animeContinue, it) }
        res["favoriteAnime"]?.let { postIfChanged("favoriteAnime", animeFav, it) }
        res["currentAnimePlanned"]?.let { postIfChanged("currentAnimePlanned", animePlanned, it) }
        res["currentManga"]?.let { postIfChanged("currentManga", mangaContinue, it) }
        res["favoriteManga"]?.let { postIfChanged("favoriteManga", mangaFav, it) }
        res["currentMangaPlanned"]?.let { postIfChanged("currentMangaPlanned", mangaPlanned, it) }
        res["recommendations"]?.let { postIfChanged("recommendations", recommendation, it) }
        res["missingSequels"]?.let { postIfChanged("missingSequels", missingSequels, it) }
        res["hidden"]?.let { postIfChanged("hidden", hidden, it) }
    }


    suspend fun loadMain(context: FragmentActivity) {
        Anilist.getSavedToken()
        Discord.getSavedToken()
        val ret = Anilist.query.getGenresAndTags()
        withContext(Dispatchers.Main) { genres.value = ret }
    
    }

    val empty = MutableLiveData<Boolean>(null)

    var loaded: Boolean = false
    val genres: MutableLiveData<Boolean?> = MutableLiveData(null)
}

class AnilistAnimeViewModel : ViewModel() {
    var searched = false
    var notSet = true
    lateinit var aniMangaSearchResults: AniMangaSearchResults
    private val type = "ANIME"
    private val trending: MutableLiveData<MutableList<Media>> =
        object : MutableLiveData<MutableList<Media>>(null) {
            override fun postValue(value: MutableList<Media>?) {
                super.postValue(value)
                nox.kiwami.others.PinnedImages.setTrending(true, value)
            }
        }

    fun getTrending(): LiveData<MutableList<Media>> = trending
    suspend fun loadTrending(i: Int) {
        val (season, year) = Anilist.currentSeasons[i]
        trending.postValue(
            Anilist.query.searchAniManga(
                type,
                perPage = 12,
                sort = Anilist.sortBy[2],
                season = season,
                seasonYear = year,
                hd = true,
                adultOnly = PrefManager.getVal(PrefName.AdultOnly)
            )?.results
        )
    }


    private val animePopular = MutableLiveData<AniMangaSearchResults?>(null)

    fun getPopular(): LiveData<AniMangaSearchResults?> = animePopular
    suspend fun loadPopular(
        type: String,
        searchVal: String? = null,
        genres: ArrayList<String>? = null,
        sort: String = Anilist.sortBy[1],
        onList: Boolean = true,
    ) {
        animePopular.postValue(
            Anilist.query.searchAniManga(
                type,
                search = searchVal,
                onList = if (onList) null else false,
                sort = sort,
                genres = genres,
                adultOnly = PrefManager.getVal(PrefName.AdultOnly)
            )
        )
    }


    suspend fun loadNextPage(r: AniMangaSearchResults) {
        animePopular.postValue(
            Anilist.query.searchAniManga(
                r.type,
                r.page + 1,
                r.perPage,
                r.search,
                r.sort,
                r.genres,
                r.tags,
                r.status,
                r.source,
                r.format,
                r.countryOfOrigin,
                r.isAdult,
                r.onList,
                r.excludedGenres,
                r.excludedTags,
                r.startYear,
                r.seasonYear,
                r.season,
                adultOnly = PrefManager.getVal(PrefName.AdultOnly),
            )
        )
    }

    var loaded: Boolean = false
    private val updated: MutableLiveData<MutableList<Media>> =
        MutableLiveData<MutableList<Media>>(null)

    fun getUpdated(): LiveData<MutableList<Media>> = updated

    private val popularMovies: MutableLiveData<MutableList<Media>> =
        MutableLiveData<MutableList<Media>>(null)

    fun getMovies(): LiveData<MutableList<Media>> = popularMovies

    private val topRatedAnime: MutableLiveData<MutableList<Media>> =
        MutableLiveData<MutableList<Media>>(null)

    fun getTopRated(): LiveData<MutableList<Media>> = topRatedAnime

    private val mostFavAnime: MutableLiveData<MutableList<Media>> =
        MutableLiveData<MutableList<Media>>(null)

    fun getMostFav(): LiveData<MutableList<Media>> = mostFavAnime
    suspend fun loadAll(onList: Boolean = PrefManager.getVal(PrefName.PopularAnimeList)) {
        val list = Anilist.query.loadAnimeList(onList)
        updated.postValue(list["recentUpdates"])
        popularMovies.postValue(list["trendingMovies"])
        topRatedAnime.postValue(list["topRated"])
        mostFavAnime.postValue(list["mostFav"])
        list["trending"]?.let { trending.postValue(it) }
        list["popular"]?.let {
            animePopular.postValue(
                AniMangaSearchResults(
                    type = "ANIME",
                    isAdult = PrefManager.getVal(PrefName.AdultOnly),
                    onList = onList,
                    results = it,
                    hasNextPage = true,
                    page = 1,
                    sort = Anilist.sortBy[1]
                )
            )
        }
    }

}

class AnilistMangaViewModel : ViewModel() {
    var searched = false
    var notSet = true
    lateinit var aniMangaSearchResults: AniMangaSearchResults
    private val type = "MANGA"
    private val trending: MutableLiveData<MutableList<Media>> =
        object : MutableLiveData<MutableList<Media>>(null) {
            override fun postValue(value: MutableList<Media>?) {
                super.postValue(value)
                nox.kiwami.others.PinnedImages.setTrending(false, value)
            }
        }

    fun getTrending(): LiveData<MutableList<Media>> = trending
    suspend fun loadTrending() {
        trending.postValue(
            Anilist.query.searchAniManga(
                type,
                perPage = 10,
                sort = Anilist.sortBy[2],
                hd = true,
                adultOnly = PrefManager.getVal(PrefName.AdultOnly)
            )?.results
        )
    }


    private val mangaPopular = MutableLiveData<AniMangaSearchResults?>(null)
    fun getPopular(): LiveData<AniMangaSearchResults?> = mangaPopular
    suspend fun loadPopular(
        type: String,
        searchVal: String? = null,
        genres: ArrayList<String>? = null,
        sort: String = Anilist.sortBy[1],
        onList: Boolean = true,
    ) {
        mangaPopular.postValue(
            Anilist.query.searchAniManga(
                type,
                search = searchVal,
                onList = if (onList) null else false,
                sort = sort,
                genres = genres,
                adultOnly = PrefManager.getVal(PrefName.AdultOnly)
            )
        )
    }


    suspend fun loadNextPage(r: AniMangaSearchResults) {
        mangaPopular.postValue(
            Anilist.query.searchAniManga(
                r.type,
                r.page + 1,
                r.perPage,
                r.search,
                r.sort,
                r.genres,
                r.tags,
                r.status,
                r.source,
                r.format,
                r.countryOfOrigin,
                r.isAdult,
                r.onList,
                r.excludedGenres,
                r.excludedTags,
                r.startYear,
                r.seasonYear,
                r.season,
                adultOnly = PrefManager.getVal(PrefName.AdultOnly)
            )
        )
    }

    var loaded: Boolean = false

    private val popularManga: MutableLiveData<MutableList<Media>> =
        MutableLiveData<MutableList<Media>>(null)

    fun getPopularManga(): LiveData<MutableList<Media>> = popularManga

    private val popularManhwa: MutableLiveData<MutableList<Media>> =
        MutableLiveData<MutableList<Media>>(null)

    fun getPopularManhwa(): LiveData<MutableList<Media>> = popularManhwa

    private val popularNovel: MutableLiveData<MutableList<Media>> =
        MutableLiveData<MutableList<Media>>(null)

    fun getPopularNovel(): LiveData<MutableList<Media>> = popularNovel

    private val topRatedManga: MutableLiveData<MutableList<Media>> =
        MutableLiveData<MutableList<Media>>(null)

    fun getTopRated(): LiveData<MutableList<Media>> = topRatedManga

    private val mostFavManga: MutableLiveData<MutableList<Media>> =
        MutableLiveData<MutableList<Media>>(null)

    fun getMostFav(): LiveData<MutableList<Media>> = mostFavManga
    suspend fun loadAll(onList: Boolean = PrefManager.getVal(PrefName.PopularMangaList)) {
        val list = Anilist.query.loadMangaList(onList)
        popularManga.postValue(list["trendingManga"])
        popularManhwa.postValue(list["trendingManhwa"])
        popularNovel.postValue(list["trendingNovel"])
        topRatedManga.postValue(list["topRated"])
        mostFavManga.postValue(list["mostFav"])
        list["trending"]?.let { trending.postValue(it) }
        list["popular"]?.let {
            mangaPopular.postValue(
                AniMangaSearchResults(
                    type = "MANGA",
                    isAdult = PrefManager.getVal(PrefName.AdultOnly),
                    onList = onList,
                    results = it,
                    hasNextPage = true,
                    page = 1,
                    sort = Anilist.sortBy[1]
                )
            )
        }
    }

}

class AnilistSearch : ViewModel() {

    enum class SearchType {
        ANIME, MANGA, CHARACTER, STAFF, STUDIO, USER;

        companion object {

            fun SearchType.toAnilistString(): String {
                return when (this) {
                    ANIME -> "ANIME"
                    MANGA -> "MANGA"
                    CHARACTER -> "CHARACTER"
                    STAFF -> "STAFF"
                    STUDIO -> "STUDIO"
                    USER -> "USER"
                }
            }

            fun fromString(string: String): SearchType {
                return when (string.uppercase()) {
                    "ANIME" -> ANIME
                    "MANGA" -> MANGA
                    "CHARACTER" -> CHARACTER
                    "STAFF" -> STAFF
                    "STUDIO" -> STUDIO
                    "USER" -> USER
                    else -> throw IllegalArgumentException("Invalid search type")
                }
            }
        }
    }

    var searched = false
    var notSet = true
    lateinit var aniMangaSearchResults: AniMangaSearchResults
    private val aniMangaResult: MutableLiveData<AniMangaSearchResults?> =
        MutableLiveData<AniMangaSearchResults?>(null)

    lateinit var characterSearchResults: CharacterSearchResults
    private val characterResult: MutableLiveData<CharacterSearchResults?> =
        MutableLiveData<CharacterSearchResults?>(null)

    lateinit var studioSearchResults: StudioSearchResults
    private val studioResult: MutableLiveData<StudioSearchResults?> =
        MutableLiveData<StudioSearchResults?>(null)

    lateinit var staffSearchResults: StaffSearchResults
    private val staffResult: MutableLiveData<StaffSearchResults?> =
        MutableLiveData<StaffSearchResults?>(null)

    lateinit var userSearchResults: UserSearchResults
    private val userResult: MutableLiveData<UserSearchResults?> =
        MutableLiveData<UserSearchResults?>(null)

    fun <T> getSearch(type: SearchType): MutableLiveData<T?> {
        return when (type) {
            SearchType.ANIME, SearchType.MANGA -> aniMangaResult as MutableLiveData<T?>
            SearchType.CHARACTER -> characterResult as MutableLiveData<T?>
            SearchType.STUDIO -> studioResult as MutableLiveData<T?>
            SearchType.STAFF -> staffResult as MutableLiveData<T?>
            SearchType.USER -> userResult as MutableLiveData<T?>
        }
    }

    suspend fun loadSearch(type: SearchType) {
        when (type) {
            SearchType.ANIME, SearchType.MANGA -> loadAniMangaSearch(aniMangaSearchResults)
            SearchType.CHARACTER -> loadCharacterSearch(characterSearchResults)
            SearchType.STUDIO -> loadStudiosSearch(studioSearchResults)
            SearchType.STAFF -> loadStaffSearch(staffSearchResults)
            SearchType.USER -> loadUserSearch(userSearchResults)
        }
    }

    suspend fun loadNextPage(type: SearchType) {
        when (type) {
            SearchType.ANIME, SearchType.MANGA -> loadNextAniMangaPage(aniMangaSearchResults)
            SearchType.CHARACTER -> loadNextCharacterPage(characterSearchResults)
            SearchType.STUDIO -> loadNextStudiosPage(studioSearchResults)
            SearchType.STAFF -> loadNextStaffPage(staffSearchResults)
            SearchType.USER -> loadNextUserPage(userSearchResults)
        }
    }

    fun hasNextPage(type: SearchType): Boolean {
        return when (type) {
            SearchType.ANIME, SearchType.MANGA -> aniMangaSearchResults.hasNextPage
            SearchType.CHARACTER -> characterSearchResults.hasNextPage
            SearchType.STUDIO -> studioSearchResults.hasNextPage
            SearchType.STAFF -> staffSearchResults.hasNextPage
            SearchType.USER -> userSearchResults.hasNextPage
        }
    }

    fun resultsIsNotEmpty(type: SearchType): Boolean {
        return when (type) {
            SearchType.ANIME, SearchType.MANGA -> aniMangaSearchResults.results.isNotEmpty()
            SearchType.CHARACTER -> characterSearchResults.results.isNotEmpty()
            SearchType.STUDIO -> studioSearchResults.results.isNotEmpty()
            SearchType.STAFF -> staffSearchResults.results.isNotEmpty()
            SearchType.USER -> userSearchResults.results.isNotEmpty()
        }
    }

    fun size(type: SearchType): Int {
        return when (type) {
            SearchType.ANIME, SearchType.MANGA -> aniMangaSearchResults.results.size
            SearchType.CHARACTER -> characterSearchResults.results.size
            SearchType.STUDIO -> studioSearchResults.results.size
            SearchType.STAFF -> staffSearchResults.results.size
            SearchType.USER -> userSearchResults.results.size
        }
    }

    fun clearResults(type: SearchType) {
        when (type) {
            SearchType.ANIME, SearchType.MANGA -> aniMangaSearchResults.results.clear()
            SearchType.CHARACTER -> characterSearchResults.results.clear()
            SearchType.STUDIO -> studioSearchResults.results.clear()
            SearchType.STAFF -> staffSearchResults.results.clear()
            SearchType.USER -> userSearchResults.results.clear()
        }
    }

    private suspend fun loadAniMangaSearch(r: AniMangaSearchResults) {
        aniMangaResult.postValue(
            Anilist.query.searchAniManga(
                r.type,
                r.page,
                r.perPage,
                r.search,
                r.sort,
                r.genres,
                r.tags,
                r.status,
                r.source,
                r.format,
                r.countryOfOrigin,
                r.isAdult,
                r.onList,
                r.excludedGenres,
                r.excludedTags,
                r.startYear,
                r.seasonYear,
                r.season,
            )
        )
    }

    private suspend fun loadCharacterSearch(r: CharacterSearchResults) {
        characterResult.postValue(Anilist.query.searchCharacters(r.page, r.search))
    }

    private suspend fun loadStudiosSearch(r: StudioSearchResults) {
        studioResult.postValue(Anilist.query.searchStudios(r.page, r.search))
    }

    private suspend fun loadStaffSearch(r: StaffSearchResults) {
        staffResult.postValue(Anilist.query.searchStaff(r.page, r.search))
    }

    private suspend fun loadUserSearch(r: UserSearchResults) {
        userResult.postValue(Anilist.query.searchUsers(r.page, r.search))
    }

    private suspend fun loadNextAniMangaPage(r: AniMangaSearchResults) {
        aniMangaResult.postValue(
            Anilist.query.searchAniManga(
                r.type,
                r.page + 1,
                r.perPage,
                r.search,
                r.sort,
                r.genres,
                r.tags,
                r.status,
                r.source,
                r.format,
                r.countryOfOrigin,
                r.isAdult,
                r.onList,
                r.excludedGenres,
                r.excludedTags,
                r.startYear,
                r.seasonYear,
                r.season
            )
        )
    }

    private suspend fun loadNextCharacterPage(r: CharacterSearchResults) {
        characterResult.postValue(Anilist.query.searchCharacters(r.page + 1, r.search))
    }

    private suspend fun loadNextStudiosPage(r: StudioSearchResults) {
        studioResult.postValue(Anilist.query.searchStudios(r.page + 1, r.search))
    }

    private suspend fun loadNextStaffPage(r: StaffSearchResults) {
        staffResult.postValue(Anilist.query.searchStaff(r.page + 1, r.search))
    }

    private suspend fun loadNextUserPage(r: UserSearchResults) {
        userResult.postValue(Anilist.query.searchUsers(r.page + 1, r.search))
    }
}

class GenresViewModel : ViewModel() {
    var genres: MutableMap<String, String>? = null
    var done = false
    var doneListener: (() -> Unit)? = null
    suspend fun loadGenres(genre: ArrayList<String>, listener: (Pair<String, String>) -> Unit) {
        if (genres == null) {
            genres = mutableMapOf()
            Anilist.query.getGenres(genre) {
                genres!![it.first] = it.second
                listener.invoke(it)
                if (genres!!.size == genre.size) {
                    done = true
                    doneListener?.invoke()
                }
            }
        
        }
    }

}

class ProfileViewModel : ViewModel() {

    private val mangaFav: MutableLiveData<ArrayList<Media>> =
        MutableLiveData<ArrayList<Media>>(null)

    fun getMangaFav(): LiveData<ArrayList<Media>> = mangaFav

    private val animeFav: MutableLiveData<ArrayList<Media>> =
        MutableLiveData<ArrayList<Media>>(null)

    fun getAnimeFav(): LiveData<ArrayList<Media>> = animeFav

    suspend fun setData(id: Int) {
        val res = Anilist.query.initProfilePage(id)
        val mangaList = res?.data?.favoriteManga?.favourites?.manga?.edges?.mapNotNull {
            it.node?.let { i -> Media(i) }
        }
        mangaFav.postValue(ArrayList(mangaList ?: arrayListOf()))
        val animeList = res?.data?.favoriteAnime?.favourites?.anime?.edges?.mapNotNull {
            it.node?.let { i -> Media(i) }
        }
        animeFav.postValue(ArrayList(animeList ?: arrayListOf()))

    }

    fun refresh() {
        mangaFav.postValue(mangaFav.value)
        animeFav.postValue(animeFav.value)

    }
}
