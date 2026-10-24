package ani.dantotsu.others

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * User-picked covers and banners for the media info page.
 *
 * These live only on the device, keyed by the AniList/MAL media id. The [ani.dantotsu.media.Media]
 * object itself is never touched, so list entries, progress and every tracking call keep using the
 * real id and are unaffected by a custom picture.
 */
object CustomMediaImages {
    enum class Kind(val tag: String, val maxEdge: Int) {
        COVER("cover", 1600),
        BANNER("banner", 2400)
    }

    private fun dir(context: Context): File =
        File(context.filesDir, "custom_media_images").apply { mkdirs() }

    // ---- Global lookup -------------------------------------------------------------------
    // Every image loader in the app asks [overrideFor] first, so a custom cover/banner shows up
    // in lists, search, home, etc. AniList image URLs carry the media id in their file name
    // (".../cover/large/bx21-abc.jpg", ".../banner/21-abc.jpg"), which lets us find the override
    // for any size/variant of the picture without knowing which Media object it belongs to.

    private val urlRegex = Regex("""/(cover|banner)/(?:[^/]+/)?(?:bx|b)?(\d+)-[^/]*$""")

    @Volatile
    private var index: Map<String, File>? = null

    private fun buildIndex(): Map<String, File> {
        val ctx = Injekt.get<Application>()
        val found = HashMap<String, File>()
        dir(ctx).listFiles()?.filter { it.isFile && it.length() > 0 }
            ?.sortedBy { it.lastModified() }
            ?.forEach { f ->
                // name: <id>_<kind>_<timestamp>.jpg ; newest wins because of the sort above
                val parts = f.name.split("_")
                if (parts.size >= 3) found["${parts[0]}_${parts[1]}"] = f
            }
        index = found
        return found
    }

    private fun invalidate() {
        index = null
    }

    /** The custom picture that should replace [url], or null. Cheap enough for every image load. */
    fun overrideFor(url: String?): File? {
        if (url.isNullOrEmpty() || !url.startsWith("http")) return null
        val match = urlRegex.find(url) ?: return null
        val kind = match.groupValues[1]
        val id = match.groupValues[2]
        val map = index ?: try {
            buildIndex()
        } catch (_: Exception) {
            return null
        }
        if (map.isEmpty()) return null
        return map["${id}_$kind"]?.takeIf { it.exists() }
    }

    /** The media id encoded in an AniList image URL, if it has one. */
    fun idFromUrl(url: String?): Int? =
        url?.let { urlRegex.find(it)?.groupValues?.get(2)?.toIntOrNull() }

    /** Custom images are tied to AniList image URLs, so only media that has one can use them. */
    fun canCustomize(mediaId: Int, cover: String?, banner: String?): Boolean =
        mediaId != 0 && (idFromUrl(cover) == mediaId || idFromUrl(banner) == mediaId)

    // Files get a unique name on every save so Glide never serves a stale cached copy.
    private fun prefix(mediaId: Int, kind: Kind) = "${mediaId}_${kind.tag}_"

    /** The custom image for this media, or null if the user hasn't set one. */
    fun get(context: Context, mediaId: Int, kind: Kind): File? {
        if (mediaId == 0) return null
        return dir(context).listFiles { f -> f.isFile && f.name.startsWith(prefix(mediaId, kind)) && f.length() > 0 }
            ?.maxByOrNull { it.lastModified() }
    }

    fun path(context: Context, mediaId: Int, kind: Kind): String? = get(context, mediaId, kind)?.absolutePath

    fun remove(context: Context, mediaId: Int, kind: Kind) {
        dir(context).listFiles { f -> f.name.startsWith(prefix(mediaId, kind)) }?.forEach { it.delete() }
        invalidate()
    }

    /** Copies [uri] into app storage (downscaled, rotation fixed). Returns true on success. */
    suspend fun save(context: Context, mediaId: Int, kind: Kind, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            if (mediaId == 0) return@withContext false
            try {
                val resolver = context.contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext false

                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= kind.maxEdge) sample *= 2
                var bitmap = resolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: return@withContext false

                val rotation = runCatching {
                    resolver.openInputStream(uri)?.use {
                        when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                            else -> 0f
                        }
                    } ?: 0f
                }.getOrDefault(0f)
                if (rotation != 0f) {
                    val rotated = Bitmap.createBitmap(
                        bitmap, 0, 0, bitmap.width, bitmap.height,
                        Matrix().apply { postRotate(rotation) }, true
                    )
                    if (rotated !== bitmap) bitmap.recycle()
                    bitmap = rotated
                }

                val target = File(dir(context), prefix(mediaId, kind) + System.currentTimeMillis() + ".jpg")
                target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                bitmap.recycle()
                if (target.length() <= 0) {
                    target.delete()
                    return@withContext false
                }
                // Keep only the newest copy.
                dir(context).listFiles { f -> f.name.startsWith(prefix(mediaId, kind)) && f != target }
                    ?.forEach { it.delete() }
                invalidate()
                true
            } catch (e: Exception) {
                Logger.log(e)
                false
            }
        }
}
