package vn.edu.mobileoptimization.paging3

import androidx.paging.PagingSource
import androidx.paging.PagingState
import java.io.IOException

/** Nơi PagingSource báo từng request để ViewModel cập nhật card số đo. */
internal interface LoadRecorder {
    fun started(afterId: Int): Int
    fun finished(number: Int, result: FetchResult)
    fun cancelled(number: Int)
}

/**
 * Phân trang keyset: key của trang là mã sách cuối cùng đã nhận (`afterId`).
 * Máy chủ chạy `WHERE id > afterId ORDER BY id LIMIT n`, không cần COUNT hay OFFSET.
 */
internal class BookPagingSource(
    private val repository: CatalogRepository,
    private val recorder: LoadRecorder,
) : PagingSource<Int, BookDto>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, BookDto> {
        val afterId = params.key ?: 0
        val number = recorder.started(afterId)
        var finished = false
        try {
            val result = repository.fetch(afterId, params.loadSize.coerceIn(1, 50))
            finished = true
            recorder.finished(number, result)
            val books = result.books ?: return LoadResult.Error(IOException(result.error ?: "Không nhận được dữ liệu"))
            return LoadResult.Page(
                data = books,
                prevKey = null, // Không bỏ trang đã tải nên không cần tải ngược.
                nextKey = if (result.hasMore) result.nextCursor else null,
            )
        } finally {
            if (!finished) recorder.cancelled(number)
        }
    }

    // Mỗi lần làm mới đều bắt đầu lại từ đầu danh sách, giống nút "Tải danh sách" của T2.
    override fun getRefreshKey(state: PagingState<Int, BookDto>): Int? = null
}
