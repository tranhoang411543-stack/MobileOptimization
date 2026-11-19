package vn.edu.mobileoptimization.images

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BitmapDecoderTest {
    @Test
    fun sameImageIsDecodedAtFullSizeOrNearTargetWithoutUpscaling() {
        val source = Bitmap.createBitmap(2000, 1500, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(30, 120, 210))
        val output = ByteArrayOutputStream()
        source.compress(Bitmap.CompressFormat.JPEG, 90, output)
        source.recycle()

        val full = BitmapDecoder.decode(output.toByteArray(), false, 252, 252)
        val optimized = BitmapDecoder.decode(output.toByteArray(), true, 252, 252)
        try {
            assertEquals(2000, full.bitmap.width)
            assertEquals(1500, full.bitmap.height)
            assertEquals(Bitmap.Config.ARGB_8888, full.bitmap.config)
            assertEquals(2000, optimized.originalWidth)
            assertEquals(1500, optimized.originalHeight)
            assertEquals(4, optimized.sampleSize)
            assertTrue(optimized.bitmap.width in 252..504)
            assertTrue(optimized.bitmap.height in 252..504)
            assertTrue(optimized.bitmap.allocationByteCount < full.bitmap.allocationByteCount / 10)
            val pixel = optimized.bitmap.getPixel(optimized.bitmap.width / 2, optimized.bitmap.height / 2)
            assertTrue(kotlin.math.abs(Color.blue(pixel) - 210) < 10)
        } finally {
            full.bitmap.recycle()
            optimized.bitmap.recycle()
        }
    }

    @Test
    fun invalidImageIsRejected() {
        try {
            BitmapDecoder.decode(byteArrayOf(1, 2, 3), true, 100, 100)
            fail("Ảnh không hợp lệ cần bị từ chối")
        } catch (_: IllegalArgumentException) { }
    }
}
