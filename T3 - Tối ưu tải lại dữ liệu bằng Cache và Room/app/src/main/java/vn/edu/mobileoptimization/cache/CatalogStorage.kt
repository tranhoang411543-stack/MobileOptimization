package vn.edu.mobileoptimization.cache

import android.app.Application
import android.app.Activity
import android.os.Bundle
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID
import java.util.concurrent.Executors

@Entity(tableName = "cached_books")
data class CachedBook(
    @PrimaryKey val id: Int,
    val title: String,
    val author: String,
    val priceVnd: Int,
)

@Entity(tableName = "cache_metadata")
data class CacheMetadata(@PrimaryKey val key: String, val savedAt: Long)

data class CatalogSnapshot(val books: List<CachedBook>, val savedAt: Long)

private const val EXPANDED_BASE_KEY = "catalog_base_1000"

@Entity(tableName = "catalog_sync_state")
data class CatalogSyncState(
    @PrimaryKey val id: Int = 1,
    val generation: String,
    val epoch: String,
    val cursor: Long,
)

@Dao
abstract class CatalogDao {
    @Query("SELECT * FROM cached_books ORDER BY id")
    abstract fun getBooks(): List<CachedBook>

    @Query("SELECT * FROM cache_metadata WHERE `key` = 'catalog' LIMIT 1")
    abstract fun getMetadata(): CacheMetadata?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertBooks(books: List<CachedBook>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertMetadata(metadata: CacheMetadata)

    @Query("DELETE FROM cached_books")
    abstract fun deleteBooks()

    @Query("DELETE FROM cache_metadata WHERE `key` = 'catalog'")
    abstract fun deleteMetadata()

    @Query("SELECT EXISTS(SELECT 1 FROM cache_metadata WHERE `key` = 'catalog_base_1000')")
    abstract fun hasExpandedBase(): Boolean

    @Query("DELETE FROM cache_metadata WHERE `key` = 'catalog_base_1000'")
    abstract fun deleteExpandedBaseMetadata()

    @Query("SELECT * FROM catalog_sync_state WHERE id = 1 LIMIT 1")
    abstract fun getSyncState(): CatalogSyncState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun setSyncState(state: CatalogSyncState)

    @Query("DELETE FROM catalog_sync_state")
    abstract fun deleteSyncState()

    @Transaction
    open fun readSnapshot(): CatalogSnapshot? {
        val metadata = getMetadata() ?: return null
        return CatalogSnapshot(getBooks(), metadata.savedAt)
    }

    @Transaction
    open fun replaceSnapshot(books: List<CachedBook>, savedAt: Long) {
        deleteBooks()
        insertBooks(books)
        insertMetadata(CacheMetadata("catalog", savedAt))
        deleteExpandedBaseMetadata()
        deleteSyncState()
    }

    /** Manual refresh updates the base page without removing books received by sync. */
    @Transaction
    open fun mergeBaseSnapshot(books: List<CachedBook>, savedAt: Long) {
        if (getMetadata() == null) deleteSyncState()
        insertBooks(books)
        insertMetadata(CacheMetadata("catalog", savedAt))
        insertMetadata(CacheMetadata(EXPANDED_BASE_KEY, savedAt))
    }

    /** Merge the expanded base only if the cache has not been cleared or replaced. */
    @Transaction
    open fun upgradeBaseSnapshot(expected: CatalogSyncState, books: List<CachedBook>, savedAt: Long): Boolean {
        if (getMetadata() == null || getSyncState() != expected) return false
        insertBooks(books)
        insertMetadata(CacheMetadata("catalog", savedAt))
        insertMetadata(CacheMetadata(EXPANDED_BASE_KEY, savedAt))
        return true
    }

    @Transaction
    open fun ensureSyncState(): CatalogSyncState? {
        if (getMetadata() == null) return null
        return getSyncState() ?: CatalogSyncState(generation = UUID.randomUUID().toString(), epoch = "", cursor = 0)
            .also { setSyncState(it) }
    }

    @Transaction
    open fun applySyncPage(expected: CatalogSyncState, epoch: String, cursor: Long, books: List<CachedBook>, savedAt: Long): Boolean {
        if (getMetadata() == null || getSyncState() != expected) return false
        insertBooks(books)
        setSyncState(expected.copy(epoch = epoch, cursor = cursor))
        if (books.isNotEmpty()) insertMetadata(CacheMetadata("catalog", savedAt))
        return true
    }

    @Transaction
    open fun resetSyncSnapshot(expected: CatalogSyncState, epoch: String, cursor: Long, books: List<CachedBook>, savedAt: Long): Boolean {
        if (getMetadata() == null || getSyncState() != expected) return false
        deleteBooks()
        insertBooks(books)
        insertMetadata(CacheMetadata("catalog", savedAt))
        insertMetadata(CacheMetadata(EXPANDED_BASE_KEY, savedAt))
        setSyncState(expected.copy(epoch = epoch, cursor = cursor))
        return true
    }

    @Transaction
    open fun clearSnapshot() {
        deleteBooks()
        deleteMetadata()
        deleteExpandedBaseMetadata()
        deleteSyncState()
    }
}

@Database(entities = [CachedBook::class, CacheMetadata::class, CatalogSyncState::class], version = 2, exportSchema = false)
abstract class CatalogDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
}

class CacheLabApp : Application(), Application.ActivityLifecycleCallbacks {
    lateinit var database: CatalogDatabase
        private set
    val diskExecutor = Executors.newSingleThreadExecutor()
    val networkExecutor = Executors.newSingleThreadExecutor()
    lateinit var sync: CatalogSync
        private set
    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        database = Room.databaseBuilder(applicationContext, CatalogDatabase::class.java, "catalog-cache.db")
            .addMigrations(CATALOG_MIGRATION_1_2).build()
        sync = CatalogSync(this, database)
        val settings = getSharedPreferences("demo-settings", MODE_PRIVATE)
        sync.configure(settings.getBoolean("optimized", false))
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
        if (startedActivities == 1) sync.setForeground(true)
    }
    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0) sync.setForeground(false)
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

val CATALOG_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `catalog_sync_state` (`id` INTEGER NOT NULL, `generation` TEXT NOT NULL, `epoch` TEXT NOT NULL, `cursor` INTEGER NOT NULL, PRIMARY KEY(`id`))")
    }
}

object CachePolicy {
    const val TTL_MS = 60_000L

    fun isFresh(savedAt: Long, now: Long = System.currentTimeMillis()): Boolean {
        val age = now - savedAt
        return age >= 0 && age < TTL_MS
    }
}
