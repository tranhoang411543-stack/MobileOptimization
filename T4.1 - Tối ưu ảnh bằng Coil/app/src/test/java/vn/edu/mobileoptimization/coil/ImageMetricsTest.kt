package vn.edu.mobileoptimization.coil

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageMetricsTest {
    @Test
    fun metrics_addsRecordsCorrectly() {
        val metrics = ImageMetrics()
        val record1 = ImageRecord(productName = "Product 1")
        val record2 = ImageRecord(productName = "Product 2")

        metrics.add(record1)
        metrics.add(record2)

        assertEquals(2, metrics.requests)
        assertEquals(1, record1.number)
        assertEquals(2, record2.number)
        assertEquals(2, metrics.records.size)
    }

    @Test
    fun metrics_limitsRecordHistory() {
        val metrics = ImageMetrics()
        repeat(150) { index ->
            metrics.add(ImageRecord(productName = "Product $index"))
        }

        assertEquals(150, metrics.requests)
        assertEquals(100, metrics.records.size)
        assertEquals(51, metrics.records.first().number)
        assertEquals(150, metrics.records.last().number)
    }

    @Test
    fun formatBytes_formatsCorrectly() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.5 KiB", formatBytes(1536))
        assertEquals("2.0 MiB", formatBytes(2 * 1024 * 1024))
    }
}
