package vn.edu.mobileoptimization.paging3

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.testing.TestPager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookPagingSourceTest {
    /** Giả lập bảng 45 sách và truy vấn `WHERE id > afterId ORDER BY id LIMIT n` của backend. */
    private class FakeCatalog(private val total: Int = 45, var failNext: Boolean = false) : CatalogRepository {
        val requests = mutableListOf<Pair<Int?, Int>>()
        override suspend fun fetch(afterId: Int?, limit: Int): FetchResult {
            requests += afterId to limit
            if (failNext) {
                failNext = false
                return FetchResult(null, false, null, 5, 0, 0, -1.0, null, 0, "Không nhận được phản hồi")
            }
            val rows = (1..total).filter { it > (afterId ?: 0) }.take(if (afterId == null) total else limit)
                .map { BookDto(it, "Sách $it", "Tác giả", 100_000) }
            val hasMore = afterId != null && rows.isNotEmpty() && rows.last().id < total
            return FetchResult(rows, hasMore, if (hasMore) rows.last().id else null, 10, rows.size * 100L, 200, 1.0, "c1", 1, null)
        }
    }

    private class RecordingRecorder : LoadRecorder {
        var measurement = Measurement()
        override fun started(afterId: Int): Int {
            measurement = measurement.withStarted(afterId)
            return measurement.records.size
        }
        override fun finished(number: Int, result: FetchResult) { measurement = measurement.withFinished(number, result) }
        override fun cancelled(number: Int) { measurement = measurement.withCancelled(number) }
    }

    private val config = PagingConfig(pageSize = PAGE_SIZE, initialLoadSize = PAGE_SIZE, prefetchDistance = 5, enablePlaceholders = false)

    @Test
    fun trangDauChi20SachVaDungKeyset() = runTest {
        val catalog = FakeCatalog()
        val recorder = RecordingRecorder()
        val pager = TestPager(config, BookPagingSource(catalog, recorder))

        val first = pager.refresh() as PagingSource.LoadResult.Page
        assertEquals(20, first.data.size)
        assertEquals(20, first.nextKey) // Trang sau bắt đầu sau mã sách 20.
        assertEquals(listOf(0 to 20), catalog.requests)
        assertEquals(20, recorder.measurement.initialBooks)
        assertEquals(2000L, recorder.measurement.initialBytes)
    }

    @Test
    fun cuonDenHetThiDungTai() = runTest {
        val catalog = FakeCatalog()
        val recorder = RecordingRecorder()
        val pager = TestPager(config, BookPagingSource(catalog, recorder))

        pager.refresh()
        pager.append()
        val last = pager.append() as PagingSource.LoadResult.Page
        assertEquals(5, last.data.size)
        assertNull(last.nextKey)
        assertEquals(listOf(0 to 20, 20 to 20, 40 to 20), catalog.requests)
        assertEquals(45, recorder.measurement.receivedBooks)
        assertEquals(3, recorder.measurement.records.size)
    }

    @Test
    fun loiMangTraVeLoadResultError() = runTest {
        val catalog = FakeCatalog(failNext = true)
        val recorder = RecordingRecorder()
        val pager = TestPager(config, BookPagingSource(catalog, recorder))

        assertTrue(pager.refresh() is PagingSource.LoadResult.Error)
        assertEquals(RequestState.ERROR, recorder.measurement.records.single().state)
        assertNull(recorder.measurement.initialBooks)
    }
}
