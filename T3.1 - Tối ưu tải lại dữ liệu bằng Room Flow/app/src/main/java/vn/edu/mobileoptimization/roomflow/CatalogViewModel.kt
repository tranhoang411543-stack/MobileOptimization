package vn.edu.mobileoptimization.roomflow

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CatalogUiState(
    val books: List<CachedBook> = emptyList(),
    val hasContent: Boolean = false,
    val source: String = "Nguồn: Đang tải…",
    val updated: String = "Cập nhật lần cuối: —",
    val status: String = "Đang chuẩn bị…",
    val empty: String = "Đang tải danh sách…",
    val busy: Boolean = false,
)

/**
 * Một lượt mở danh mục. ViewModel gắn với màn hình trong Navigation nên bị hủy khi người dùng quay lại.
 *
 * Chế độ bật: Room là nguồn dữ liệu duy nhất (single source of truth). Giao diện chỉ quan sát
 * `observeSnapshot()`; tải từ server hay đồng bộ đều chỉ ghi vào Room, Flow tự phát danh sách mới.
 */
class CatalogViewModel(
    private val optimized: Boolean,
    private val snapshots: Flow<CatalogSnapshot?>,
    private val dao: CatalogDao,
    private val remote: CatalogRemote,
    private val requestSyncCheck: () -> Unit,
    private val measurements: MeasurementStore,
    private val offlineReason: () -> String?,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val startedAt = clock()
    private val visitId = measurements.start(optimized)
    private val _state = MutableStateFlow(CatalogUiState())
    val state: StateFlow<CatalogUiState> = _state.asStateFlow()

    private var currentSource = ""
    private var savingFromServer = false
    /** Nguồn và số sách của lần hiển thị đầu tiên, chốt ngay khi hiển thị (đồng bộ có thể ghi thêm trước khung hình). */
    private var firstShown: Pair<String, Int>? = null

    init {
        if (optimized) observeRoom() else requestFromServer()
    }

    fun refresh() {
        if (optimized && _state.value.hasContent) requestSyncCheck() else requestFromServer()
    }

    /** Giao diện gọi sau khung hình đầu tiên có danh sách. */
    fun onFirstContentDrawn() {
        val (source, count) = firstShown ?: return
        measurements.update(visitId) { visit ->
            if (visit.firstContentMs != null) visit
            else visit.copy(firstSource = source, firstCount = count, firstContentMs = clock() - startedAt)
        }
    }

    private fun observeRoom() {
        _state.update { it.copy(status = "Đang đọc dữ liệu trong Room…", busy = true) }
        viewModelScope.launch {
            var first = true
            snapshots
                .catch {
                    if (!_state.value.hasContent) {
                        setStatus("Không đọc được Room · Đang thử server.", busy = false)
                        requestFromServer()
                    }
                }
                .collect { snapshot ->
                    if (first) {
                        first = false
                        _state.update { it.copy(busy = false) }
                        if (snapshot == null) {
                            setStatus("Chưa có cache · Đang tải từ server.")
                            requestFromServer()
                            return@collect
                        }
                        display(snapshot.books, "Room", snapshot.savedAt, stored = true)
                        val reason = offlineReason()
                        val freshness = if (CachePolicy.isFresh(snapshot.savedAt, wallClock())) "Cache còn mới" else "Cache đã cũ"
                        setStatus(if (reason == null) "$freshness · Kiểm tra phiên bản ở nền." else "$reason · Đang hiển thị cache.")
                        requestSyncCheck()
                    } else if (snapshot != null) {
                        val fromServer = savingFromServer.also { savingFromServer = false }
                        display(snapshot.books, if (fromServer) "Máy chủ" else "Room", snapshot.savedAt, stored = true)
                        setStatus(if (fromServer) "Đã tải từ server và lưu vào Room." else "Đã cập nhật dữ liệu Room.")
                    }
                }
        }
    }

    private fun requestFromServer() {
        if (_state.value.busy) return
        offlineReason()?.let { showFailure("$it · Không gửi request."); return }
        _state.update {
            it.copy(
                busy = true,
                status = if (it.hasContent) "Đang làm mới từ server…" else "Đang tải danh sách từ server…",
                empty = "Đang tải danh sách từ server…",
            )
        }
        viewModelScope.launch {
            measurements.update(visitId) { it.copy(attempts = it.attempts + NetworkAttempt()) }
            val result = remote.fetchCatalog()
            val books = result.books
            val attempt = NetworkAttempt(
                status = if (books == null) result.error ?: "Không nhận được dữ liệu" else "Thành công (HTTP ${result.statusCode})",
                httpMs = result.httpMs, bytes = result.bytes, books = books?.size ?: 0, databaseMs = result.databaseMs,
                connectionId = result.connectionId, connectionNumber = result.connectionNumber,
            )
            measurements.update(visitId) { it.copy(attempts = it.attempts.dropLast(1) + attempt) }
            when {
                books == null -> { _state.update { it.copy(busy = false) }; showFailure(attempt.status) }
                optimized -> save(books)
                else -> {
                    _state.update { it.copy(busy = false) }
                    display(books, "Máy chủ", wallClock(), stored = false)
                    setStatus("Đã tải từ server. Chế độ tắt không dùng Room.")
                }
            }
        }
    }

    /** Chỉ ghi vào Room; Flow sẽ phát danh sách mới và `observeRoom` hiển thị nó. */
    private suspend fun save(books: List<CachedBook>) {
        setStatus("Đang lưu dữ liệu vào Room…")
        savingFromServer = true
        try {
            dao.mergeBaseSnapshot(books, wallClock())
            _state.update { it.copy(busy = false) }
            requestSyncCheck()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            savingFromServer = false
            _state.update { it.copy(busy = false) }
            display(books, "Máy chủ", wallClock(), stored = false)
            setStatus("Đã tải từ server nhưng không lưu được vào Room.")
        }
    }

    private fun showFailure(reason: String) {
        val current = _state.value
        if (current.hasContent) {
            val description = if (currentSource == "Room") "Đang hiển thị dữ liệu Room đã lưu."
            else "Đang giữ dữ liệu của lần tải trước trên màn hình này."
            setStatus("$reason $description")
        } else {
            _state.update {
                it.copy(
                    source = "Nguồn: Chưa có dữ liệu",
                    updated = "Cập nhật lần cuối: —",
                    empty = if (optimized) "Chưa có dữ liệu Room để xem. Hãy kết nối mạng và làm mới."
                    else "Chế độ tắt cần kết nối server để tải danh sách.",
                )
            }
            setStatus(reason)
        }
    }

    private fun display(items: List<CachedBook>, source: String, updatedAt: Long, stored: Boolean) {
        currentSource = source
        if (firstShown == null) firstShown = source to items.size
        _state.update {
            it.copy(
                books = items,
                hasContent = true,
                source = when {
                    source == "Room" -> "Nguồn: Room"
                    stored -> "Nguồn: Máy chủ → Room"
                    else -> "Nguồn: Máy chủ"
                },
                updated = if (stored) "Cập nhật Room: ${formatTime(updatedAt)}" else "Nhận từ server: ${formatTime(updatedAt)}",
                empty = "Danh mục hiện không có sách.",
            )
        }
    }

    private fun setStatus(text: String, busy: Boolean? = null) {
        _state.update { it.copy(status = text, busy = busy ?: it.busy) }
        measurements.update(visitId) { it.copy(outcome = text) }
    }

    override fun onCleared() {
        // Khác T3: rời màn hình sẽ hủy coroutine, OkHttp hủy luôn request đang chạy.
        if (_state.value.busy) {
            measurements.update(visitId) { visit ->
                visit.copy(
                    attempts = visit.attempts.map { if (it.httpMs < 0) it.copy(status = "Đã rời màn hình") else it },
                    outcome = "Đã rời màn hình khi đang tải.",
                )
            }
        }
    }
}
