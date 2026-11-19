package vn.edu.mobileoptimization.cache

import android.content.Context
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: CatalogDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, CatalogDatabase::class.java).build()
    }

    @After
    fun tearDown() { database.close() }

    @Test
    fun snapshotIsAtomicAndEmptyCatalogIsStillCached() {
        val dao = database.catalogDao()
        val original = listOf(CachedBook(1, "Sách cũ", "Tác giả", 100))
        assertNull(dao.readSnapshot())
        dao.replaceSnapshot(original, 1234L)
        assertEquals(CatalogSnapshot(original, 1234L), dao.readSnapshot())

        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_insert BEFORE INSERT ON cached_books " +
                "WHEN NEW.id = -1 BEGIN SELECT RAISE(ABORT, 'forced failure'); END",
        )
        try {
            dao.replaceSnapshot(listOf(CachedBook(-1, "Lỗi", "Tác giả", 200)), 9999L)
            fail("Cần phát sinh lỗi khi lưu để kiểm tra rollback")
        } catch (_: SQLiteException) {
            // Transaction must restore both the previous books and metadata.
        }
        assertEquals(CatalogSnapshot(original, 1234L), dao.readSnapshot())
        dao.replaceSnapshot(emptyList(), 4321L)
        assertEquals(CatalogSnapshot(emptyList(), 4321L), dao.readSnapshot())
        dao.clearSnapshot()
        assertNull(dao.readSnapshot())
    }

    @Test
    fun roomSnapshotRemainsAfterDatabaseIsClosedAndReopened() {
        val name = "room-cache-test-${System.nanoTime()}.db"
        val items = listOf(CachedBook(2, "Sách đã lưu", "Tác giả", 200))
        try {
            val first = Room.databaseBuilder(context, CatalogDatabase::class.java, name).build()
            try { first.catalogDao().replaceSnapshot(items, 5678L) } finally { first.close() }
            val reopened = Room.databaseBuilder(context, CatalogDatabase::class.java, name).build()
            try {
                assertNotNull(reopened.catalogDao().readSnapshot())
                assertEquals(CatalogSnapshot(items, 5678L), reopened.catalogDao().readSnapshot())
            } finally { reopened.close() }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun freshnessExpiresAtSixtySecondsAndRejectsFutureTimestamp() {
        assertTrue(CachePolicy.isFresh(1000L, 1000L))
        assertTrue(CachePolicy.isFresh(1000L, 60_999L))
        assertFalse(CachePolicy.isFresh(1000L, 61_000L))
        assertFalse(CachePolicy.isFresh(1000L, 999L))
    }

    @Test
    fun syncPageAndCursorAreAtomicAndOldGenerationCannotRestoreClearedCache() {
        val dao = database.catalogDao()
        dao.replaceSnapshot(listOf(CachedBook(1, "Sách gốc", "Tác giả", 100)), 10L)
        val initial = dao.ensureSyncState()!!
        val book = CachedBook(10001, "Sách mới", "Tác giả", 200)
        assertTrue(dao.applySyncPage(initial, "epoch", 1L, listOf(book), 20L))
        assertEquals(2, dao.readSnapshot()!!.books.size)
        assertEquals(1L, dao.getSyncState()!!.cursor)
        assertFalse(dao.applySyncPage(initial, "epoch", 1L, listOf(book), 30L))

        val beforeFailure = dao.getSyncState()!!
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_sync BEFORE INSERT ON cached_books WHEN NEW.id = -1 " +
                "BEGIN SELECT RAISE(ABORT, 'forced failure'); END",
        )
        try {
            dao.applySyncPage(beforeFailure, "epoch", 2L, listOf(CachedBook(-1, "Lỗi", "Tác giả", 100)), 30L)
            fail("Phải rollback cả mốc và dữ liệu khi ghi thất bại")
        } catch (_: SQLiteException) { }
        assertEquals(beforeFailure, dao.getSyncState())
        assertEquals(2, dao.readSnapshot()!!.books.size)

        dao.clearSnapshot()
        dao.mergeBaseSnapshot(listOf(CachedBook(1, "Cache mới", "Tác giả", 100)), 40L)
        val newGeneration = dao.ensureSyncState()!!
        assertTrue(newGeneration.generation != beforeFailure.generation)
        assertFalse(dao.applySyncPage(beforeFailure, "epoch", 2L, listOf(book), 50L))
        assertEquals(1, dao.readSnapshot()!!.books.size)
        assertEquals(newGeneration, dao.getSyncState())
    }

    @Test
    fun expandedBasePreservesSyncedBooksAndCannotRestoreClearedCache() {
        val dao = database.catalogDao()
        dao.replaceSnapshot(listOf(CachedBook(1, "Sách cũ", "Tác giả", 100)), 10L)
        assertFalse(dao.hasExpandedBase())

        val initial = dao.ensureSyncState()!!
        val syncedBook = CachedBook(10001, "Sách đồng bộ", "Tác giả", 200)
        assertTrue(dao.applySyncPage(initial, "epoch", 1L, listOf(syncedBook), 20L))
        val expected = dao.getSyncState()!!
        val expanded = (1..1000).map { CachedBook(it, "Sách $it", "Tác giả", 100) }
        assertTrue(dao.upgradeBaseSnapshot(expected, expanded, 30L))
        assertTrue(dao.hasExpandedBase())
        assertEquals(1001, dao.readSnapshot()!!.books.size)
        assertEquals(syncedBook, dao.readSnapshot()!!.books.last())
        assertEquals(expected, dao.getSyncState())

        dao.clearSnapshot()
        assertFalse(dao.hasExpandedBase())
        assertFalse(dao.upgradeBaseSnapshot(expected, expanded, 40L))
        assertNull(dao.readSnapshot())
    }

    @Test
    fun migrationFromVersionOneKeepsExistingBooksAndMetadata() {
        val name = "room-cache-migration-${System.nanoTime()}.db"
        try {
            val path = context.getDatabasePath(name)
            path.parentFile?.mkdirs()
            val old = SQLiteDatabase.openOrCreateDatabase(path, null)
            try {
                old.execSQL("CREATE TABLE IF NOT EXISTS cached_books (id INTEGER NOT NULL, title TEXT NOT NULL, author TEXT NOT NULL, priceVnd INTEGER NOT NULL, PRIMARY KEY(id))")
                old.execSQL("CREATE TABLE IF NOT EXISTS cache_metadata (`key` TEXT NOT NULL, savedAt INTEGER NOT NULL, PRIMARY KEY(`key`))")
                old.execSQL("INSERT INTO cached_books VALUES (7, 'Sách cũ', 'Tác giả', 500)")
                old.execSQL("INSERT INTO cache_metadata VALUES ('catalog', 1234)")
                old.version = 1
            } finally { old.close() }
            val migrated = Room.databaseBuilder(context, CatalogDatabase::class.java, name)
                .addMigrations(CATALOG_MIGRATION_1_2).build()
            try {
                assertEquals(CatalogSnapshot(listOf(CachedBook(7, "Sách cũ", "Tác giả", 500)), 1234L), migrated.catalogDao().readSnapshot())
                assertNotNull(migrated.catalogDao().ensureSyncState())
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
}
