package vn.edu.mobileoptimization.flowdebounce

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal sealed interface ResultsState {
    data object Idle : ResultsState
    data class Message(val text: String) : ResultsState
    data class Books(val books: List<BookDto>) : ResultsState
}

internal data class SearchUiState(
    val query: String = "",
    val optimized: Boolean = false,
    val off: ModeMetrics = ModeMetrics(),
    val on: ModeMetrics = ModeMetrics(),
    val results: ResultsState = ResultsState.Idle,
)

/**
 * Bản hiện đại của T1:
 * - Tắt tối ưu: mỗi lần nội dung thay đổi sẽ khởi chạy một coroutine gọi HTTP (giống T1).
 * - Bật tối ưu: `debounce(400)` + `collectLatest`; khi có từ khóa mới, coroutine cũ bị hủy
 *   và Retrofit/OkHttp hủy luôn request HTTP đang chạy.
 * ViewModel sống qua xoay màn hình nên request không bị gián đoạn như cách Activity + Handler.
 */
@OptIn(FlowPreview::class)
internal class SearchViewModel(
    private val repository: SearchRepository,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val clock: () -> Long = ::monotonicMillis,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    /** Chỉ nhận từ khóa ở chế độ bật tối ưu. */
    private val debouncedInput = MutableStateFlow("")
    private var pipeline: Job? = null
    private var session = 0L
    private var queryVersion = 0L

    init {
        startPipeline()
    }

    private fun startPipeline() {
        pipeline?.cancel() // Hủy ngay request đang chạy khi đổi chế độ hoặc làm lại.
        debouncedInput.value = ""
        pipeline = viewModelScope.launch {
            debouncedInput
                .debounce(debounceMs)
                .collectLatest { query ->
                    if (query.isNotEmpty()) runSearch(query, optimized = true, session, queryVersion)
                }
        }
    }

    fun onQueryChanged(raw: String) {
        queryVersion++
        val optimized = _state.value.optimized
        val query = raw.trim()
        _state.update {
            it.copy(
                query = raw,
                results = when {
                    query.isEmpty() -> ResultsState.Idle
                    optimized -> ResultsState.Message("Đang chờ bạn ngừng gõ…")
                    else -> ResultsState.Message("Đang tìm sách…")
                },
            )
        }
        if (optimized) {
            debouncedInput.value = query
        } else if (query.isNotEmpty()) {
            val session = session
            val version = queryVersion
            viewModelScope.launch { runSearch(query, optimized = false, session, version) }
        }
    }

    fun setOptimized(optimized: Boolean) {
        queryVersion++
        startPipeline()
        _state.update { it.copy(optimized = optimized, query = "", results = ResultsState.Idle) }
    }

    fun reset() {
        session++
        queryVersion++
        startPipeline()
        _state.update { it.copy(query = "", off = ModeMetrics(), on = ModeMetrics(), results = ResultsState.Idle) }
    }

    private suspend fun runSearch(query: String, optimized: Boolean, session: Long, version: Long) {
        if (session != this.session) return
        var number = 0
        updateMetrics(optimized) { metrics ->
            metrics.withStarted(query, wallClock()).also { number = it.requests }
        }
        val start = clock()
        var finished = false
        try {
            val result = repository.search(query)
            finished = true
            if (session != this.session) return
            val current = isCurrent(version, session, optimized)
            val shown = current && result.error == null && result.books != null
            updateMetrics(optimized) { metrics ->
                val updated = metrics.withRecord(number) {
                    it.copy(
                        state = if (result.error == null) RequestState.SUCCESS else RequestState.ERROR,
                        httpMs = result.httpMs,
                        databaseMs = result.databaseMs,
                        connectionId = result.connectionId,
                        connectionRequest = result.connectionRequest,
                        statusCode = result.statusCode,
                        error = result.error,
                        displayed = shown,
                    )
                }
                if (shown) updated.copy(httpMs = result.httpMs, databaseMs = result.databaseMs) else updated
            }
            when {
                shown -> _state.update { it.copy(results = ResultsState.Books(result.books.orEmpty())) }
                current && result.error != null -> _state.update {
                    it.copy(results = ResultsState.Message("Không tải được kết quả. Kiểm tra Backend\\Start.cmd rồi nhập lại."))
                }
            }
        } finally {
            if (!finished && session == this.session) {
                val elapsed = clock() - start
                updateMetrics(optimized) { metrics ->
                    metrics.withRecord(number) { it.copy(state = RequestState.CANCELLED, httpMs = elapsed) }
                }
            }
        }
    }

    private fun isCurrent(version: Long, session: Long, optimized: Boolean): Boolean =
        this.session == session && queryVersion == version && _state.value.optimized == optimized

    private inline fun updateMetrics(optimized: Boolean, crossinline transform: (ModeMetrics) -> ModeMetrics) {
        _state.update { if (optimized) it.copy(on = transform(it.on)) else it.copy(off = transform(it.off)) }
    }

    companion object {
        const val DEBOUNCE_MS = 400L
    }
}
