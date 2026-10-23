package ani.dantotsu.others

import ani.dantotsu.currContext
import ani.dantotsu.media.Media
import ani.dantotsu.okHttpClient
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps covers and banners that must not disappear in their own folder, outside Glide's
 * normal "oldest picture goes first" cache (only while Smart cache is turned on):
 *
 *  1. Library (watching/reading + planned): always kept, removed only when you remove the title.
 *  2. Trending: kept only while there is room, and replaced whenever the trending lists change.
 *
 * Everything else uses Glide's own cache. Pinned images get 30% of the Smart cache limit and
 * Glide gets the other 70%, so the total stays within the limit the user chose.
 */
object PinnedImages {
    private const val LIB = "L_"
    private const val TREND = "T_"

    @Volatile private var libraryUrls: Set<String> = emptySet()
    @Volatile private var trendingAnime: Set<String> = emptySet()
    @Volatile private var trendingManga: Set<String> = emptySet()

    private val running = AtomicBoolean(false)
    @Volatile private var pending = false

    private fun dir(): File? {
        val base = currContext()?.cacheDir ?: return null
        return File(base, "pinned_images").apply { mkdirs() }
    }

    private fun hash(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** The pinned copy of [url], if there is one. */
    fun fileFor(url: String): File? {
        if (!SmartCache.isEnabled()) return null
        val d = dir() ?: return null
        val h = hash(url)
        return listOf(LIB, TREND).map { File(d, it + h) }.firstOrNull { it.exists() && it.length() > 0 }
    }

    private fun urlsOf(list: Collection<Media>?): Set<String> =
        list.orEmpty().flatMap { listOf(it.cover, it.banner) }
            .filter { !it.isNullOrBlank() && it.startsWith("http") }
            .map { it!! }.toSet()

    fun setLibrary(lists: List<Collection<Media>?>) {
        libraryUrls = lists.flatMap { urlsOf(it) }.toSet()
        sync()
    }

    fun setTrending(anime: Boolean, list: Collection<Media>?) {
        if (anime) trendingAnime = urlsOf(list) else trendingManga = urlsOf(list)
        sync()
    }

    /** Starts a sync with whatever library/trending lists are already known. */
    fun requestSync() = sync()

    private fun sync() {
        if (!SmartCache.isEnabled()) return
        pending = true
        if (!running.compareAndSet(false, true)) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                while (pending) {
                    pending = false
                    runCatching { doSync() }.onFailure { Logger.log(it) }
                }
            } finally {
                running.set(false)
            }
        }
    }

    private fun doSync() {
        val d = dir() ?: return
        val lib = libraryUrls
        val trend = (trendingAnime + trendingManga) - lib
        val libHashes = lib.associateBy { hash(it) }
        val trendHashes = trend.associateBy { hash(it) }

        // Drop what is no longer wanted.
        d.listFiles()?.forEach { f ->
            val keep = when {
                f.name.startsWith(LIB) -> f.name.removePrefix(LIB) in libHashes
                f.name.startsWith(TREND) -> f.name.removePrefix(TREND) in trendHashes
                else -> false
            }
            if (!keep) f.delete()
        }

        fun total() = d.listFiles()?.sumOf { it.length() } ?: 0L
        val maxBytes = SmartCache.pinnedBudgetBytes()

        // If the limit was lowered, shrink: trending goes first, then the oldest library copies.
        if (total() > maxBytes) {
            d.listFiles()
                ?.sortedWith(compareBy<File>({ it.name.startsWith(LIB) }, { it.lastModified() }))
                ?.forEach { if (total() > maxBytes) it.delete() }
        }

        // Library first, then trending.
        for ((prefix, wanted) in listOf(LIB to libHashes, TREND to trendHashes)) {
            for ((h, url) in wanted) {
                if (File(d, prefix + h).exists()) continue
                if (total() >= maxBytes) {
                    if (prefix == TREND) return
                    // Make room for library items by dropping trending copies.
                    d.listFiles { f -> f.name.startsWith(TREND) }
                        ?.sortedBy { it.lastModified() }
                        ?.forEach { if (total() >= maxBytes) it.delete() }
                    if (total() >= maxBytes) return
                }
                download(url, File(d, prefix + h))
            }
        }
    }

    private fun download(url: String, target: File) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return
                resp.body.byteStream().use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
            }
            if (tmp.length() > 0) tmp.renameTo(target)
        } catch (e: Exception) {
            Logger.log(e)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
