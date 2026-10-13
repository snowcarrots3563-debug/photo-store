package dev.local.artistgallery.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GalleryControlsRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GalleryDatabase::class.java)

    @Test fun migrationKeepsExistingArtistAndStartsWithAutomaticCover() {
        val name = "controls-migration-${UUID.randomUUID()}.db"
        try {
            migration.createDatabase(name, 3).use { it.execSQL("INSERT INTO Artist VALUES ('a', '保留画师', 1, 1)") }
            migration.runMigrationsAndValidate(name, 4, true, GalleryDatabase.MIGRATION_3_4).use { db ->
                db.query("SELECT name, coverAssetId FROM Artist WHERE id = 'a'").use {
                    assertTrue(it.moveToFirst()); assertEquals("保留画师", it.getString(0)); assertTrue(it.isNull(1))
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun selectedCoverRejectsForeignMediaAndSharedDeletionClearsAllReferences() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val dao = db.galleryDao()
            val repository = RoomGalleryRepository(db)
            dao.insertArtist(ArtistEntity("a", "画师甲", 1, 1))
            dao.insertArtist(ArtistEntity("b", "画师乙", 2, 2))
            dao.insertCollection(CollectionEntity("c", "a", "甲作品", false, 1, 1))
            dao.insertCollection(CollectionEntity("d", "b", "乙作品", false, 2, 2))
            fun asset(id: String) = MediaAssetEntity(id, "$id.png", "IMAGE", 8000, null, null, id, "key-$id", "READY", 1)
            dao.insertAsset(asset("first")); dao.insertAsset(asset("shared")); dao.insertAsset(asset("foreign"))
            dao.insertCollectionMedia(CollectionMediaEntity("c", "first", 0, "first.png", null))
            dao.insertCollectionMedia(CollectionMediaEntity("c", "shared", 1, "shared.png", null))
            dao.insertCollectionMedia(CollectionMediaEntity("d", "shared", 0, "shared.png", null))
            dao.insertCollectionMedia(CollectionMediaEntity("d", "foreign", 1, "foreign.png", null))
            assertFalse(repository.setArtistCover("a", "foreign"))
            assertTrue(repository.setArtistCover("a", "shared"))
            assertTrue(repository.setArtistCover("b", "shared"))
            assertEquals("key-shared", dao.observeArtist("a").first()!!.coverStorageKey)
            assertNotNull(repository.markAssetForDelete("shared"))
            assertEquals(2, repository.referenceCount("shared"))
            assertEquals("key-first", dao.observeArtist("a").first()!!.coverStorageKey)
            repository.restoreAssetAfterDeleteFailure("shared")
            assertEquals("key-shared", dao.observeArtist("a").first()!!.coverStorageKey)
            assertNotNull(repository.markAssetForDelete("shared"))
            repository.completeAssetDelete("shared")
            assertEquals(0, repository.referenceCount("shared"))
            assertNull(repository.getAsset("shared"))
            assertNull(dao.getArtist("a")!!.coverAssetId)
            assertNull(dao.getArtist("b")!!.coverAssetId)
            assertEquals("key-first", dao.observeArtist("a").first()!!.coverStorageKey)
            assertEquals("key-foreign", dao.observeArtist("b").first()!!.coverStorageKey)
        } finally { db.close() }
    }
}
