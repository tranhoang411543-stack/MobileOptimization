package vn.edu.mobileoptimization.paging3

internal enum class RequestState { PENDING, SUCCESS, ERROR, CANCELLED }

internal data class RequestRecord(
    val number: Int,
    /** null = tải toàn bộ; số khác = mã sách cuối của trang trước (keyset cursor). */
    val afterId: Int?,
    val state: RequestState = RequestState.PENDING,
    val status: String = "Đang thực hiện…",
    val httpMs: Long = -1,
    val bytes: Long = 0,
    val returned: Int = 0,
    val databaseMs: Double = -1.0,
    val connectionId: String? = null,
    val connectionNumber: Int = 0,
)

/** Số đo của một phiên tải; cùng ý nghĩa với các chỉ số trên card của T2. */
internal data class Measurement(
    val records: List<RequestRecord> = emptyList(),
    val receivedBooks: Int = 0,
    val receivedBytes: Long = 0,
    val initialBooks: Int? = null,
    val initialBytes: Long? = null,
    val firstFrameMs: Long? = null,
    val heapBefore: Long? = null,
    val heapAfter: Long? = null,
) {
    fun withStarted(afterId: Int?): Measurement =
        copy(records = records + RequestRecord(records.size + 1, afterId))

    fun withFinished(number: Int, result: FetchResult): Measurement {
        val success = result.error == null && result.books != null
        val returned = result.books?.size ?: 0
        val updated = copy(
            records = records.map { record ->
                if (record.number != number) record else record.copy(
                    state = if (success) RequestState.SUCCESS else RequestState.ERROR,
                    status = if (success) "Thành công (HTTP ${result.statusCode})" else result.error ?: "Không nhận được dữ liệu",
                    httpMs = result.httpMs,
                    bytes = result.bytes,
                    returned = returned,
                    databaseMs = result.databaseMs,
                    connectionId = result.connectionId,
                    connectionNumber = result.connectionNumber,
                )
            },
            receivedBytes = receivedBytes + result.bytes,
            receivedBooks = receivedBooks + if (success) returned else 0,
        )
        return if (success && initialBooks == null) updated.copy(initialBooks = returned, initialBytes = result.bytes)
        else updated
    }

    fun withCancelled(number: Int): Measurement = copy(
        records = records.map {
            if (it.number == number && it.state == RequestState.PENDING) it.copy(state = RequestState.CANCELLED, status = "Đã hủy")
            else it
        },
    )
}
