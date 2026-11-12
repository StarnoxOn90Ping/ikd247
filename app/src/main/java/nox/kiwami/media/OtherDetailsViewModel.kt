package nox.kiwami.media

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import nox.kiwami.client
import nox.kiwami.Mapper
import nox.kiwami.connections.anilist.Anilist
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import nox.kiwami.tryWithSuspend
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import nox.kiwami.media.anime.Anime
import nox.kiwami.media.manga.Manga

class OtherDetailsViewModel : ViewModel() {
    companion object {
        private const val DUB_FEED_URL =
            "https://raw.githubusercontent.com/RockinChaos/AniSchedule/master/readable/dub-episode-feed-readable.json"
    }

    private val character: MutableLiveData<Character> = MutableLiveData(null)
    fun getCharacter(): LiveData<Character> = character
    suspend fun loadCharacter(m: Character) {
        if (character.value != null) return
        character.postValue(Anilist.query.getCharacterDetails(m))
    }

    private val studio: MutableLiveData<Studio> = MutableLiveData(null)
    fun getStudio(): LiveData<Studio> = studio
    suspend fun loadStudio(m: Studio) {
        if (studio.value != null) return
        if (studio.value == null) studio.postValue(Anilist.query.getStudioDetails(m))
    }

    private val author: MutableLiveData<Author> = MutableLiveData(null)
    fun getAuthor(): LiveData<Author> = author
    suspend fun loadAuthor(m: Author) {
        if (author.value != null) return
        author.postValue(Anilist.query.getAuthorDetails(m))
    }

    private var cachedAllCalendarData: Map<String, MutableList<Media>>? = null
    private var cachedLibraryCalendarData: Map<String, MutableList<Media>>? = null
    private var cachedDubAnilistIds: Set<Int>? = null
    private var cachedDubMalIds: Set<Int>? = null
    private val calendar: MutableLiveData<Map<String, MutableList<Media>>> = MutableLiveData(null)
    fun getCalendar(): LiveData<Map<String, MutableList<Media>>> = calendar
    suspend fun loadCalendar(showOnlyLibrary: Boolean = false, showOnlyDubbed: Boolean = false) {
        loadCalendarFromAnilist(showOnlyLibrary, showOnlyDubbed)
    }

    private suspend fun loadCalendarFromAnilist(showOnlyLibrary: Boolean, showOnlyDubbed: Boolean) {
        if (cachedAllCalendarData == null || cachedLibraryCalendarData == null) {
            val curr = System.currentTimeMillis() / 1000
            val res = Anilist.query.recentlyUpdated(curr - 86400, curr + (86400 * 6))
            val df = DateFormat.getDateInstance(DateFormat.FULL)
            val tf = DateFormat.getTimeInstance(DateFormat.SHORT)
            val allMap = mutableMapOf<String, MutableList<Media>>()
            val libraryMap = mutableMapOf<String, MutableList<Media>>()
            val idMap = mutableMapOf<String, MutableList<Int>>()

            val userId = Anilist.userid ?: 0
            val userLibrary = Anilist.query.getMediaLists(true, userId)
            val libraryMediaIds = userLibrary.flatMap { it.value }.map { it.id }

            res.forEach {
                val v = it.relation?.split(",")?.map { i -> i.toLong() }!!
                val dateInfo = df.format(Date(v[1] * 1000))
                val timeInfo = tf.format(Date(v[1] * 1000))
                val list = allMap.getOrPut(dateInfo) { mutableListOf() }
                val libraryList = if (libraryMediaIds.contains(it.id)) {
                    libraryMap.getOrPut(dateInfo) { mutableListOf() }
                } else {
                    null
                }
                val idList = idMap.getOrPut(dateInfo) { mutableListOf() }
                it.relation = "Episode ${v[0]}\n$timeInfo"
                if (!idList.contains(it.id)) {
                    idList.add(it.id)
                    list.add(it)
                    libraryList?.add(it)
                }
            }

            cachedAllCalendarData = allMap
            cachedLibraryCalendarData = libraryMap
        }

        var cacheToUse: Map<String, MutableList<Media>> = if (showOnlyLibrary) {
            cachedLibraryCalendarData ?: emptyMap()
        } else {
            cachedAllCalendarData ?: emptyMap()
        }
        if (showOnlyDubbed) {
            cacheToUse = applyDubFilter(cacheToUse)
        }
        calendar.postValue(cacheToUse)
    }

    private suspend fun applyDubFilter(
        data: Map<String, MutableList<Media>>
    ): Map<String, MutableList<Media>> {
        ensureDubDataLoaded()
        val dubAnilistIds = cachedDubAnilistIds ?: emptySet()
        val dubMalIds = cachedDubMalIds ?: emptySet()

        val filteredMap = linkedMapOf<String, MutableList<Media>>()
        data.forEach { (date, mediaList) ->
            val filteredList = mediaList.filter { media ->
                dubAnilistIds.contains(media.id) || (media.idMAL != null && dubMalIds.contains(media.idMAL))
            }.toMutableList()
            if (filteredList.isNotEmpty()) {
                filteredMap[date] = filteredList
            }
        }
        return filteredMap
    }

    private suspend fun ensureDubDataLoaded() {
        if (cachedDubAnilistIds != null && cachedDubMalIds != null) return

        val feedItems = tryWithSuspend {
            val response = client.get(DUB_FEED_URL)
            Mapper.json.decodeFromString<List<DubEpisodeFeedItem>>(response.text)
        } ?: emptyList()

        if (feedItems.isNotEmpty()) {
            cachedDubAnilistIds = feedItems.mapNotNull { it.id }.toSet()
            cachedDubMalIds = feedItems.mapNotNull { it.idMal }.toSet()
        }
    }
}

@Serializable
private data class DubEpisodeFeedItem(
    @SerialName("id") val id: Int? = null,
    @SerialName("idMal") val idMal: Int? = null,
)
