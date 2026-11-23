package vn.edu.mobileoptimization.roomflow

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID

@Entity(tableName = "cached_books")
data class CachedBook(
    @PrimaryKey val id: Int,
    val title: String,
    val author: String,
    val priceVnd: Int,
)

@Entity(tableName = "cache_metadata")
data class CacheMetadata(@PrimaryKey val key: String, val savedAt: Long)

@Entity(tableName = "catalog_sync_state")
data class CatalogSyncState(
    @PrimaryKey val id: Int = 1,
    /** Đổi mỗi khi cache bị xóa; trang đồng bộ cũ mang mốc cũ sẽ bị từ chối. */
    val generation: String,
    val epoch: String,
    val cursor: Long,
)

data class CatalogSnapshot(val books: List<CachedBook>, val savedAt: Long)
data class CacheSummary(val count: Int, val savedAt: Long)

/** Một dòng của truy vấn JOIN: metadata luôn có, cột sách có thể null khi danh mục rỗng. */
data class SnapshotRow(val savedAt: Long, val id: Int?, val title: String?, val author: String?, val priceVnd: Int?)

private const val CATALOG_KEY = "catalog"

@Dao
interface CatalogDao {
    /**
     * Một câu SELECT duy nhất đọc cả mốc thời gian và danh sách sách, nên hai phần luôn khớp nhau.
     * Room theo dõi cả hai bảng và phát lại Flow sau mỗi lần ghi.
     */
    @Query(
        "SELECT m.savedAt AS savedAt, b.id AS id, b.title AS title, b.author AS author, b.priceVnd AS priceVnd " +
            "FROM cache_metadata m LEFT JOIN cached_books b ON 1 = 1 WHERE m.`key` = 'catalog' ORDER BY b.id",
    )
    fun observeSnapshotRows(): Flow<List<SnapshotRow>>

    /** Màn hình chính chỉ cần số lượng, không phải đọc 1.000 dòng. */
    @Query(
        "SELECT (SELECT COUNT(*) FROM cached_books) AS count, savedAt FROM cache_metadata WHERE `key` = 'catalog' LIMIT 1",
    )
    fun observeSummary(): Flow<CacheSummary?>

    @Query("SELECT * FROM cache_metadata WHERE `key` = 'catalog' LIMIT 1")
    suspend fun getMetadata(): CacheMetadata?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBooks(books: List<CachedBook>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMetadata(metadata: CacheMetadata)

    @Query("DELETE FROM cached_books")
    suspend fun deleteBooks()

    @Query("DELETE FROM cache_metadata")
    suspend fun deleteMetadata()

    @Query("SELECT * FROM catalog_sync_state WHERE id = 1 LIMIT 1")
    suspend fun getSyncState(): CatalogSyncState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSyncState(state: CatalogSyncState)

    @Query("DELETE FROM catalog_sync_state")
    suspend fun deleteSyncState()

    /** Làm mới thủ công: cập nhật danh mục gốc, giữ sách đã nhận qua đồng bộ. */
    @Transaction
    suspend fun mergeBaseSnapshot(books: List<CachedBook>, savedAt: Long) {
        if (getMetadata() == null) deleteSyncState()
        insertBooks(books)
        insertMetadata(CacheMetadata(CATALOG_KEY, savedAt))
    }

    @Transaction
    suspend fun ensureSyncState(): CatalogSyncState? {
        if (getMetadata() == null) return null
        return getSyncState() ?: CatalogSyncState(generation = UUID.randomUUID().toString(), epoch = "", cursor = 0)
            .also { setSyncState(it) }
    }

    /** Ghi một trang đồng bộ và mốc mới trong cùng transaction; từ chối nếu cache đã bị xóa hoặc thay thế. */
    @Transaction
    suspend fun applySyncPage(expected: CatalogSyncState, epoch: String, cursor: Long, books: List<CachedBook>, savedAt: Long): Boolean {
        if (getMetadata() == null || getSyncState() != expected) return false
        insertBooks(books)
        setSyncState(expected.copy(epoch = epoch, cursor = cursor))
        if (books.isNotEmpty()) insertMetadata(CacheMetadata(CATALOG_KEY, savedAt))
        return true
    }

    /** Backend được tạo lại (epoch đổi): thay toàn bộ cache bằng bản đầy đủ mới. */
    @Transaction
    suspend fun resetSyncSnapshot(expected: CatalogSyncState, epoch: String, cursor: Long, books: List<CachedBook>, savedAt: Long): Boolean {
        if (getMetadata() == null || getSyncState() != expected) return false
        deleteBooks()
        insertBooks(books)
        insertMetadata(CacheMetadata(CATALOG_KEY, savedAt))
        setSyncState(expected.copy(epoch = epoch, cursor = cursor))
        return true
    }

    @Transaction
    suspend fun clearSnapshot() {
        deleteBooks()
        deleteMetadata()
        deleteSyncState()
    }
}

/** Chuyển các dòng JOIN thành snapshot; null nghĩa là chưa có cache. */
fun CatalogDao.observeSnapshot(): Flow<CatalogSnapshot?> = observeSnapshotRows()
    .map { rows ->
        rows.firstOrNull()?.let { first ->
            CatalogSnapshot(
                books = rows.mapNotNull { row ->
                    row.id?.let { CachedBook(it, row.title.orEmpty(), row.author.orEmpty(), row.priceVnd ?: 0) }
                },
                savedAt = first.savedAt,
            )
        }
    }
    .distinctUntilChanged()

@Database(entities = [CachedBook::class, CacheMetadata::class, CatalogSyncState::class], version = 1, exportSchema = false)
abstract class CatalogDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
}

object CachePolicy {
    const val TTL_MS = 60_000L

    fun isFresh(savedAt: Long, now: Long = System.currentTimeMillis()): Boolean {
        val age = now - savedAt
        return age >= 0 && age < TTL_MS
    }
}
