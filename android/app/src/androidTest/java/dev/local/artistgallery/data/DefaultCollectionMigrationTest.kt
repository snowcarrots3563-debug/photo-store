package dev.local.artistgallery.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DefaultCollectionMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GalleryDatabase::class.java)

    @Test fun versionsOneToFiveGainFalseFlagWithoutBuildingOrRenamingCollections() {
        for (version in 1..5) {
            val name = "default-migration-$version-${UUID.randomUUID()}.db"
            try {
                migration.createDatabase(name, version).use { db ->
                    db.execSQL("INSERT INTO Artist(id,name,createdAt,updatedAt) VALUES('a','旧画师',1,2)")
                    db.execSQL("INSERT INTO WorkCollection(id,artistId,name,isTemporary,createdAt,updatedAt) VALUES('c','a','默认图集',0,3,4)")
                    if (version >= 2) {
                        db.execSQL("INSERT INTO MediaAsset VALUES('m','原图.png','IMAGE',6000,NULL,NULL,'hash','key','READY',5)")
                        db.execSQL("INSERT INTO CollectionMedia VALUES('c','m',7,'原图.png','目录/原图.png')")
                    }
                    if (version >= 4) db.execSQL("UPDATE Artist SET coverAssetId='m'")
                }
                migration.runMigrationsAndValidate(name, 6, true, GalleryDatabase.MIGRATION_1_2,
                    GalleryDatabase.MIGRATION_2_3, GalleryDatabase.MIGRATION_3_4, GalleryDatabase.MIGRATION_4_5,
                    GalleryDatabase.MIGRATION_5_6).use { db ->
                    db.query("SELECT id,artistId,name,isTemporary,createdAt,updatedAt,isDefault FROM WorkCollection").use {
                        assertTrue(it.moveToFirst()); assertEquals("c", it.getString(0)); assertEquals("a", it.getString(1))
                        assertEquals("默认图集", it.getString(2)); assertEquals(0, it.getInt(3)); assertEquals(3L, it.getLong(4))
                        assertEquals(4L, it.getLong(5)); assertEquals(0, it.getInt(6)); assertFalse(it.moveToNext())
                    }
                    db.query("SELECT manualOrder FROM CollectionMedia").use {
                        if (version >= 2) { assertTrue(it.moveToFirst()); assertEquals(7, it.getInt(0)) } else assertFalse(it.moveToFirst())
                    }
                    db.query("SELECT coverAssetId FROM Artist").use { assertTrue(it.moveToFirst()); if (version >= 4) assertEquals("m", it.getString(0)) else assertTrue(it.isNull(0)) }
                    db.query("SELECT COUNT(*) FROM pragma_index_list('CollectionMedia') WHERE name='index_CollectionMedia_collectionId_manualOrder_assetId'").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
                    db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                }
            } finally { context.deleteDatabase(name) }
        }
    }

    @Test fun freshDatabaseHasFalseColumnDefaultAndExplicitMigrationRegistered() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            assertEquals(6, db.openHelper.writableDatabase.version)
            db.galleryDao().insertArtist(ArtistEntity("old", "普通插入", 1, 1))
            db.openHelper.writableDatabase.execSQL("INSERT INTO WorkCollection(id,artistId,name,isTemporary,createdAt,updatedAt) VALUES('c','old','普通',0,1,1)")
            assertFalse(db.galleryDao().getCollection("c")!!.isDefault)
            assertEquals(5, GalleryDatabase.MIGRATION_5_6.startVersion)
            assertEquals(6, GalleryDatabase.MIGRATION_5_6.endVersion)
        } finally { db.close() }
    }
}
