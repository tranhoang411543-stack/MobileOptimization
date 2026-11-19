package vn.edu.mobileoptimization.debounce

internal enum class RequestState { PENDING, SUCCESS, ERROR, INTERRUPTED }
internal enum class ConnectionKind { NEW, REUSED, UNKNOWN }

internal data class RequestRecord(
    var number: Int,
    val query: String,
    val startedAtMillis: Long,
    var state: RequestState = RequestState.PENDING,
    var httpMs: Long = -1,
    var databaseMs: Double = -1.0,
    var connectionId: String? = null,
    var connectionRequest: Int = 0,
    var statusCode: Int = 0,
    var error: String? = null,
    var displayed: Boolean = false,
) {
    fun connectionKind(): ConnectionKind = when {
        connectionId.isNullOrBlank() || connectionRequest < 1 -> ConnectionKind.UNKNOWN
        connectionRequest == 1 -> ConnectionKind.NEW
        else -> ConnectionKind.REUSED
    }
}

internal data class RequestTotals(
    val httpCount: Int,
    val httpMs: Long,
    val databaseCount: Int,
    val databaseMs: Double,
)

internal class ModeMetrics {
    var requests: Int = 0
    var httpMs: Long = -1
    var databaseMs: Double = -1.0
    val records: MutableList<RequestRecord> = mutableListOf()

    fun add(record: RequestRecord) {
        requests += 1
        record.number = requests
        records.add(record)
    }

    fun clear() {
        requests = 0
        httpMs = -1
        databaseMs = -1.0
        records.clear()
    }

    fun totals(): RequestTotals {
        var httpCount = 0
        var totalHttpMs = 0L
        var databaseCount = 0
        var totalDatabaseMs = 0.0
        for (record in records) {
            if (record.state != RequestState.SUCCESS && record.state != RequestState.ERROR) continue
            if (record.httpMs >= 0) {
                httpCount++
                totalHttpMs += record.httpMs
            }
            if (record.databaseMs >= 0 && record.databaseMs.isFinite()) {
                databaseCount++
                totalDatabaseMs += record.databaseMs
            }
        }
        return RequestTotals(httpCount, totalHttpMs, databaseCount, totalDatabaseMs)
    }

    fun successful(kind: ConnectionKind): List<RequestRecord> =
        records.filter { it.state == RequestState.SUCCESS && it.connectionKind() == kind }

    fun medianHttp(kind: ConnectionKind): Double? {
        val times = successful(kind).map { it.httpMs }.filter { it >= 0 }.sorted()
        if (times.isEmpty()) return null
        val middle = times.size / 2
        return if (times.size % 2 == 1) times[middle].toDouble()
        else (times[middle - 1].toDouble() + times[middle]) / 2.0
    }
}
