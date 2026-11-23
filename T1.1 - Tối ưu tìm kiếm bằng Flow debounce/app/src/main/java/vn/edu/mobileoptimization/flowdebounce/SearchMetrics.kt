package vn.edu.mobileoptimization.flowdebounce

internal enum class RequestState { PENDING, SUCCESS, ERROR, CANCELLED }

internal data class RequestRecord(
    val number: Int,
    val query: String,
    val startedAtMillis: Long,
    val state: RequestState = RequestState.PENDING,
    val httpMs: Long = -1,
    val databaseMs: Double = -1.0,
    val connectionId: String? = null,
    val connectionRequest: Int = 0,
    val statusCode: Int = 0,
    val error: String? = null,
    val displayed: Boolean = false,
)

internal data class RequestTotals(
    val httpCount: Int,
    val httpMs: Long,
    val databaseCount: Int,
    val databaseMs: Double,
)

/** Số đo của một chế độ; bất biến để có thể đặt trực tiếp trong StateFlow. */
internal data class ModeMetrics(
    val records: List<RequestRecord> = emptyList(),
    val httpMs: Long = -1,
    val databaseMs: Double = -1.0,
) {
    val requests: Int get() = records.size
    val cancelled: Int get() = records.count { it.state == RequestState.CANCELLED }

    fun withStarted(query: String, startedAtMillis: Long): ModeMetrics =
        copy(records = records + RequestRecord(requests + 1, query, startedAtMillis))

    fun withRecord(number: Int, transform: (RequestRecord) -> RequestRecord): ModeMetrics =
        copy(records = records.map { if (it.number == number) transform(it) else it })

    fun totals(): RequestTotals {
        val finished = records.filter { it.state == RequestState.SUCCESS || it.state == RequestState.ERROR }
        val http = finished.filter { it.httpMs >= 0 }
        val database = finished.filter { it.databaseMs >= 0 && it.databaseMs.isFinite() }
        return RequestTotals(http.size, http.sumOf { it.httpMs }, database.size, database.sumOf { it.databaseMs })
    }
}
