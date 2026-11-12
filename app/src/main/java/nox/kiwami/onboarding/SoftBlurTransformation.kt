package nox.kiwami.onboarding

import android.graphics.Bitmap
import com.bumptech.glide.load.Key
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

/**
 * Pure-Kotlin box blur (two passes ~ gaussian). Needs no RenderScript / RenderEffect, so a poster
 * looks exactly the same blurred on every Android version.
 */
class SoftBlurTransformation(private val radius: Int = 4) : BitmapTransformation() {

    override fun transform(
        pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int
    ): Bitmap {
        val w = toTransform.width
        val h = toTransform.height
        var pixels = IntArray(w * h)
        toTransform.getPixels(pixels, 0, w, 0, 0, w, h)
        repeat(2) { pixels = blur(pixels, w, h, radius) }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun blur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(src.size)
        val dst = IntArray(src.size)
        val div = 2 * r + 1

        // horizontal pass
        for (y in 0 until h) {
            val row = y * w
            var rs = 0
            var gs = 0
            var bs = 0
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, w - 1)]
                rs += (p shr 16) and 0xFF; gs += (p shr 8) and 0xFF; bs += p and 0xFF
            }
            for (x in 0 until w) {
                tmp[row + x] = (0xFF shl 24) or ((rs / div) shl 16) or ((gs / div) shl 8) or (bs / div)
                val add = src[row + (x + r + 1).coerceAtMost(w - 1)]
                val sub = src[row + (x - r).coerceAtLeast(0)]
                rs += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                gs += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                bs += (add and 0xFF) - (sub and 0xFF)
            }
        }
        // vertical pass
        for (x in 0 until w) {
            var rs = 0
            var gs = 0
            var bs = 0
            for (i in -r..r) {
                val p = tmp[i.coerceIn(0, h - 1) * w + x]
                rs += (p shr 16) and 0xFF; gs += (p shr 8) and 0xFF; bs += p and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = (0xFF shl 24) or ((rs / div) shl 16) or ((gs / div) shl 8) or (bs / div)
                val add = tmp[(y + r + 1).coerceAtMost(h - 1) * w + x]
                val sub = tmp[(y - r).coerceAtLeast(0) * w + x]
                rs += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                gs += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                bs += (add and 0xFF) - (sub and 0xFF)
            }
        }
        return dst
    }

    override fun equals(other: Any?) = other is SoftBlurTransformation && other.radius == radius
    override fun hashCode() = ID.hashCode() + radius * 31
    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("$ID$radius".toByteArray(Key.CHARSET))
    }

    private companion object {
        const val ID = "nox.kiwami.onboarding.SoftBlurTransformation.v1"
    }
}
