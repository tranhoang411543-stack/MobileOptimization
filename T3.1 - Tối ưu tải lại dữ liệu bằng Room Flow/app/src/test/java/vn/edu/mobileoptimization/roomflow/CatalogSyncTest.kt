package vn.edu.mobileoptimization.roomflow

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun book(id: Int) = CachedBook(id, "Sách $id", "Tác giả", 100_000)

/** Backend giả: danh mục gốc + nhật ký thay đổi theo phiên bản. */
private class FakeRemote(var epoch: String = "epoch-a") : CatalogRemote {
    var base = (1..3).map(::book)
    val changes = mutableListOf<CachedBook>()
    var catalogRequests = 0
    var changeRequests = 0

    override suspend fun fetchCatalog(): NetworkResult {
        catalogRequests++
        return NetworkResult(base, 5, 100, 200, 1.0, null, 0, null)
    }

    override suspend fun state() = SyncHead(epoch, changes.size.toLong())

    override suspend fun changes(epoch: String, after: Long): SyncPage {
        changeRequests++
        val page = changes.drop(after.toInt()).take(2) // Trang 2 sách để kiểm tra nhiều trang.
        val cursor = after + page.size
        return SyncPage(epoch, page, cursor, hasMore = cursor < changes.size)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatalogSyncTest {
    private lateinit var database: CatalogDatabase
    private lateinit var dao: CatalogDao
    private val remote = FakeRemote()
    private lateinit var sync: CatalogSync

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CatalogDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = database.catalogDao()
        sync = CatalogSync(dao, remote, now = { 1_000L })
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun noCacheMeansNothingToSync() = runBlocking {
        assertEquals("Chưa có cache. Mở danh mục để tải lần đầu.", sync.synchronize())
        assertEquals(0, remote.changeRequests)
    }

    @Test
    fun roomFlowEmitsNewBooksAfterSync() = runBlocking {
        assertNull(dao.observeSnapshot().first())
        dao.mergeBaseSnapshot(remote.base, savedAt = 500)
        assertEquals(listOf(1, 2, 3), dao.observeSnapshot().first()!!.books.map { it.id })

        assertEquals("Không có sách mới từ script.", sync.synchronize())
        remote.changes += listOf(book(1001), book(1002), book(1003))
        assertEquals("Đã đồng bộ 3 sách mới vào Room.", sync.synchronize())

        // Màn hình chỉ quan sát Flow nên tự thấy 3 sách mới, không cần đọc lại bằng tay.
        val snapshot = dao.observeSnapshot().first()!!
        assertEquals(listOf(1, 2, 3, 1001, 1002, 1003), snapshot.books.map { it.id })
        assertEquals(1_000L, snapshot.savedAt)
        assertEquals(CacheSummary(6, 1_000L), dao.observeSummary().first())
        assertEquals(2, remote.changeRequests) // 3 thay đổi, mỗi trang 2 → 2 request.
        assertEquals(3L, dao.getSyncState()!!.cursor)
    }

    @Test
    fun staleSyncPageCannotRestoreClearedCache() = runBlocking {
        dao.mergeBaseSnapshot(remote.base, savedAt = 500)
        val staleState = dao.ensureSyncState()!!
        dao.clearSnapshot()

        assertFalse(dao.applySyncPage(staleState, "epoch-a", 1, listOf(book(1001)), 2_000))
        assertNull(dao.observeSnapshot().first())
        assertEquals(null, dao.observeSummary().first())
    }

    @Test
    fun recreatedBackendReplacesCacheWithFullCopy() = runBlocking {
        dao.mergeBaseSnapshot(listOf(book(1), book(99)), savedAt = 500)
        remote.changes += book(1001)
        sync.synchronize()

        remote.epoch = "epoch-b" // Backend được tạo lại: sách 99 và 1001 không còn.
        remote.changes.clear()
        remote.changes += book(2001)
        assertEquals("Đã khôi phục cache và đồng bộ 1 sách.", sync.synchronize())

        assertEquals(listOf(1, 2, 3, 2001), dao.observeSnapshot().first()!!.books.map { it.id })
        assertEquals("epoch-b", dao.getSyncState()!!.epoch)
        assertEquals(1, remote.catalogRequests)
    }
}
