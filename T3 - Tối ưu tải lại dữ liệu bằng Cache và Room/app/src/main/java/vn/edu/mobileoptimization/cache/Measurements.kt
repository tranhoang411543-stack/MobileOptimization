package vn.edu.mobileoptimization.cache

data class NetworkAttempt(
    var status: String = "Đang thực hiện…",
    var httpMs: Long = -1,
    var bytes: Long = 0,
    var books: Int = 0,
    var databaseMs: Double = -1.0,
    var connectionId: String? = null,
    var connectionNumber: Int = 0,
)

data class VisitMeasurement(
    val optimized: Boolean,
    val startedAt: Long,
    val openedAt: Long = System.currentTimeMillis(),
    val attempts: MutableList<NetworkAttempt> = mutableListOf(),
    var firstSource: String? = null,
    var firstContentMs: Long? = null,
    var firstCount: Int? = null,
    var outcome: String = "Đang mở…",
)

/** All records are read and updated on the Android main thread. */
object Measurements {
    val off = mutableListOf<VisitMeasurement>()
    val on = mutableListOf<VisitMeasurement>()
    fun forMode(optimized: Boolean) = if (optimized) on else off
    fun clear() { off.clear(); on.clear() }
}
