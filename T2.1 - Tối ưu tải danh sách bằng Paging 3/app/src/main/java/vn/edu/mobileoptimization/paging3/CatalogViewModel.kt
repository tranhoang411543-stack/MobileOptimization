package vn.edu.mobileoptimization.paging3

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class CatalogUiState(
    val optimized: Boolean = false,
    val session: Long = 0,
    val loadRequested: Boolean = false,
    val off: Measurement = Measurement(),
    val on: Measurement = Measurement(),
    /** Chỉ dùng cho chế độ tắt tối ưu (tải toàn bộ). */
    val allBooks: List<BookDto> = emptyList(),
    val allLoading: Boolean = false,
    val allError: String? = null,
) {
    fun measurement(optimized: Boolean) = if (optimized) on else off
    fun withMeasurement(optimized: Boolean, value: Measurement) =
        if (optimized) copy(on = value) else copy(off = value)
}

internal fun usedJavaHeap(): Long = Runtime.getRuntime().run { totalMemory() - freeMemory() }

/**
 * Bản hiện đại của T2:
 * - Tắt tối ưu: một request tải toàn bộ sách (giống T2).
 * - Bật tối ưu: Paging 3 tải trang đầu 20 sách, sau đó tự tải trang tiếp khi người dùng cuộn gần cuối.
 */
internal class CatalogViewModel(
    private val repository: CatalogRepository,
    private val clock: () -> Long = ::monotonicMillis,
    private val heap: () -> Long = ::usedJavaHeap,
) : ViewModel() {
    private val _state = MutableStateFlow(CatalogUiState())
    val state: StateFlow<CatalogUiState> = _state.asStateFlow()

    private val _pages = MutableStateFlow<Flow<PagingData<BookDto>>?>(null)
    val pages: StateFlow<Flow<PagingData<BookDto>>?> = _pages.asStateFlow()

    /** Mỗi phiên có scope riêng; hủy scope sẽ hủy luôn Pager và request đang chạy của phiên cũ. */
    private var sessionScope: CoroutineScope? = null
    private var actionStartedAt = 0L

    fun setOptimized(optimized: Boolean) = beginSession(optimized)

    fun reset() {
        _state.update { it.copy(off = Measurement(), on = Measurement()) }
        beginSession(_state.value.optimized)
    }

    fun load() {
        val current = _state.value
        if (current.allLoading) return
        val optimized = current.optimized
        val scope = beginSession(optimized)
        val session = _state.value.session
        actionStartedAt = clock()
        _state.update {
            it.copy(loadRequested = true).withMeasurement(optimized, Measurement(heapBefore = heap()))
        }
        if (optimized) {
            _pages.value = Pager(
                config = PagingConfig(
                    pageSize = PAGE_SIZE,
                    initialLoadSize = PAGE_SIZE, // Mặc định là 3 × pageSize; đặt bằng 20 để lần đầu giống T2.
                    prefetchDistance = PREFETCH_DISTANCE,
                    enablePlaceholders = false,
                ),
                pagingSourceFactory = { BookPagingSource(repository, recorder(session)) },
            ).flow.cachedIn(scope)
        } else {
            loadAll(scope, session)
        }
    }

    fun retryAll() {
        val current = _state.value
        if (current.optimized || current.allLoading || current.allError == null) return
        sessionScope?.let { loadAll(it, current.session) }
    }

    /** Giao diện gọi sau khung hình đầu tiên có nội dung danh sách. */
    fun onFirstContentDrawn(session: Long) {
        val current = _state.value
        val measurement = current.measurement(current.optimized)
        if (current.session != session || measurement.initialBooks == null || measurement.firstFrameMs != null) return
        _state.update {
            it.withMeasurement(it.optimized, measurement.copy(firstFrameMs = clock() - actionStartedAt, heapAfter = heap()))
        }
    }

    private fun beginSession(optimized: Boolean): CoroutineScope {
        sessionScope?.let { it.coroutineContext[Job]?.cancel() }
        val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
        sessionScope = scope
        _pages.value = null
        _state.update {
            it.copy(
                optimized = optimized,
                session = it.session + 1,
                loadRequested = false,
                allBooks = emptyList(),
                allLoading = false,
                allError = null,
            ).withMeasurement(optimized, Measurement())
        }
        return scope
    }

    private fun loadAll(scope: CoroutineScope, session: Long) {
        _state.update { it.copy(allLoading = true, allError = null) }
        scope.launch {
            val number = startRecord(optimized = false, session, afterId = null)
            var finished = false
            try {
                val result = repository.fetch(afterId = null, limit = 0)
                finished = true
                finishRecord(optimized = false, session, number, result)
                if (_state.value.session != session) return@launch
                _state.update {
                    if (result.books != null) it.copy(allBooks = result.books, allLoading = false)
                    else it.copy(allLoading = false, allError = "Không tải được danh sách (${result.error}). Nhấn để thử lại.")
                }
            } finally {
                if (!finished) cancelRecord(optimized = false, session, number)
            }
        }
    }

    private fun recorder(session: Long) = object : LoadRecorder {
        override fun started(afterId: Int) = startRecord(optimized = true, session, afterId)
        override fun finished(number: Int, result: FetchResult) = finishRecord(optimized = true, session, number, result)
        override fun cancelled(number: Int) = cancelRecord(optimized = true, session, number)
    }

    private fun startRecord(optimized: Boolean, session: Long, afterId: Int?): Int {
        var number = 0
        updateIfCurrent(optimized, session) { measurement ->
            measurement.withStarted(afterId).also { number = it.records.size }
        }
        return number
    }

    private fun finishRecord(optimized: Boolean, session: Long, number: Int, result: FetchResult) {
        if (number != 0) updateIfCurrent(optimized, session) { it.withFinished(number, result) }
    }

    private fun cancelRecord(optimized: Boolean, session: Long, number: Int) {
        if (number != 0) updateIfCurrent(optimized, session) { it.withCancelled(number) }
    }

    private inline fun updateIfCurrent(optimized: Boolean, session: Long, crossinline transform: (Measurement) -> Measurement) {
        _state.update {
            if (it.session != session || it.optimized != optimized) it
            else it.withMeasurement(optimized, transform(it.measurement(optimized)))
        }
    }

    override fun onCleared() {
        sessionScope?.let { it.coroutineContext[Job]?.cancel() }
    }

    companion object {
        /** Còn 5 sách nữa là hết danh sách đang có thì tải trang tiếp. */
        const val PREFETCH_DISTANCE = 5
    }
}
