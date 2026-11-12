package nox.kiwami.media.user

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import nox.kiwami.connections.anilist.Anilist
import nox.kiwami.media.Media
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import nox.kiwami.tryWithSuspend

class ListViewModel : ViewModel() {
    var grid = MutableLiveData(PrefManager.getVal<Boolean>(PrefName.ListGrid))

    private val lists = MutableLiveData<MutableMap<String, ArrayList<Media>>>()
    private val unfilteredLists = MutableLiveData<MutableMap<String, ArrayList<Media>>>()
    fun getLists(): LiveData<MutableMap<String, ArrayList<Media>>> = lists
    suspend fun loadLists(anime: Boolean, userId: Int, sortOrder: String? = null) {
        tryWithSuspend {
            val res = Anilist.query.getMediaLists(anime, userId, sortOrder)
            lists.postValue(res)
            unfilteredLists.postValue(res)
        }
    }

    fun filterLists(genre: String) {
        if (genre == "All") {
            lists.postValue(unfilteredLists.value)
            return
        }
        val currentLists = unfilteredLists.value ?: return
        val filteredLists = currentLists.mapValues { entry ->
            entry.value.filter { media ->
                genre in media.genres
            } as ArrayList<Media>
        }.toMutableMap()

        lists.postValue(filteredLists)
    }

    fun filterListsByTag(tag: String) {
        if (tag == "All") {
            lists.postValue(unfilteredLists.value)
            return
        }
        val currentLists = unfilteredLists.value ?: return
        val filteredLists = currentLists.mapValues { entry ->
            entry.value.filter { media ->
                tag in media.tags
            } as ArrayList<Media>
        }.toMutableMap()

        lists.postValue(filteredLists)
    }

    fun getAllTags(): List<String> {
        val allMedia = unfilteredLists.value?.values?.flatten() ?: return emptyList()
        return allMedia.flatMap { it.tags }.distinct().sorted()
    }

    fun getAllGenres(): List<String> {
        val allMedia = unfilteredLists.value?.values?.flatten() ?: return emptyList()
        val listGenres = allMedia.flatMap { it.genres }.distinct().sorted()
        return if (listGenres.isNotEmpty()) listGenres else PrefManager.getVal<Set<String>>(PrefName.GenresList).sorted()
    }

    fun searchLists(search: String) {
        if (search.isEmpty()) {
            lists.postValue(unfilteredLists.value)
            return
        }
        val currentLists = unfilteredLists.value ?: return
        val filteredLists = currentLists.mapValues { entry ->
            entry.value.filter { media ->
                media.name?.contains(
                    search,
                    ignoreCase = true
                ) == true || media.synonyms.any { it.contains(search, ignoreCase = true) } ||
                        media.nameRomaji.contains(
                            search,
                            ignoreCase = true
                        )
            } as ArrayList<Media>
        }.toMutableMap()

        lists.postValue(filteredLists)
    }

    fun unfilterLists() {
        lists.postValue(unfilteredLists.value)
    }

}
