package dev.local.artistgallery.data

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic databases only; no ordinary gallery database or media files. */
@RunWith(AndroidJUnit4::class)
class GalleryIndexMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val indexName = "index_CollectionMedia_collectionId_manualOrder_assetId"
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GalleryDatabase::class.java)

    @Test fun allHistoricalVersionsUpgradeToV5PreservingExistingRows() {
        for (version in 1..4) {
            val name = "order-index-v$version-${UUID.randomUUID()}.db"
            try {
                val snapshots = migration.createDatabase(name, version).use { db ->
                    seedLegacy(db, version)
                    val tables = mutableListOf("Artist", "WorkCollection")
                    if (version >= 2) tables += listOf("MediaAsset", "CollectionMedia", "ImportBatch", "ImportItem")
                    if (version >= 3) tables += listOf("ArchiveTask", "ArchiveGroup")
                    tables.associateWith { table ->
                        db.query("SELECT * FROM $table ORDER BY rowid").use { it.columnNames.toList() to rows(it) }
                    }
                }
                migration.runMigrationsAndValidate(name, 5, true, GalleryDatabase.MIGRATION_1_2,
                    GalleryDatabase.MIGRATION_2_3, GalleryDatabase.MIGRATION_3_4, GalleryDatabase.MIGRATION_4_5).use { db ->
                    assertEquals(5, db.version)
                    snapshots.forEach { (table, snapshot) ->
                        db.query("SELECT ${snapshot.first.joinToString()} FROM $table ORDER BY rowid").use {
                            assertEquals("v$version must preserve $table", snapshot.second, rows(it))
                        }
                    }
                    db.query("SELECT coverAssetId FROM Artist WHERE id = 'a'").use {
                        assertTrue(it.moveToFirst())
                        if (version == 4) assertEquals("shared", it.getString(0)) else assertTrue(it.isNull(0))
                    }
                    db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                    assertOrderIndex(db)
                }
            } finally { context.deleteDatabase(name) }
        }
    }

    @Test fun freshDatabaseKeepsV5CoveringOrderIndex() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            assertEquals(0, db.galleryDao().nextManualOrder("empty"))
            assertEquals(6, db.openHelper.writableDatabase.version)
            assertOrderIndex(db.openHelper.writableDatabase)
        } finally { db.close() }
    }

    @Test fun nextManualOrderPreservesBoundaryValuesAndIndependentGroups() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val dao = db.galleryDao()
            dao.insertArtist(ArtistEntity("a", "Synthetic artist", 1, 1))
            val cases = linkedMapOf("empty" to emptyList(), "single" to listOf(5), "gap" to listOf(0, 3, 19),
                "repeated" to listOf(7, 7), "negative" to listOf(-9, -4), "mixed" to listOf(-9, -1, 0, 12))
            cases.forEach { (collection, orders) ->
                dao.insertCollection(CollectionEntity(collection, "a", collection, false, 1, 1))
                orders.forEachIndexed { position, order ->
                    val id = "$collection-$position"
                    dao.insertAsset(asset(id))
                    dao.insertCollectionMedia(CollectionMediaEntity(collection, id, order, "$id.png", "$collection/$id.png"))
                }
            }
            dao.insertAsset(asset("shared"))
            dao.insertCollectionMedia(CollectionMediaEntity("gap", "shared", 2, "shared-a.png", "gap/shared-a.png"))
            dao.insertCollectionMedia(CollectionMediaEntity("negative", "shared", -8, "shared-b.png", "negative/shared-b.png"))
            val before = dao.allReferences()
            val expected = mapOf("empty" to 0, "single" to 6, "gap" to 20, "repeated" to 8, "negative" to -3, "mixed" to 13)
            expected.forEach { (collection, order) -> assertEquals(collection, order, dao.nextManualOrder(collection)) }
            assertEquals(before, dao.allReferences())
            assertEquals(2, dao.referenceCount("shared"))
        } finally { db.close() }
    }

    @Test fun largeGroupAppendKeepsExistingOrdersAndDoesNotAffectAnotherGroup() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val dao = db.galleryDao()
            dao.insertArtist(ArtistEntity("a", "Synthetic artist", 1, 1))
            for (id in listOf("large", "other")) dao.insertCollection(CollectionEntity(id, "a", id, false, 1, 1))
            for (position in 0 until 1000) {
                val id = "asset-$position"
                dao.insertAsset(asset(id))
                dao.insertCollectionMedia(CollectionMediaEntity("large", id, position * 2, "$id.png", null))
            }
            dao.insertCollectionMedia(CollectionMediaEntity("other", "asset-0", 5000, "shared.png", null))
            val before = dao.allReferences()
            assertEquals(1999, dao.nextManualOrder("large"))
            dao.insertAsset(asset("appended"))
            dao.insertCollectionMedia(CollectionMediaEntity("large", "appended", dao.nextManualOrder("large"), "appended.png", null))
            assertEquals(before, dao.allReferences().filter { it.assetId != "appended" })
            assertEquals(2000, dao.nextManualOrder("large"))
            assertEquals(5001, dao.nextManualOrder("other"))
            assertOrderIndex(db.openHelper.writableDatabase)
        } finally { db.close() }
    }

    private fun assertOrderIndex(db: SupportSQLiteDatabase) {
        db.query("PRAGMA index_list('CollectionMedia')").use { cursor ->
            var found = false
            while (cursor.moveToNext()) if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == indexName) {
                found = true
                assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("unique")))
            }
            assertTrue("The order index must exist", found)
        }
        db.query("PRAGMA index_info('$indexName')").use { cursor ->
            val columns = mutableListOf<String>()
            while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            assertEquals(listOf("collectionId", "manualOrder", "assetId"), columns)
        }
        db.query("EXPLAIN QUERY PLAN SELECT COALESCE(MAX(manualOrder), -1) + 1 FROM CollectionMedia WHERE collectionId = ?", arrayOf("large")).use { cursor ->
            var covered = false
            while (cursor.moveToNext()) {
                val detail = cursor.getString(cursor.getColumnIndexOrThrow("detail"))
                if (detail.contains(indexName) && detail.contains("COVERING INDEX", ignoreCase = true)) covered = true
            }
            assertTrue("nextManualOrder must use the covering index", covered)
        }
    }

    private fun seedLegacy(db: SupportSQLiteDatabase, version: Int) {
        db.execSQL("INSERT INTO Artist (id, name, createdAt, updatedAt) VALUES ('a', 'Preserved artist', 1, 2), ('b', 'Second artist', 3, 4)")
        db.execSQL("INSERT INTO WorkCollection VALUES ('c', 'a', 'Original collection', 0, 5, 6), ('d', 'b', 'Temporary collection', 1, 7, 8)")
        if (version >= 2) {
            db.execSQL("INSERT INTO MediaAsset VALUES ('shared', 'original.png', 'IMAGE', 8000, 100, 50, 'hash-shared', 'original-shared', 'READY', 9), ('other', 'other.gif', 'GIF', 9000, NULL, NULL, 'hash-other', 'original-other', 'PENDING_DELETE', 10)")
            db.execSQL("INSERT INTO CollectionMedia VALUES ('c', 'shared', -4, 'source-a.png', 'directory/source-a.png'), ('c', 'other', 19, 'source.gif', NULL), ('d', 'shared', 3, 'source-b.png', 'different/source-b.png')")
            db.execSQL("INSERT INTO ImportBatch VALUES ('batch', 'c', 'INTERRUPTED', 11, 12)")
            db.execSQL("INSERT INTO ImportItem (id, batchId, targetCollectionId, sourceUri, hasPersistedPermission, sourceName, mediaType, stagingKey, storageKey, sha256, byteSize, status, errorMessage, createdAt, updatedAt) VALUES ('item', 'batch', 'c', 'archive://fixture/item', 0, 'kept.png', 'IMAGE', 'stage-fixture', 'original-fixture', 'pending-hash', 8011, 'STAGED', 'preserved-error', 13, 14)")
        }
        if (version >= 3) {
            db.execSQL("UPDATE ImportItem SET archiveGroupId = 'group', sourceRelativePath = 'directory/kept.png' WHERE id = 'item'")
            db.execSQL("""INSERT INTO ArchiveTask VALUES ('task', 'a', 'ZIP', 'fixture.zip', '["fixture.zip"]', 15)""")
            db.execSQL("INSERT INTO ArchiveGroup VALUES ('group', 'batch', 'a', 'Archive collection', 'directory')")
        }
        if (version == 4) db.execSQL("UPDATE Artist SET coverAssetId = 'shared' WHERE id = 'a'")
    }

    private fun rows(cursor: Cursor): List<List<String?>> = buildList {
        while (cursor.moveToNext()) add(cursor.columnNames.indices.map { if (cursor.isNull(it)) null else cursor.getString(it) })
    }

    private fun asset(id: String) = MediaAssetEntity(id, "$id.png", "IMAGE", 8000, null, null, "hash-$id", "key-$id", "READY", 1)
}
