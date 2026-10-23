package ani.dantotsu.connections.anilist

import ani.dantotsu.Mapper
import ani.dantotsu.connections.anilist.api.Query
import ani.dantotsu.currContext
import ani.dantotsu.others.SmartCache
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * (Only active while Smart cache is turned on in Settings → Data and Storage.)
 * On-disk cache for the AniList "full details" response of an anime/manga page.
 *
 * - Entries are kept for 30 days and at most [MAX_FILES] titles are stored.
 * - Titles in your library (watching/reading + planned) are the last to be thrown out.
 * - Lives in the app's cache folder, so Android can still reclaim it if storage runs very low.
 * - A title's entry is removed when you edit that title in your list or favourites.
 */
object MediaDetailsCache {
    private const val TTL_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
    private const val MAX_FILES = 200
    private const val LIBRARY_FILE = "_library.txt"

    @Volatile
    private var libraryIds: Set<String>? = null

    private fun dir(): File? {
        val base = currContext()?.cacheDir ?: return null
        return File(base, "media_details").apply { mkdirs() }
    }

    private fun entry(id: Int): File? = dir()?.let { File(it, "$id.json") }

    private fun library(): Set<String> {
        libraryIds?.let { return it }
        val loaded = runCatching {
            File(dir() ?: return emptySet(), LIBRARY_FILE).readLines().toSet()
        }.getOrDefault(emptySet())
        libraryIds = loaded
        return loaded
    }

    /** Remember which titles are in the library so they are pruned last. */
    fun setLibrary(ids: Collection<Int>) {
        if (!SmartCache.isEnabled()) return
        runCatching {
            val set = ids.map { it.toString() }.toSet()
            libraryIds = set
            File(dir() ?: return, LIBRARY_FILE).writeText(set.joinToString("\n"))
        }
    }

    fun has(id: Int): Boolean = SmartCache.isEnabled() && runCatching {
        val f = entry(id) ?: return false
        f.exists() && System.currentTimeMillis() - f.lastModified() <= TTL_MS
    }.getOrDefault(false)

    fun get(id: Int): Query.Media? = if (!SmartCache.isEnabled()) null else runCatching {
        val f = entry(id) ?: return null
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > TTL_MS) return null
        Mapper.json.decodeFromString<Query.Media>(f.readText())
    }.getOrNull()

    fun put(id: Int, response: Query.Media) {
        if (!SmartCache.isEnabled()) return
        runCatching {
            val d = dir() ?: return
            File(d, "$id.json").writeText(Mapper.json.encodeToString(response))
            prune(d)
        }
    }

    /** Throws out the oldest non-library titles first, library titles only as a last resort. */
    private fun prune(d: File) {
        val files = d.listFiles { f -> f.name.endsWith(".json") } ?: return
        val excess = files.size - MAX_FILES
        if (excess <= 0) return
        val lib = library()
        files.sortedWith(
            compareBy<File>({ it.nameWithoutExtension in lib }, { it.lastModified() })
        ).take(excess).forEach { it.delete() }
    }

    fun remove(id: Int) {
        runCatching { entry(id)?.delete() }
    }

    fun clear() {
        runCatching {
            dir()?.listFiles { f -> f.name.endsWith(".json") }?.forEach { it.delete() }
        }
    }

    /**
     * A short summary of the details that matter to the reader (status, episode/chapter counts,
     * next airing episode, title, synopsis, dates). Popularity, scores etc. change constantly
     * and are left out on purpose so the "outdated" notice only appears for real changes.
     */
    fun fingerprint(r: Query.Media): String {
        val m = r.data?.media ?: return ""
        return listOf(
            m.status, m.episodes, m.chapters, m.volumes, m.nextAiringEpisode?.episode, m.format,
            m.title?.romaji, m.title?.english, m.description?.hashCode(),
            m.startDate?.toString(), m.endDate?.toString()
        ).joinToString("|")
    }
}
