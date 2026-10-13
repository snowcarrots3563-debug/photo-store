package dev.local.artistgallery.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionOperationsTest {
    private lateinit var database: GalleryDatabase
    private lateinit var repository: RoomGalleryRepository
    @Before fun open() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        repository = RoomGalleryRepository(database)
    }
    @After fun close() { database.close() }

    private suspend fun seed() {
        val dao = database.galleryDao()
        dao.insertArtist(ArtistEntity("a", "画师", 1, 1))
        dao.insertArtist(ArtistEntity("b", "其他", 1, 1))
        dao.insertCollection(CollectionEntity("c", "a", "待删除", false, 1, 1))
        dao.insertCollection(CollectionEntity("d", "b", "共享", false, 1, 1))
        listOf("shared" to "READY", "pending" to "PENDING_DELETE").forEach { (id, state) ->
            dao.insertAsset(MediaAssetEntity(id, "$id.png", "IMAGE", 4, null, null, id, "key-$id", state, 1))
            dao.insertCollectionMedia(CollectionMediaEntity("c", id, dao.nextManualOrder("c"), "$id.png", null))
        }
        dao.insertCollectionMedia(CollectionMediaEntity("d", "shared", 0, "shared.png", null))
    }

    @Test fun collectionAssetsIncludeSharedAndPendingAndCannotDeleteWhileAnyReferenceRemains() = runBlocking {
        seed()
        assertEquals(setOf("shared", "pending"), repository.getCollectionAssets("c").map { it.id }.toSet())
        assertTrue(repository.getCollectionAssets("missing").isEmpty())
        assertFalse(repository.deleteCollectionIfEmpty("c"))
        assertNotNull(database.galleryDao().getCollection("c"))
        assertEquals(2, repository.referenceCount("shared"))
    }

    @Test fun deletingCopiesGloballyClearsOtherReferencesAndCoversButKeepsOtherCollections() = runBlocking {
        seed()
        assertTrue(repository.setArtistCover("b", "shared"))
        repository.getCollectionAssets("c").forEach { asset ->
            if (asset.state == "READY") assertNotNull(repository.markAssetForDelete(asset.id))
            repository.completeAssetDelete(asset.id)
        }
        assertTrue(repository.deleteCollectionIfEmpty("c"))
        val dao = database.galleryDao()
        assertNull(dao.getCollection("c"))
        assertNotNull(dao.getArtist("a"))
        assertNotNull(dao.getCollection("d"))
        assertNull(dao.getArtist("b")!!.coverAssetId)
        assertEquals(0, repository.referenceCount("shared"))
        assertNull(repository.getAsset("shared"))
        assertFalse(repository.deleteCollectionIfEmpty("c"))
    }

    @Test fun collectionDeleteStorageFailureIsReportedAndRetainsCollection() = runBlocking {
        val dao = database.galleryDao()
        dao.insertArtist(ArtistEntity("a", "画师", 1, 1))
        dao.insertCollection(CollectionEntity("c", "a", "图集", false, 1, 1))
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_collection_delete BEFORE DELETE ON WorkCollection BEGIN SELECT RAISE(ABORT, 'failure'); END")
        try {
            repository.deleteCollectionIfEmpty("c")
            fail("写入失败不能报告成功")
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            assertNotNull(dao.getCollection("c"))
        }
    }
}
