package vn.edu.mobileoptimization.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class DecodedImage(
    val bitmap: Bitmap,
    val originalWidth: Int,
    val originalHeight: Int,
    val sampleSize: Int,
    val decodeMs: Double,
)

object BitmapDecoder {
    fun decode(bytes: ByteArray, optimized: Boolean, targetWidth: Int, targetHeight: Int): DecodedImage {
        require(targetWidth > 0 && targetHeight > 0)
        val started = SystemClock.elapsedRealtimeNanos()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Ảnh không hợp lệ" }

        var sample = 1
        if (optimized) {
            while (bounds.outWidth / (sample * 2) >= targetWidth &&
                bounds.outHeight / (sample * 2) >= targetHeight) {
                sample *= 2
            }
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException("Không giải mã được ảnh")
        if (optimized) {
            // Preserve enough pixels for centerCrop without enlarging a small source image.
            val scale = min(1.0, max(targetWidth.toDouble() / bitmap.width, targetHeight.toDouble() / bitmap.height))
            val width = max(1, ceil(bitmap.width * scale).toInt())
            val height = max(1, ceil(bitmap.height * scale).toInt())
            if (width != bitmap.width || height != bitmap.height) {
                val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                if (scaled !== bitmap) bitmap.recycle() // This intermediate bitmap was never shown by a View.
                bitmap = scaled
            }
        }
        val elapsed = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
        return DecodedImage(bitmap, bounds.outWidth, bounds.outHeight, sample, elapsed)
    }
}
