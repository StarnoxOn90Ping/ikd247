package ani.kiwami.others

import android.content.Context
import ani.kiwami.settings.saving.PrefManager
import ani.kiwami.settings.saving.PrefName
import com.bumptech.glide.Glide
import java.io.File

/**
 * Central switch and size rules for the "Smart cache" feature (saved library/trending images and
 * saved title info). Everything is off by default and does nothing until the user turns it on.
 */
object SmartCache {
    const val MIN_MB = 200
    const val MAX_MB = 1000
    const val STEP_MB = 50

    private const val DEFAULT_GLIDE_BYTES = 100L * 1024 * 1024

    fun isEnabled(): Boolean =
        runCatching { PrefManager.getVal<Boolean>(PrefName.SmartCacheEnabled) }.getOrDefault(false)

    fun limitMb(): Int =
        runCatching { PrefManager.getVal<Int>(PrefName.SmartCacheLimitMb) }
            .getOrDefault(500).coerceIn(MIN_MB, MAX_MB)

    private fun limitBytes(): Long = limitMb() * 1024L * 1024L

    /** 30% of the limit is for library/trending images that are kept on purpose. */
    fun pinnedBudgetBytes(): Long = (limitBytes() * 0.3).toLong()

    /** The rest is Glide's normal "oldest picture goes first" cache (applied on app start). */
    fun glideBudgetBytes(): Long =
        if (isEnabled()) (limitBytes() * 0.7).toLong() else DEFAULT_GLIDE_BYTES

    fun sizeOf(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun pinnedBytes(context: Context) = sizeOf(File(context.cacheDir, "pinned_images"))
    fun glideBytes(context: Context) = sizeOf(File(context.cacheDir, "img"))
    fun infoBytes(context: Context) = sizeOf(File(context.cacheDir, "media_details"))

    /** Deletes everything this feature (and the enlarged image cache) has stored. */
    fun clearAll(context: Context) {
        runCatching { File(context.cacheDir, "pinned_images").deleteRecursively() }
        runCatching { File(context.cacheDir, "media_details").deleteRecursively() }
        runCatching { Glide.get(context).clearDiskCache() } // must run off the main thread
    }

    fun onEnabled() = PinnedImages.requestSync()
    fun onLimitChanged() = PinnedImages.requestSync()
}
