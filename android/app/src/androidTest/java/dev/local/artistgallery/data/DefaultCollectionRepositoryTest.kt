package dev.local.artistgallery.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.artistgallery.model.SaveResult
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Neutral in-memory records only; no original files or ordinary gallery. */
@RunWith(AndroidJUnit4::class)
class DefaultCollectionRepositoryTest {
    private lateinit var db: GalleryDatabase
    private lateinit var repository: RoomGalleryRepository
    @Before fun open() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        repository = RoomGalleryRepository(db)
    }
    @After fun close() { db.close() }
    private fun id(result: SaveResult) = (result as SaveResult.Saved).id

    @Test fun newArtistAndDefaultAreAtomicAndUseSeparateIds() = runBlocking {
        val artist = id(repository.createArtist("新画师"))
        val group = db.galleryDao().getCollections(artist).single()
        assertTrue(group.isDefault)
        assertFalse(group.isTemporary)
        assertEquals("默认图集", group.name)
        assertNotEquals(artist, group.id)
        assertEquals(1, repository.observeArtist(artist).first()!!.artist.collectionCount)
        assertTrue(repository.observeCollection(group.id).first()!!.isDefault)
    }

    @Test fun failingDefaultInsertRollsBackTheArtist() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_default BEFORE INSERT ON WorkCollection WHEN NEW.isDefault = 1 BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        assertEquals(SaveResult.StorageFailure, repository.createArtist("事务回滚"))
        assertTrue(db.galleryDao().allArtists().isEmpty())
        assertTrue(db.galleryDao().allCollections().isEmpty())
    }

    @Test fun concurrentOldArtistEnsureCreatesOnceWithoutChangingSameNamedOrdinaryGroups() = runBlocking {
        val dao = db.galleryDao()
        dao.insertArtist(ArtistEntity("old", "旧画师", 1, 1))
        val ordinary = listOf(CollectionEntity("one", "old", "默认图集", false, 1, 1),
            CollectionEntity("two", "old", "默认图集（2）", false, 2, 2))
        ordinary.forEach { dao.insertCollection(it) }
        val results = (1..8).map { async(Dispatchers.IO) { id(repository.ensureDefaultCollection("old")) } }.awaitAll()
        assertEquals(1, results.distinct().size)
        assertEquals(ordinary, dao.getCollections("old").filterNot { it.isDefault })
        assertEquals("默认图集（3）", dao.getCollection(results.first())!!.name)
    }

    @Test fun renamedDefaultIsReusedAndMissingArtistCannotBeRecreated() = runBlocking {
        val artist = id(repository.createArtist("画师"))
        val group = db.galleryDao().getCollections(artist).single()
        assertEquals(SaveResult.Saved(group.id), repository.renameCollection(group.id, "常用收藏"))
        assertEquals(SaveResult.Saved(group.id), repository.ensureDefaultCollection(artist))
        assertTrue(db.galleryDao().getCollection(group.id)!!.isDefault)
        assertEquals("常用收藏", db.galleryDao().getCollection(group.id)!!.name)
        assertEquals(SaveResult.NotFound, repository.ensureDefaultCollection("missing"))
        assertNull(db.galleryDao().getArtist("missing"))
    }

    @Test fun defaultCannotSupplyAssetsForGroupDeletionOrBeDeletedDirectly() = runBlocking {
        val artist = id(repository.createArtist("受保护"))
        val group = db.galleryDao().getCollections(artist).single()
        assertThrows(IOException::class.java) { runBlocking { repository.getCollectionAssets(group.id) } }
        assertFalse(repository.deleteCollectionIfEmpty(group.id))
        assertEquals(0, db.galleryDao().deleteCollection(group.id))
        assertEquals(group, db.galleryDao().getCollection(group.id))
    }

    @Test fun completingSharedPendingDeletionRetainsDefaultAndCleansOrdinaryGroup() = runBlocking {
        val artist = id(repository.createArtist("共享"))
        val group = db.galleryDao().getCollections(artist).single()
        val ordinary = id(repository.createCollection(artist, "普通"))
        seedAsset("shared", group.id, ordinary)
        repository.completeAssetDelete("shared")
        assertNotNull(db.galleryDao().getCollection(group.id))
        assertNull(db.galleryDao().getCollection(ordinary))
        assertNull(db.galleryDao().getAsset("shared"))
        assertTrue(db.galleryDao().allReferences().isEmpty())
    }

    @Test fun batchDeletionRetainsDefaultAndFailedAssetsKeepTheirGroup() = runBlocking {
        val artist = id(repository.createArtist("批次"))
        val group = db.galleryDao().getCollections(artist).single()
        val ordinary = id(repository.createCollection(artist, "失败保留"))
        seedAsset("deleted", group.id)
        seedAsset("failed", ordinary)
        repository.finishAssetDeletes(listOf("deleted"), listOf("failed"))
        assertNotNull(db.galleryDao().getCollection(group.id))
        assertNotNull(db.galleryDao().getCollection(ordinary))
        assertEquals("READY", db.galleryDao().getAsset("failed")!!.state)
        assertNull(db.galleryDao().getAsset("deleted"))
    }

    @Test fun deletingWholeArtistCanRemoveItsEmptyDefault() = runBlocking {
        val artist = id(repository.createArtist("删除整个画师"))
        assertTrue(repository.deleteArtistIfEmpty(artist))
        assertNull(db.galleryDao().getArtist(artist))
        assertTrue(db.galleryDao().allCollections().isEmpty())
    }

    private suspend fun seedAsset(id: String, vararg groups: String) {
        val dao = db.galleryDao()
        dao.insertAsset(MediaAssetEntity(id, "$id.png", "IMAGE", 6000, null, null, "hash-$id", "key-$id", "PENDING_DELETE", 1))
        groups.forEach { dao.insertCollectionMedia(CollectionMediaEntity(it, id, 0, "$id.png", null)) }
    }
}
