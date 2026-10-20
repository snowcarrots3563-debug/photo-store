package dev.local.artistgallery.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.artistgallery.model.ArtistSummary
import dev.local.artistgallery.model.CoverRef
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtistSummaryProjectionTest {
    private lateinit var database: GalleryDatabase
    private lateinit var repository: RoomGalleryRepository
    private val queries = CopyOnWriteArrayList<String>()

    @Before fun open() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java)
            .setQueryCallback({ sql, _ -> queries.add(sql) }, Executor { it.run() })
            .build()
        repository = RoomGalleryRepository(database)
    }

    @After fun close() { database.close() }

    private suspend fun artist(id: String, createdAt: Long = 1) {
        database.galleryDao().insertArtist(ArtistEntity(id, "画师$id", createdAt, createdAt))
    }

    private suspend fun collection(id: String, artistId: String, createdAt: Long = 1, isDefault: Boolean = false) {
        database.galleryDao().insertCollection(CollectionEntity(id, artistId, "图集$id", false, createdAt, createdAt, isDefault))
    }

    private suspend fun asset(id: String, type: String = "IMAGE", state: String = "READY") {
        database.galleryDao().insertAsset(MediaAssetEntity(id, "$id.png", type, 8, null, null, id, "key-$id", state, 1))
    }

    private suspend fun link(collectionId: String, assetId: String, order: Int = 0) {
        database.galleryDao().insertCollectionMedia(CollectionMediaEntity(collectionId, assetId, order, "$assetId.png", null))
    }

    private suspend fun summaries(id: String): List<ArtistSummary> = withTimeout(5_000) {
        listOf(repository.observeArtists().first().single { it.id == id }, repository.observeArtist(id).first()!!.artist)
    }

    @Test fun countsDistinctReadyImagesGifsAndVideosAcrossSharedReferences() = runBlocking {
        artist("a"); artist("b")
        collection("a1", "a"); collection("a2", "a"); collection("b1", "b")
        asset("image"); asset("gif", "GIF"); asset("video", "VIDEO")
        asset("pending-image", state = "PENDING_DELETE")
        asset("staged-image", state = "STAGED")
        asset("failed-gif", "GIF", "FAILED")
        asset("pending-video", "VIDEO", "PENDING_DELETE")
        asset("foreign"); asset("unassigned")
        for (id in listOf("image", "gif", "video")) {
            link("a1", id); link("a2", id); link("b1", id)
        }
        for (id in listOf("pending-image", "staged-image", "failed-gif", "pending-video")) link("a1", id)
        link("b1", "foreign")

        for (summary in summaries("a")) {
            assertEquals(2, summary.collectionCount)
            assertEquals(2, summary.imageCount)
            assertEquals(1, summary.videoCount)
        }
        for (summary in summaries("b")) {
            assertEquals(1, summary.collectionCount)
            assertEquals(3, summary.imageCount)
            assertEquals(1, summary.videoCount)
        }
    }

    @Test fun countsDefaultAndOrdinaryEmptyCollectionsAndKeepsEmptyArtists() = runBlocking {
        artist("a"); artist("empty")
        collection("default", "a", isDefault = true)
        collection("ordinary", "a")
        for (summary in summaries("a")) {
            assertEquals(2, summary.collectionCount)
            assertEquals(0, summary.imageCount)
            assertEquals(0, summary.videoCount)
            assertEquals(CoverRef.None, summary.cover)
        }
        for (summary in summaries("empty")) {
            assertEquals(0, summary.collectionCount)
            assertEquals(CoverRef.None, summary.cover)
        }
        assertNull(withTimeout(5_000) { database.galleryDao().observeArtist("missing").first() })
    }

    @Test fun customCoverRequiresLinkedReadyImageOrGifAndFallbackKeepsExistingOrder() = runBlocking {
        artist("a"); artist("b")
        collection("early", "a", createdAt = 10)
        collection("late", "a", createdAt = 20)
        collection("foreign", "b")
        asset("a"); asset("b"); asset("late", "GIF"); asset("foreign")
        asset("pending", state = "PENDING_DELETE"); asset("video", "VIDEO")
        link("early", "video", -2); link("early", "pending", -1)
        link("early", "b", 5); link("early", "a", 5)
        link("late", "late", 0); link("foreign", "foreign", 0)
        assertTrue(summaries("a").all { it.cover == CoverRef.Asset("key-a") })

        val dao = database.galleryDao()
        for ((coverId, expected) in listOf(
            "late" to "key-late",
            "b" to "key-b",
            "foreign" to "key-a",
            "video" to "key-a",
            "pending" to "key-a",
            "missing" to "key-a",
            null to "key-a",
        )) {
            dao.updateArtist(dao.getArtist("a")!!.copy(coverAssetId = coverId))
            for (summary in summaries("a")) assertEquals("cover=$coverId", CoverRef.Asset(expected), summary.cover)
        }
        dao.updateAsset(dao.getAsset("a")!!.copy(state = "PENDING_DELETE"))
        assertTrue(summaries("a").all { it.cover == CoverRef.Asset("key-b") })
    }

    @Test fun artistOrderingRemainsCreationTimeThenId() = runBlocking {
        artist("b", createdAt = 2)
        artist("a", createdAt = 2)
        artist("z", createdAt = 1)
        assertEquals(listOf("z", "a", "b"), withTimeout(5_000) { repository.observeArtists().first() }.map { it.id })
    }

    @Test fun homeAndSingleArtistSummaryDoNotFetchFullCollectionRelations() = runBlocking {
        artist("a"); collection("c", "a")
        for (singleArtist in listOf(false, true)) {
            queries.clear()
            withTimeout(5_000) {
                if (singleArtist) database.galleryDao().observeArtist("a").first()
                else database.galleryDao().observeArtists().first()
            }
            val normalized = queries.map { it.replace("`", "").trim().replace(Regex("\\s+"), " ").lowercase() }
            assertTrue("Must observe the artist summary query", normalized.any { it.startsWith("select artist.*,") })
            assertFalse("Summary reads must not materialize full WorkCollection relations", normalized.any {
                it.startsWith("select ") && it.contains(" from workcollection where artistid in (")
            })
        }
    }
}
