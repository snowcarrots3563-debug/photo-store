package dev.local.artistgallery.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.artistgallery.data.deleting.deleteMediaCopies
import java.util.Collections
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchDeleteRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: GalleryDatabase
    private lateinit var repository: RoomGalleryRepository
    private lateinit var dao: GalleryDao
    private val queries = Collections.synchronizedList(mutableListOf<String>())

    @Before fun open() {
        database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java)
            .setQueryCallback({ sql, _ -> queries.add(sql) }, Executor { it.run() }).build()
        repository = RoomGalleryRepository(database)
        dao = database.galleryDao()
    }

    @After fun close() { database.close() }

    @Test fun identityChecksReadOnlyPrimaryKeysForEmptyAndMissingEntities() = runBlocking {
        seedArtists()
        queries.clear()
        assertTrue(repository.artistExists("a"))
        assertFalse(repository.artistExists("missing"))
        assertTrue(repository.collectionExists("c"))
        assertFalse(repository.collectionExists("missing"))
        val reads = synchronized(queries) { queries.toList() }
            .map(String::trim).filter { it.startsWith("SELECT", ignoreCase = true) && !it.contains("room_", ignoreCase = true) }
        assertEquals(listOf("SELECT * FROM Artist WHERE id = ?", "SELECT * FROM Artist WHERE id = ?",
            "SELECT * FROM WorkCollection WHERE id = ?", "SELECT * FROM WorkCollection WHERE id = ?"), reads)
        assertTrue(repository.deleteArtistIfEmpty("a"))
        assertFalse(repository.artistExists("a"))
        assertFalse(repository.collectionExists("c"))
        assertTrue(repository.artistExists("b"))
    }

    @Test fun thousandAssetsUseFourBatchUpdatesAndFourCleanupTransactions() = runBlocking {
        val ids = (0 until 1000).map(Int::toString)
        database.withTransaction { ids.forEach { dao.insertAsset(asset(it)) } }
        queries.clear()
        val result = deleteMediaCopies(repository, ids, { true }) { _, _ -> }
        val recorded = synchronized(queries) { queries.toList() }
        assertEquals(ids.toSet(), result.deletedIds)
        assertEquals(4, recorded.count { it.startsWith("UPDATE MediaAsset SET state = 'PENDING_DELETE' WHERE id IN") })
        assertEquals(4, recorded.count { it.startsWith("SELECT * FROM MediaAsset WHERE id IN") })
        assertEquals(4, recorded.count { it.startsWith("UPDATE Artist SET coverAssetId = NULL WHERE coverAssetId IN") })
        assertEquals(4, recorded.count { it.startsWith("DELETE FROM CollectionMedia WHERE assetId IN") })
        assertEquals(4, recorded.count { it.startsWith("DELETE FROM MediaAsset WHERE id IN") })
        assertEquals(4, recorded.count { it.trimStart().startsWith("DELETE FROM WorkCollection WHERE id IN") })
        assertFalse(recorded.any { it.startsWith("UPDATE MediaAsset SET state = 'PENDING_DELETE' WHERE id =") })
        assertTrue(dao.allAssets().isEmpty())
    }

    @Test fun sharedSuccessClearsAllReferencesAndCoversWhileFailureKeepsThem() = runBlocking {
        seedArtists()
        dao.insertAsset(asset("shared")); dao.insertAsset(asset("failed")); dao.insertAsset(asset("untouched"))
        link("c", "shared"); link("d", "shared"); link("c", "failed"); link("d", "untouched")
        assertTrue(repository.setArtistCover("a", "failed"))
        assertTrue(repository.setArtistCover("b", "shared"))
        val result = deleteMediaCopies(repository, listOf("shared", "failed"), { it != "key-failed" }) { _, _ -> }
        assertEquals(setOf("shared"), result.deletedIds)
        assertEquals(setOf("failed"), result.failedIds)
        assertNull(repository.getAsset("shared"))
        assertEquals(0, repository.referenceCount("shared"))
        assertNull(dao.getArtist("b")!!.coverAssetId)
        assertEquals("READY", repository.getAsset("failed")!!.state)
        assertEquals(1, repository.referenceCount("failed"))
        assertEquals("failed", dao.getArtist("a")!!.coverAssetId)
        assertNotNull(repository.getAsset("untouched"))
        assertEquals(1, repository.referenceCount("untouched"))
    }

    @Test fun markTriggerFailureRollsBackBeforeAnyFileOperation() = runBlocking {
        dao.insertAsset(asset("first")); dao.insertAsset(asset("bad"))
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_mark BEFORE UPDATE OF state ON MediaAsset
            WHEN NEW.state = 'PENDING_DELETE' AND OLD.id = 'bad' BEGIN SELECT RAISE(ABORT, 'mark failure'); END""")
        var removed = 0
        var callbacks = 0
        val failure = try {
            deleteMediaCopies(repository, listOf("first", "bad"), { removed++; true }) { _, _ -> callbacks++ }
            null
        } catch (error: Exception) { error }
        assertNotNull(failure)
        assertEquals(0, removed)
        assertEquals(0, callbacks)
        assertEquals("READY", repository.getAsset("first")!!.state)
        assertEquals("READY", repository.getAsset("bad")!!.state)
    }

    @Test fun cleanupTriggerFailureRollsBackCoversReferencesAndFailedRestoreThenLegacyRecoveryWorks() = runBlocking {
        seedArtists()
        listOf("first", "bad", "failed").forEach { dao.insertAsset(asset(it)); link("c", it) }
        link("d", "first")
        assertTrue(repository.setArtistCover("a", "first"))
        assertTrue(repository.setArtistCover("b", "first"))
        database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_cleanup BEFORE DELETE ON MediaAsset
            WHEN OLD.id = 'bad' BEGIN SELECT RAISE(ABORT, 'cleanup failure'); END""")
        var callbacks = 0
        val failure = try {
            deleteMediaCopies(repository, listOf("first", "bad", "failed"), { it != "key-failed" }) { _, _ -> callbacks++ }
            null
        } catch (error: Exception) { error }
        assertNotNull(failure)
        assertEquals(0, callbacks)
        assertEquals("first", dao.getArtist("a")!!.coverAssetId)
        assertEquals("first", dao.getArtist("b")!!.coverAssetId)
        assertEquals(2, repository.referenceCount("first"))
        assertEquals(1, repository.referenceCount("bad"))
        assertEquals(1, repository.referenceCount("failed"))
        assertEquals(setOf("first", "bad", "failed"), repository.pendingDeleteAssets().map { it.id }.toSet())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_cleanup")
        // The same single-asset API used by recoverPending remains valid after batch rollback.
        repository.pendingDeleteAssets().forEach { repository.completeAssetDelete(it.id) }
        assertTrue(repository.pendingDeleteAssets().isEmpty())
        assertEquals(0, repository.referenceCount("first"))
        assertNull(dao.getArtist("a")!!.coverAssetId)
        assertNull(dao.getArtist("b")!!.coverAssetId)
        assertNull(dao.getCollection("c"))
        assertNull(dao.getCollection("d"))
        assertFalse(repository.deleteCollectionIfEmpty("c"))
    }

    @Test fun joinCandidatesMatchExistsIncludingPendingAndSharedAssetsInStableOrder() = runBlocking {
        seedArtists()
        dao.insertCollection(CollectionEntity("e", "a", "甲二", false, 2, 2))
        listOf(asset("z", createdAt = 1), asset("a", "PENDING_DELETE", 1),
            asset("late", createdAt = 5), asset("foreign", createdAt = 0)).forEach { dao.insertAsset(it) }
        link("c", "z"); link("c", "a"); link("e", "a"); link("e", "late"); link("d", "a"); link("d", "foreign")
        val oldArtist = oldIds("""SELECT ma.id FROM MediaAsset ma WHERE EXISTS(
            SELECT 1 FROM WorkCollection wc JOIN CollectionMedia cm ON cm.collectionId = wc.id
            WHERE wc.artistId = ? AND cm.assetId = ma.id) ORDER BY ma.createdAt ASC, ma.id ASC""", "a")
        val oldCollection = oldIds("""SELECT ma.id FROM MediaAsset ma WHERE EXISTS(
            SELECT 1 FROM CollectionMedia cm WHERE cm.collectionId = ? AND cm.assetId = ma.id)
            ORDER BY ma.createdAt ASC, ma.id ASC""", "c")
        assertEquals(listOf("a", "z", "late"), oldArtist)
        assertEquals(oldArtist, repository.getArtistAssets("a").map { it.id })
        assertEquals(oldCollection, repository.getCollectionAssets("c").map { it.id })
        assertEquals(1, repository.getArtistAssets("a").count { it.id == "a" })
    }

    @Test fun pendingDuplicatesMissingAndOtherStatesAreClassifiedWithoutUnlinkingUnavailable() = runBlocking {
        dao.insertAsset(asset("ready")); dao.insertAsset(asset("pending", "PENDING_DELETE")); dao.insertAsset(asset("blocked", "IMPORTING"))
        val removed = mutableListOf<String>()
        val result = deleteMediaCopies(repository, listOf("pending", "missing", "ready", "ready", "blocked"), {
            removed += it
            true
        }) { _, _ -> }
        assertEquals(listOf("key-pending", "key-ready"), removed)
        assertEquals(setOf("pending", "ready"), result.deletedIds)
        assertEquals(setOf("missing", "blocked"), result.unavailableIds)
        assertEquals("IMPORTING", repository.getAsset("blocked")!!.state)
    }

    @Test fun emptyBatchIsNoOpAndOversizedOrOverlappingBatchIsRejected() = runBlocking {
        dao.insertAsset(asset("first"))
        queries.clear()
        assertTrue(repository.markAssetsForDelete(emptyList()).isEmpty())
        repository.finishAssetDeletes(emptyList(), emptyList())
        val recorded = synchronized(queries) { queries.toList() }
        assertFalse(recorded.any { it.startsWith("UPDATE MediaAsset") || it.startsWith("DELETE FROM MediaAsset") ||
            it.startsWith("DELETE FROM CollectionMedia") || it.trimStart().startsWith("DELETE FROM WorkCollection") ||
            it.startsWith("UPDATE Artist") || it.startsWith("SELECT * FROM MediaAsset WHERE id IN") })
        suspend fun rejects(operation: suspend () -> Unit) {
            try { operation(); fail("expected batch guard") } catch (_: IllegalArgumentException) { }
        }
        rejects { repository.markAssetsForDelete((0..256).map(Int::toString)) }
        rejects { repository.finishAssetDeletes((0..256).map(Int::toString), emptyList()) }
        rejects { repository.finishAssetDeletes(listOf("first"), listOf("first")) }
        assertEquals("READY", repository.getAsset("first")!!.state)
    }

    @Test fun entityEmptyCheckProtectsNewlyAddedMediaAfterCandidateCleanup() = runBlocking {
        seedArtists()
        dao.insertAsset(asset("old")); link("c", "old")
        dao.insertAsset(asset("remaining")); link("c", "remaining")
        deleteMediaCopies(repository, listOf("old"), { true }) { _, _ -> }
        dao.insertAsset(asset("new")); link("c", "new")
        assertFalse(repository.deleteCollectionIfEmpty("c"))
        assertFalse(repository.deleteArtistIfEmpty("a"))
        assertNotNull(dao.getCollection("c"))
        assertNotNull(dao.getArtist("a"))
        assertEquals(1, repository.referenceCount("new"))
    }

    @Test fun successfulCopiesDeleteOnlyAffectedEmptyCollectionsAcrossArtists() = runBlocking {
        seedArtists()
        dao.insertCollection(CollectionEntity("empty", "a", "手工空图集", false, 1, 1))
        dao.insertCollection(CollectionEntity("untouched", "b", "未涉及", false, 1, 1))
        dao.insertAsset(asset("shared")); dao.insertAsset(asset("second")); dao.insertAsset(asset("other"))
        link("c", "shared"); link("c", "second"); link("d", "shared"); link("untouched", "other")
        assertTrue(repository.setArtistCover("b", "shared"))
        val result = deleteMediaCopies(repository, listOf("shared", "second"), { true }) { _, _ -> }
        assertEquals(setOf("shared", "second"), result.deletedIds)
        assertNull(dao.getCollection("c")); assertNull(dao.getCollection("d"))
        assertNotNull(dao.getCollection("empty")); assertNotNull(dao.getCollection("untouched"))
        assertNotNull(dao.getArtist("a")); assertNotNull(dao.getArtist("b"))
        assertNull(dao.getArtist("b")!!.coverAssetId)
        assertEquals(1, repository.referenceCount("other"))
    }

    @Test fun videoGifFailedPendingAndUnavailableReferencesAllKeepCollections() = runBlocking {
        seedArtists()
        listOf("gif", "failed", "pending", "staged").forEach {
            dao.insertCollection(CollectionEntity(it, "a", it, false, 1, 1))
        }
        dao.insertAsset(asset("shared"))
        listOf("c", "gif", "failed", "pending", "staged").forEach { link(it, "shared") }
        dao.insertAsset(asset("video").copy(mediaType = "VIDEO")); link("c", "video")
        dao.insertAsset(asset("gif").copy(mediaType = "GIF")); link("gif", "gif")
        dao.insertAsset(asset("failed")); link("failed", "failed")
        dao.insertAsset(asset("pending", "PENDING_DELETE")); link("pending", "pending")
        dao.insertAsset(asset("staged", "STAGED")); link("staged", "staged")
        val result = deleteMediaCopies(repository, listOf("shared", "failed"), { it != "key-failed" }) { _, _ -> }
        assertEquals(setOf("shared"), result.deletedIds)
        assertEquals(setOf("failed"), result.failedIds)
        listOf("c", "gif", "failed", "pending", "staged", "d").forEach { assertNotNull(dao.getCollection(it)) }
        listOf("video", "gif", "failed", "pending", "staged").forEach { assertEquals(1, repository.referenceCount(it)) }
        assertEquals("READY", repository.getAsset("failed")!!.state)
        assertEquals("PENDING_DELETE", repository.getAsset("pending")!!.state)
        assertEquals("STAGED", repository.getAsset("staged")!!.state)
    }

    @Test fun collectionSpanningBatchesIsRemovedOnlyAfterFinalSuccessfulBatch() = runBlocking {
        seedArtists()
        val ids = (0..256).map(Int::toString)
        database.withTransaction { ids.forEach { dao.insertAsset(asset(it)); link("c", it) } }
        link("d", ids.last())
        var batches = 0
        val inspectingRepository = object : MediaLibraryRepository by repository {
            override suspend fun finishAssetDeletes(deletedIds: List<String>, failedIds: List<String>) {
                repository.finishAssetDeletes(deletedIds, failedIds)
                batches++
                if (batches == 1) {
                    assertNotNull(dao.getCollection("c")); assertNotNull(dao.getCollection("d"))
                    assertEquals(2, repository.referenceCount(ids.last()))
                } else {
                    assertNull(dao.getCollection("c")); assertNull(dao.getCollection("d"))
                }
            }
        }
        deleteMediaCopies(inspectingRepository, ids, { true }) { _, _ -> }
        assertEquals(2, batches)
        assertNull(dao.getCollection("c")); assertNull(dao.getCollection("d"))
        assertNotNull(dao.getArtist("a")); assertNotNull(dao.getArtist("b"))
        assertTrue(dao.allAssets().isEmpty())
    }

    @Test fun cancellationAfterCommittedBatchRetainsCollectionWithUntouchedPendingCopy() = runBlocking {
        seedArtists()
        val ids = (0..256).map(Int::toString)
        database.withTransaction { ids.forEach { dao.insertAsset(asset(it)); link("c", it) } }
        var batches = 0
        try {
            deleteMediaCopies(repository, ids, {
                if (it == "key-256") throw CancellationException("stop before final file")
                true
            }) { _, _ -> batches++ }
            fail("expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(2, batches)
        assertNotNull(dao.getCollection("c"))
        assertEquals("PENDING_DELETE", repository.getAsset("256")!!.state)
        assertEquals(1, repository.referenceCount("256"))
        assertEquals(listOf("256"), dao.allAssets().map { it.id })
        repository.completeAssetDelete("256")
        assertNull(dao.getCollection("c"))
        assertNotNull(dao.getCollection("d"))
    }

    @Test fun collectionDeleteFailureRollsBackCoversReferencesAndFailureRestore() = runBlocking {
        seedArtists()
        dao.insertAsset(asset("shared")); dao.insertAsset(asset("failed"))
        link("c", "shared"); link("d", "shared"); link("d", "failed")
        assertTrue(repository.setArtistCover("a", "shared"))
        assertTrue(repository.setArtistCover("b", "shared"))
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_empty_cleanup BEFORE DELETE ON WorkCollection BEGIN SELECT RAISE(ABORT, 'cleanup failure'); END")
        var callbacks = 0
        try {
            deleteMediaCopies(repository, listOf("shared", "failed"), { it != "key-failed" }) { _, _ -> callbacks++ }
            fail("collection cleanup failure must be reported")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertEquals(0, callbacks)
        assertNotNull(dao.getCollection("c")); assertNotNull(dao.getCollection("d"))
        assertEquals(2, repository.referenceCount("shared")); assertEquals(1, repository.referenceCount("failed"))
        assertEquals("shared", dao.getArtist("a")!!.coverAssetId)
        assertEquals("shared", dao.getArtist("b")!!.coverAssetId)
        assertEquals(setOf("shared", "failed"), repository.pendingDeleteAssets().map { it.id }.toSet())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_empty_cleanup")
        repository.finishAssetDeletes(listOf("shared"), listOf("failed"))
        assertNull(dao.getCollection("c")); assertNotNull(dao.getCollection("d"))
        assertEquals("READY", repository.getAsset("failed")!!.state)
        assertNull(dao.getArtist("a")!!.coverAssetId); assertNull(dao.getArtist("b")!!.coverAssetId)
    }

    @Test fun singlePendingRecoveryRemovesGroupsOnlyWhenTheirLastReferenceIsDeleted() = runBlocking {
        seedArtists()
        dao.insertCollection(CollectionEntity("empty", "a", "手工空图集", false, 1, 1))
        dao.insertAsset(asset("first", "PENDING_DELETE")); dao.insertAsset(asset("last", "PENDING_DELETE"))
        link("c", "first"); link("c", "last"); link("d", "first")
        repository.completeAssetDelete("first")
        assertNull(dao.getCollection("d")); assertNotNull(dao.getCollection("c"))
        assertEquals(1, repository.referenceCount("last"))
        repository.completeAssetDelete("last")
        assertNull(dao.getCollection("c"))
        assertNotNull(dao.getCollection("empty"))
        assertNotNull(dao.getArtist("a")); assertNotNull(dao.getArtist("b"))
        assertTrue(repository.pendingDeleteAssets().isEmpty())
    }

    private suspend fun seedArtists() {
        dao.insertArtist(ArtistEntity("a", "甲", 1, 1))
        dao.insertArtist(ArtistEntity("b", "乙", 2, 2))
        dao.insertCollection(CollectionEntity("c", "a", "甲作品", false, 1, 1))
        dao.insertCollection(CollectionEntity("d", "b", "乙作品", false, 2, 2))
    }

    private suspend fun link(collectionId: String, assetId: String) =
        dao.insertCollectionMedia(CollectionMediaEntity(collectionId, assetId, 0, "$assetId.png", null))

    private fun oldIds(sql: String, id: String): List<String> =
        database.openHelper.readableDatabase.query(sql, arrayOf(id)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun asset(id: String, state: String = "READY", createdAt: Long = 1) =
        MediaAssetEntity(id, "$id.png", "IMAGE", 8000, null, null, id, "key-$id", state, createdAt)
}
