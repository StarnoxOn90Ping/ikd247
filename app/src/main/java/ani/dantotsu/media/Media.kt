package ani.dantotsu.media

import android.graphics.Bitmap
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.anilist.api.FuzzyDate
import ani.dantotsu.connections.anilist.api.MediaEdge
import ani.dantotsu.connections.anilist.api.MediaExternalLink
import ani.dantotsu.connections.anilist.api.ExternalLinkType
import ani.dantotsu.connections.anilist.api.MediaList
import ani.dantotsu.connections.anilist.api.MediaStreamingEpisode
import ani.dantotsu.connections.anilist.api.MediaType
import ani.dantotsu.connections.anilist.api.Query
import ani.dantotsu.media.anime.Anime
import ani.dantotsu.media.manga.Manga
import ani.dantotsu.profile.User
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable
import java.util.Locale
import ani.dantotsu.connections.anilist.api.Media as ApiMedia

data class Media(
    val anime: Anime? = null,
    val manga: Manga? = null,
    val id: Int,

    var idMAL: Int? = null,

    val name: String?,
    val nameRomaji: String,
    val userPreferredName: String,

    var cover: String? = null,
    var banner: String? = null,
    var clearLogo: String? = null,
    var relation: String? = null,
    var favourites: Int? = null,

    var isAdult: Boolean,
    var isFav: Boolean = false,
    var notify: Boolean = false,

    var userListId: Int? = null,
    var isListPrivate: Boolean = false,
    var notes: String? = null,
    var userProgress: Int? = null,
    var userProgressVolumes: Int? = null,
    var userStatus: String? = null,
    var userScore: Int = 0,
    var userRepeat: Int = 0,
    var userUpdatedAt: Long? = null,
    var userStartedAt: FuzzyDate = FuzzyDate(),
    var userCompletedAt: FuzzyDate = FuzzyDate(),
    var inCustomListsOf: MutableMap<String, Boolean>? = null,
    var userFavOrder: Int? = null,

    var status: String? = null,
    var format: String? = null,
    var source: String? = null,
    var countryOfOrigin: String? = null,
    var meanScore: Int? = null,
    var genres: ArrayList<String> = arrayListOf(),
    var tags: ArrayList<String> = arrayListOf(),
    var tagsIsSpoiler: ArrayList<Boolean> = arrayListOf(),
    var description: String? = null,
    var synonyms: ArrayList<String> = arrayListOf(),
    var trailer: String? = null,
    var startDate: FuzzyDate? = null,
    var endDate: FuzzyDate? = null,
    var popularity: Int? = null,

    var timeUntilAiring: Long? = null,

    var characters: ArrayList<Character>? = null,
    var review: ArrayList<Query.Review>? = null,
    var staff: ArrayList<Author>? = null,
    var prequel: Media? = null,
    var sequel: Media? = null,
    var relations: ArrayList<Media>? = null,
    var recommendations: ArrayList<Media>? = null,
    @Transient var recommendationList: ArrayList<ani.dantotsu.connections.anilist.api.Recommendation>? = null,
    @Transient var stats: ani.dantotsu.connections.anilist.api.MediaStats? = null,
    @Transient var rankings: List<ani.dantotsu.connections.anilist.api.MediaRank>? = null,
    var users: ArrayList<User>? = null,
    var vrvId: String? = null,
    var crunchySlug: String? = null,

    var folderName: String? = null,
    var shareLink: String? = null,
    var selected: Selected? = null,
    var streamingEpisodes: List<MediaStreamingEpisode>? = null,
    var idKitsu: String? = null,
    var externalLinks: ArrayList<MediaExternalLink>? = null,
    var idIMDB: String? = null,
    var idTMDB: String? = null,

    var cameFromContinue: Boolean = false
) : Serializable {

    constructor(apiMedia: ApiMedia) : this(
        id = apiMedia.id,
        idMAL = apiMedia.idMal,
        popularity = apiMedia.popularity,
        name = apiMedia.title!!.english,
        nameRomaji = apiMedia.title!!.romaji,
        userPreferredName = apiMedia.title!!.userPreferred,
        cover = apiMedia.coverImage?.large ?: apiMedia.coverImage?.medium,
        banner = apiMedia.bannerImage,
        status = apiMedia.status.toString(),
        isFav = apiMedia.isFavourite!!,
        isAdult = apiMedia.isAdult ?: false,
        isListPrivate = apiMedia.mediaListEntry?.private ?: false,
        userProgress = apiMedia.mediaListEntry?.progress,
        userProgressVolumes = apiMedia.mediaListEntry?.progressVolumes,
        userScore = apiMedia.mediaListEntry?.score?.toInt() ?: 0,
        userStatus = apiMedia.mediaListEntry?.status?.toString(),
        meanScore = apiMedia.meanScore,
        startDate = apiMedia.startDate,
        endDate = apiMedia.endDate,
        favourites = apiMedia.favourites,
        timeUntilAiring = apiMedia.nextAiringEpisode?.timeUntilAiring?.let { it.toLong() * 1000 },
        anime = if (apiMedia.type == MediaType.ANIME) Anime(
            totalEpisodes = apiMedia.episodes,
            nextAiringEpisode = apiMedia.nextAiringEpisode?.episode?.minus(1)
        ) else null,
        manga = if (apiMedia.type == MediaType.MANGA) Manga(
            totalChapters = apiMedia.chapters,
            totalVolumes = apiMedia.volumes
        ) else null,
        format = apiMedia.format?.toString(),
        description = apiMedia.description,
        genres = ArrayList(apiMedia.genres ?: emptyList()),
    ) {
        apiMedia.genres?.let { genreList ->
            this.genres = ArrayList(genreList)
        }
        val studioEdges = apiMedia.studios?.edges
        if (!studioEdges.isNullOrEmpty()) {
            val mainNode = studioEdges.firstOrNull { it.isMain == true }?.node
                ?: studioEdges.firstOrNull { it.node?.isAnimationStudio == true }?.node
                ?: studioEdges.firstOrNull()?.node
            if (mainNode != null) {
                this.anime?.mainStudio = Studio(
                    id = mainNode.id.toString(),
                    name = mainNode.name ?: "N/A",
                    isFavourite = mainNode.isFavourite ?: false,
                    favourites = mainNode.favourites ?: 0,
                    imageUrl = null
                )
            }
            val producerNodes = studioEdges.filter { it.isMain != true }.mapNotNull { it.node }
                .filter { it.id.toString() != this.anime?.mainStudio?.id }
            if (producerNodes.isNotEmpty()) {
                this.anime?.producers = ArrayList(producerNodes.map {
                    Studio(
                        id = it.id.toString(),
                        name = it.name ?: "N/A",
                        isFavourite = it.isFavourite ?: false,
                        favourites = it.favourites ?: 0,
                        imageUrl = null
                    )
                })
            }
        } else {
            apiMedia.studios?.nodes?.let { nodes ->
                if (nodes.isNotEmpty()) {
                    val studioNode = nodes.firstOrNull { it.isAnimationStudio == true } ?: nodes[0]
                    this.anime?.mainStudio = Studio(
                        id = studioNode.id.toString(),
                        name = studioNode.name ?: "N/A",
                        isFavourite = studioNode.isFavourite ?: false,
                        favourites = studioNode.favourites ?: 0,
                        imageUrl = null
                    )
                }
            }
            apiMedia.producers?.nodes?.let { nodes ->
                if (nodes.isNotEmpty()) {
                    this.anime?.producers = ArrayList(nodes.map {
                        Studio(
                            id = it.id.toString(),
                            name = it.name ?: "N/A",
                            isFavourite = it.isFavourite ?: false,
                            favourites = it.favourites ?: 0,
                            imageUrl = null
                        )
                    })
                }
            }
        }
        apiMedia.tags?.let { tagList ->
            this.tags = ArrayList(tagList.mapNotNull { it.name })
            this.tagsIsSpoiler = ArrayList(tagList.map { it.isMediaSpoiler == true })
        }
    }

    constructor(mediaList: MediaList) : this(mediaList.media!!) {
        this.userProgress = mediaList.progress
        this.userProgressVolumes = mediaList.progressVolumes
        this.isListPrivate = mediaList.private ?: false
        this.userScore = mediaList.score?.toInt() ?: 0
        this.userStatus = mediaList.status?.toString()
        this.userUpdatedAt = mediaList.updatedAt?.toLong()
        this.userStartedAt = mediaList.startedAt ?: FuzzyDate()
        this.userCompletedAt = mediaList.completedAt ?: FuzzyDate()
        this.genres =
            mediaList.media?.genres?.toMutableList() as? ArrayList<String>? ?: arrayListOf()
        mediaList.media?.tags?.let { tagList ->
            this.tags = ArrayList(tagList.mapNotNull { it.name })
            this.tagsIsSpoiler = ArrayList(tagList.map { it.isMediaSpoiler == true })
        }
    }

    constructor(mediaEdge: MediaEdge) : this(mediaEdge.node!!) {
        this.relation = mediaEdge.relationType?.toString()
    }

    fun mainName() = name ?: nameRomaji
    fun mangaName() = if (countryOfOrigin != "JP") mainName() else nameRomaji
}

fun Media?.deleteFromList(
    scope: CoroutineScope,
    onSuccess: suspend () -> Unit,
    onError: suspend (e: Exception) -> Unit,
    onNotFound: suspend () -> Unit
) {
    val id = this?.userListId
    scope.launch {
        withContext(Dispatchers.IO) {
            this@deleteFromList?.let { media ->
                val _id = id ?: Anilist.query.userMediaDetails(media).userListId
                _id?.let { listId ->
                    try {
                        Anilist.mutation.deleteList(listId)

                        val removeList = PrefManager.getCustomVal<Set<String>>("removeList", emptySet())
                        PrefManager.setCustomVal(
                            "removeList", removeList.minus(media.id.toString())
                        )

                        onSuccess()
                    } catch (e: Exception) {
                        onError(e)
                    }
                } ?: onNotFound()
            }
        }
    }
}

fun emptyMedia() = Media(
    id = 0,
    name = "No media found",
    nameRomaji = "No media found",
    userPreferredName = "",
    isAdult = false,
    isFav = false,
    isListPrivate = false,
    userScore = 0,
    userStatus = "",
    format = "",
)

object MediaSingleton {
    var media: Media? = null
    var bitmap: Bitmap? = null
}
