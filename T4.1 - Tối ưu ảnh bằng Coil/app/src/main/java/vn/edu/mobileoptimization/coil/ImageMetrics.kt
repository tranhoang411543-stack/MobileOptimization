package vn.edu.mobileoptimization.coil

data class ImageRecord(
    var number: Int = 0,
    val productName: String,
    var status: String = "Đang tải…",
    var httpMs: Long = -1,
    var responseBytes: Long = 0,
    var originalWidth: Int = 0,
    var originalHeight: Int = 0,
    var decodedWidth: Int = 0,
    var decodedHeight: Int = 0,
    var bitmapBytes: Long = 0,
    var sampleSize: Int = 0,
    var decodeMs: Double = -1.0,
    var displayMs: Double = -1.0,
    var connectionId: String? = null,
    var connectionNumber: Int = 0,
)

class ImageMetrics {
    val records = mutableListOf<ImageRecord>()
    var requests = 0
    var receivedBytes = 0L
    var decodedCount = 0
    var totalDecodeMs = 0.0
    var displayedCount = 0
    var totalDisplayMs = 0.0
    var lastWidth = 0
    var lastHeight = 0
    var lastBitmapBytes = 0L
    var heldBytes = 0L
    var peakHeldBytes = 0L

    fun add(record: ImageRecord) {
        requests++
        record.number = requests
        records.add(record)
        if (records.size > 100) records.removeAt(0)
    }
}
