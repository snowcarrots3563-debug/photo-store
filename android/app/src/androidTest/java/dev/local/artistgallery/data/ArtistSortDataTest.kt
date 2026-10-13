package dev.local.artistgallery.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.artistgallery.data.exporting.GallerySnapshotReader
import dev.local.artistgallery.model.MediaSort
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtistSortDataTest {
    @Test fun summariesUseAssetAddedTimeAndKeepExistingReferenceNameAndDefaultOrder() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val dao = database.galleryDao()
            dao.insertArtist(ArtistEntity("artist", "画师", 900, 900))
            dao.insertCollection(CollectionEntity("first", "artist", "图集", false, 900, 900))
            dao.insertCollection(CollectionEntity("second", "artist", "图集2", false, 900, 900))
            dao.insertAsset(MediaAssetEntity("late", "late.png", "IMAGE", 9, null, null, "hash-late", "late-key", "READY", 200))
            dao.insertAsset(MediaAssetEntity("early", "original.png", "IMAGE", 5, null, null, "hash-early", "early-key", "READY", 100))
            dao.insertAsset(MediaAssetEntity("unassigned", "loose.png", "IMAGE", 3, null, null, "hash-loose", "loose-key", "READY", 50))
            dao.insertCollectionMedia(CollectionMediaEntity("first", "late", 0, "10.png", null))
            dao.insertCollectionMedia(CollectionMediaEntity("first", "early", 1, "2.png", null))
            dao.insertCollectionMedia(CollectionMediaEntity("second", "early", 0, "1.png", null))
            val repository = RoomGalleryRepository(database)
            val artist = repository.observeArtistImages("artist").first()
            assertEquals(listOf("early", "late"), artist.map { it.id })
            assertEquals(listOf(100L, 200L), artist.map { it.createdAt })
            assertEquals(listOf("1.png", "10.png"), artist.map { it.name })
            val collection = repository.observeCollectionMedia("first").first()
            assertEquals(listOf("late", "early"), collection.map { it.id })
            assertEquals(listOf(200L, 100L), collection.map { it.createdAt })
            assertEquals(50L, repository.observeUnassignedMedia().first().single().createdAt)
        } finally { database.close() }
    }

    @Test fun backupSnapshotKeepsExistingSortAndFiltersDeletedArtistMapping() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val artist = UUID.randomUUID().toString()
            val deleted = UUID.randomUUID().toString()
            database.galleryDao().insertArtist(ArtistEntity(artist, "画师", 1, 1))
            val preferences = object : BrowsePreferences {
                override val options = MutableStateFlow(BrowseOptions(artistImageSorts =
                    mapOf(artist to MediaSort.SizeDescending, deleted to MediaSort.NameAscending)))
                override fun save(options: BrowseOptions) { this.options.value = options }
            }
            val snapshot = GallerySnapshotReader(database, preferences).read()
            assertEquals(mapOf(artist to MediaSort.SizeDescending), snapshot.manifest.settings.artistImageSorts)
            assertEquals(2, preferences.options.value.artistImageSorts.size)
        } finally { database.close() }
    }

    @Test fun backupSnapshotKeepsLiveCollectionSortAndFiltersOrphanMappingWithoutChangingPreferences() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val artist = UUID.randomUUID().toString()
            val collection = UUID.randomUUID().toString()
            val orphan = UUID.randomUUID().toString()
            database.galleryDao().insertArtist(ArtistEntity(artist, "画师", 1, 1))
            database.galleryDao().insertCollection(CollectionEntity(collection, artist, "图集", false, 1, 1))
            val preferences = object : BrowsePreferences {
                override val options = MutableStateFlow(BrowseOptions(artistImageSorts = mapOf(artist to MediaSort.NameAscending),
                    collectionMediaSorts = mapOf(collection to MediaSort.SizeDescending, orphan to MediaSort.AddedAscending)))
                override fun save(options: BrowseOptions) { this.options.value = options }
            }
            val snapshot = GallerySnapshotReader(database, preferences).read()
            assertEquals(mapOf(collection to MediaSort.SizeDescending), snapshot.manifest.settings.collectionMediaSorts)
            assertEquals(mapOf(artist to MediaSort.NameAscending), snapshot.manifest.settings.artistImageSorts)
            assertEquals(2, preferences.options.value.collectionMediaSorts.size)
        } finally { database.close() }
    }
}
