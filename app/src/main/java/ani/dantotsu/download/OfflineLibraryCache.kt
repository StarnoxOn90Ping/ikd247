package ani.dantotsu.download

import android.content.Context
import android.net.Uri
import ani.dantotsu.download.anime.OfflineAnimeModel
import ani.dantotsu.download.manga.OfflineMangaModel
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers the cards shown on the downloads pages (anime / manga / novels).
 *
 * Building one card means walking the download folder through the storage access framework,
 * looking for cover.jpg / banner.jpg and parsing media.json, which is slow when there are many
 * titles. That used to happen for every title, one after another, every time a page was opened,
 * resumed, refreshed or its view style was changed. Now each title is built once, kept in memory
 * and mirrored to a small file in the cache folder, so the pages can show instantly and only
 * missing titles have to be loaded.
 *
 * Entries are tied to the current download location (a different location starts empty) and are
 * dropped when a title's downloads change (see [invalidate]) or the index is rebuilt ([clear]).
 */
object OfflineLibraryCache {
    private const val FILE_NAME = "offline_library_cache_v1.json"

    private class UriAdapter : JsonSerializer<Uri>, JsonDeserializer<Uri> {
        override fun serialize(src: Uri, typeOfSrc: Type, context: JsonSerializationContext) =
            JsonPrimitive(src.toString())

        override fun deserialize(
            json: JsonElement, typeOfT: Type, context: JsonDeserializationContext
        ): Uri = Uri.parse(json.asString)
    }

    private class Snapshot(
        val dir: String,
        val anime: Map<String, OfflineAnimeModel>,
        val manga: Map<String, OfflineMangaModel>
    )

    private val gson = GsonBuilder()
        .registerTypeHierarchyAdapter(Uri::class.java, UriAdapter())
        .create()

    private val anime = ConcurrentHashMap<String, OfflineAnimeModel>()
    private val manga = ConcurrentHashMap<String, OfflineMangaModel>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    private val stateLock = Any()
    private var loadedFromDisk = false
    private var currentDir: String? = null

    private fun downloadsDir(): String = PrefManager.getVal<String>(PrefName.DownloadsDir)

    private fun mangaKey(type: String, title: String) = "$type|$title"

    /** Loads the file once per process and resets everything when the download location changed. */
    fun ensureLoaded(context: Context) {
        synchronized(stateLock) {
            val dir = downloadsDir()
            if (currentDir != null && currentDir != dir) {
                anime.clear()
                manga.clear()
                currentDir = dir
            }
            if (loadedFromDisk) return
            loadedFromDisk = true
            currentDir = dir
            try {
                val file = File(context.applicationContext.cacheDir, FILE_NAME)
                if (!file.exists()) return
                val snapshot = gson.fromJson(file.readText(), Snapshot::class.java) ?: return
                if (snapshot.dir != dir) return
                anime.putAll(snapshot.anime)
                manga.putAll(snapshot.manga)
            } catch (e: Exception) {
                // a broken cache is only a slower first load, never a problem
                Logger.log("OfflineLibraryCache: ignoring unreadable cache (${e.message})")
            }
        }
    }

    fun getAnime(title: String): OfflineAnimeModel? = anime[title]

    fun getManga(type: String, title: String): OfflineMangaModel? = manga[mangaKey(type, title)]

    /**
     * Returns the cached card for [title], or builds it with [load] (at most once even when several
     * loads ask for the same title at the same time) and remembers it.
     * Cards built from the "could not read this title" fallback are not remembered, so they get
     * another chance next time.
     */
    suspend fun animeOrLoad(
        title: String,
        load: suspend () -> OfflineAnimeModel
    ): OfflineAnimeModel {
        anime[title]?.let { return it }
        return locks.getOrPut("A|$title") { Mutex() }.withLock {
            anime[title] ?: load().also { if (it.episodes != FALLBACK) anime[title] = it }
        }
    }

    suspend fun mangaOrLoad(
        type: String,
        title: String,
        load: suspend () -> OfflineMangaModel
    ): OfflineMangaModel {
        val key = mangaKey(type, title)
        manga[key]?.let { return it }
        return locks.getOrPut("M|$key") { Mutex() }.withLock {
            manga[key] ?: load().also { if (it.chapters != FALLBACK) manga[key] = it }
        }
    }

    /** A title's downloads changed (episode/chapter added or removed): rebuild its card next time. */
    fun invalidate(title: String) {
        val valid = title.findValidName()
        anime.remove(valid)
        anime.remove(title)
        for (type in listOf("manga", "novel", "MANGA", "NOVEL")) {
            manga.remove(mangaKey(type, valid))
            manga.remove(mangaKey(type, title))
        }
    }

    fun clearAnime() = anime.clear()

    fun clearManga() = manga.clear()

    /** Forget everything (download location changed, index rebuilt). */
    fun clear() {
        anime.clear()
        manga.clear()
        synchronized(stateLock) { currentDir = downloadsDir() }
    }

    /** Writes the cache file. Call from a background thread. */
    fun persist(context: Context) {
        try {
            val snapshot = Snapshot(downloadsDir(), HashMap(anime), HashMap(manga))
            val dir = context.applicationContext.cacheDir
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(gson.toJson(snapshot))
            if (!tmp.renameTo(File(dir, FILE_NAME))) tmp.delete()
        } catch (e: Exception) {
            Logger.log("OfflineLibraryCache: could not save cache (${e.message})")
        }
    }

    /** `episodes` / `chapters` value of the card the fragments build when a title can't be read. */
    const val FALLBACK = "hmm"
}
