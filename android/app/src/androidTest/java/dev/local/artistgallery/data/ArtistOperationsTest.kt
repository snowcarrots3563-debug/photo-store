package dev.local.artistgallery.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.artistgallery.model.SaveResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtistOperationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: GalleryDatabase
    private lateinit var repository: RoomGalleryRepository

    @Before fun open() {
        database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        repository = RoomGalleryRepository(database)
    }

    @After fun close() { database.close() }

    private suspend fun artist(id: String) {
        database.galleryDao().insertArtist(ArtistEntity(id, "画师$id", 1, 1))
    }

    private suspend fun collection(id: String, artistId: String) {
        database.galleryDao().insertCollection(CollectionEntity(id, artistId, "作品$id", false, 1, 1))
    }

    private suspend fun asset(id: String, type: String = "IMAGE", state: String = "READY") {
        database.galleryDao().insertAsset(MediaAssetEntity(id, "$id.png", type, 8000, null, null, id, "key-$id", state, 1))
    }

    private suspend fun link(collectionId: String, assetId: String) {
        val dao = database.galleryDao()
        dao.insertCollectionMedia(CollectionMediaEntity(collectionId, assetId, dao.nextManualOrder(collectionId), "$assetId.png", null))
    }

    @Test fun renameNormalizesAndKeepsIdentityCoverAndCollections() = runBlocking {
        artist("a"); artist("b"); collection("c", "a"); asset("image"); link("c", "image")
        assertTrue(repository.setArtistCover("a", "image"))
        val dao = database.galleryDao()
        val original = dao.getArtist("a")!!
        val originalDetail = repository.observeArtist("a").first()!!
        assertEquals(SaveResult.Saved("a"), repository.renameArtist("a", " \u3000改名  中文\n "))
        val renamed = dao.getArtist("a")!!
        assertEquals("改名  中文", renamed.name)
        assertEquals(original.id, renamed.id)
        assertEquals(original.createdAt, renamed.createdAt)
        assertEquals(original.coverAssetId, renamed.coverAssetId)
        assertTrue(renamed.updatedAt >= original.updatedAt)
        val detail = repository.observeArtist("a").first()!!
        assertEquals(originalDetail.collections, detail.collections)
        assertEquals(originalDetail.artist.collectionCount, detail.artist.collectionCount)
        assertEquals(originalDetail.artist.imageCount, detail.artist.imageCount)
        assertEquals(originalDetail.artist.cover, detail.artist.cover)
        assertEquals(SaveResult.Saved("b"), repository.renameArtist("b", "改名  中文"))
    }

    @Test fun blankMissingAndFailedRenamesDoNotChangeArtist() = runBlocking {
        artist("a")
        val dao = database.galleryDao()
        val original = dao.getArtist("a")!!
        assertEquals(SaveResult.InvalidName, repository.renameArtist("a", " \n\u3000"))
        assertEquals(SaveResult.NotFound, repository.renameArtist("missing", "名字"))
        assertEquals(original, dao.getArtist("a"))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_artist_rename BEFORE UPDATE OF name ON Artist " +
                "BEGIN SELECT RAISE(ABORT, 'rename failure'); END"
        )
        assertEquals(SaveResult.StorageFailure, repository.renameArtist("a", "无法保存"))
        assertEquals(original, dao.getArtist("a"))
    }

    @Test fun artistAssetsAreDistinctAndIncludeSharedVideoGifAndPendingDelete() = runBlocking {
        artist("a"); artist("b"); collection("c", "a"); collection("d", "a"); collection("e", "b")
        asset("shared"); asset("gif", "GIF"); asset("video", "VIDEO"); asset("pending", state = "PENDING_DELETE")
        asset("foreign"); asset("unassigned")
        link("c", "shared"); link("d", "shared"); link("e", "shared")
        link("c", "gif"); link("c", "video"); link("d", "pending"); link("e", "foreign")
        val assets = repository.getArtistAssets("a")
        assertEquals(setOf("shared", "gif", "video", "pending"), assets.map { it.id }.toSet())
        assertEquals(4, assets.size)
        assertEquals("PENDING_DELETE", assets.first { it.id == "pending" }.state)
        assertTrue(repository.getArtistAssets("missing").isEmpty())
    }

    @Test fun failedFileDeletionKeepsArtistCollectionsAndReferencesForRetry() = runBlocking {
        artist("a"); collection("c", "a"); asset("image"); link("c", "image")
        assertFalse(repository.deleteArtistIfEmpty("a"))
        assertNotNull(repository.markAssetForDelete("image"))
        assertFalse(repository.deleteArtistIfEmpty("a"))
        repository.restoreAssetAfterDeleteFailure("image")
        assertEquals("READY", repository.getAsset("image")!!.state)
        assertEquals(1, repository.referenceCount("image"))
        assertNotNull(database.galleryDao().getArtist("a"))
        assertNotNull(database.galleryDao().getCollection("c"))
    }

    @Test fun globalAssetCleanupRemovesSharedReferencesAndCoversBeforeArtistDeletion() = runBlocking {
        artist("a"); artist("b"); collection("c", "a"); collection("empty", "a"); collection("d", "b")
        asset("shared"); asset("video", "VIDEO"); asset("foreign")
        link("c", "shared"); link("d", "shared"); link("c", "video"); link("d", "foreign")
        assertTrue(repository.setArtistCover("a", "shared"))
        assertTrue(repository.setArtistCover("b", "shared"))
        for (asset in repository.getArtistAssets("a")) {
            assertNotNull(repository.markAssetForDelete(asset.id))
            repository.completeAssetDelete(asset.id)
        }
        assertTrue(repository.deleteArtistIfEmpty("a"))
        val dao = database.galleryDao()
        assertNull(dao.getArtist("a"))
        assertTrue(dao.getCollections("a").isEmpty())
        assertNull(repository.getAsset("shared"))
        assertNull(repository.getAsset("video"))
        assertEquals(0, repository.referenceCount("shared"))
        assertNull(dao.getArtist("b")!!.coverAssetId)
        assertEquals(listOf("foreign"), repository.getArtistAssets("b").map { it.id })
        assertNotNull(dao.getCollection("d"))
        assertFalse(repository.deleteArtistIfEmpty("a"))
        assertFalse(repository.deleteArtistIfEmpty("b"))
    }

    @Test fun artistDeleteFailureRollsBackEmptyCollectionRemoval() = runBlocking {
        artist("a"); collection("c", "a")
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_artist_delete BEFORE DELETE ON Artist " +
                "BEGIN SELECT RAISE(ABORT, 'delete failure'); END"
        )
        try {
            repository.deleteArtistIfEmpty("a")
            fail("写入失败必须抛出，不能返回已删除")
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            assertNotNull(database.galleryDao().getArtist("a"))
            assertNotNull(database.galleryDao().getCollection("c"))
        }
    }
}
