package dev.local.artistgallery.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.artistgallery.data.exporting.GallerySnapshotReader
import dev.local.artistgallery.model.MediaSort
import dev.local.artistgallery.model.CoverRef
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtistSortDataTest {
    @Test fun artistAggregationDeduplicatesReadyVideosAndKeepsImageCoverAndCountsSeparate() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val dao = database.galleryDao()
            dao.insertArtist(ArtistEntity("artist", "画师", 1, 1))
            dao.insertArtist(ArtistEntity("other", "其他画师", 2, 2))
            dao.insertCollection(CollectionEntity("first", "artist", "第一册", false, 1, 1))
            dao.insertCollection(CollectionEntity("second", "artist", "第二册", false, 2, 2))
            dao.insertCollection(CollectionEntity("foreign", "other", "其他册", false, 3, 3))
            val assets = listOf(
                MediaAssetEntity("video", "movie.mp4", "VIDEO", 90, null, null, "video-hash", "video-key", "READY", 30),
                MediaAssetEntity("image", "image.png", "IMAGE", 20, null, null, "image-hash", "image-key", "READY", 10),
                MediaAssetEntity("gif", "moving.gif", "GIF", 30, null, null, "gif-hash", "gif-key", "READY", 20),
                MediaAssetEntity("foreign-video", "foreign.mp4", "VIDEO", 80, null, null, "foreign-hash", "foreign-key", "READY", 40),
                MediaAssetEntity("loose-video", "loose.mp4", "VIDEO", 80, null, null, "loose-hash", "loose-key", "READY", 50),
            )
            assets.forEach { dao.insertAsset(it) }
            dao.insertCollectionMedia(CollectionMediaEntity("first", "video", 0, "10.mp4", null))
            dao.insertCollectionMedia(CollectionMediaEntity("second", "video", 0, "2.mp4", null))
            dao.insertCollectionMedia(CollectionMediaEntity("foreign", "video", 0, "shared.mp4", null))
            dao.insertCollectionMedia(CollectionMediaEntity("first", "image", 1, "image.png", null))
            dao.insertCollectionMedia(CollectionMediaEntity("first", "gif", 2, "moving.gif", null))
            dao.insertCollectionMedia(CollectionMediaEntity("foreign", "foreign-video", 1, "foreign.mp4", null))
            listOf("STAGED", "FINALIZED", "DELETING").forEachIndexed { index, state ->
                val id = "pending-$index"
                dao.insertAsset(MediaAssetEntity(id, "$id.mp4", "VIDEO", 50, null, null, "$id-hash", "$id-key", state, 60L + index))
                dao.insertCollectionMedia(CollectionMediaEntity("first", id, 3 + index, "$id.mp4", null))
            }
            val repository = RoomGalleryRepository(database)
            val media = repository.observeArtistImages("artist").first()
            assertEquals(listOf("image", "gif", "video"), media.map { it.id })
            assertEquals("10.mp4", media.single { it.id == "video" }.name)
            assertEquals(30L, media.single { it.id == "video" }.createdAt)
            val detail = requireNotNull(repository.observeArtist("artist").first())
            assertEquals(2, detail.artist.imageCount)
            assertEquals(1, detail.artist.videoCount)
            assertEquals(CoverRef.Asset("image-key"), detail.artist.cover)
            val home = repository.observeArtists().first().associateBy { it.id }
            assertEquals(detail.artist, home.getValue("artist"))
            assertEquals(2, home.getValue("other").videoCount)
            assertEquals(0, home.getValue("other").imageCount)
        } finally { database.close() }
    }

    @Test fun videoOnlyArtistHasNoImageCoverAndCannotSaveVideoAsCover() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val dao = database.galleryDao()
            dao.insertArtist(ArtistEntity("artist", "视频画师", 1, 1))
            dao.insertCollection(CollectionEntity("collection", "artist", "视频册", false, 1, 1))
            dao.insertAsset(MediaAssetEntity("video", "movie.mp4", "VIDEO", 90, null, null, "video-hash", "video-key", "READY", 30))
            dao.insertCollectionMedia(CollectionMediaEntity("collection", "video", 0, "movie.mp4", null))
            val repository = RoomGalleryRepository(database)
            val artist = requireNotNull(repository.observeArtist("artist").first()).artist
            assertEquals(0, artist.imageCount)
            assertEquals(1, artist.videoCount)
            assertEquals(CoverRef.None, artist.cover)
            assertFalse(repository.setArtistCover("artist", "video"))
            assertEquals(CoverRef.None, repository.observeArtists().first().single().cover)
            assertEquals(listOf("video"), repository.observeArtistImages("artist").first().map { it.id })
        } finally { database.close() }
    }

    @Test fun sharedVideoBecomingUnavailableUpdatesAllArtistAggregatesWithoutChangingImageCover() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val dao = database.galleryDao()
            val video = MediaAssetEntity("video", "movie.mp4", "VIDEO", 90, null, null, "video-hash", "video-key", "READY", 30)
            dao.insertAsset(video)
            listOf("a", "b").forEach { id ->
                dao.insertArtist(ArtistEntity(id, id, 1, 1))
                dao.insertCollection(CollectionEntity("$id-collection", id, id, false, 1, 1))
                dao.insertCollectionMedia(CollectionMediaEntity("$id-collection", "video", 0, "movie.mp4", null))
            }
            val repository = RoomGalleryRepository(database)
            assertTrue(repository.observeArtists().first().all { it.videoCount == 1 })
            dao.updateAsset(video.copy(state = "DELETING"))
            assertTrue(repository.observeArtists().first().all { it.videoCount == 0 && it.cover == CoverRef.None })
            listOf("a", "b").forEach { id ->
                assertTrue(repository.observeArtistImages(id).first().isEmpty())
                assertEquals(0, repository.observeArtist(id).first()?.artist?.videoCount)
            }
        } finally { database.close() }
    }

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
