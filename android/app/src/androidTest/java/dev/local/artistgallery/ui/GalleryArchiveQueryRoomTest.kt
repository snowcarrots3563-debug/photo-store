package dev.local.artistgallery.ui

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.artistgallery.data.*
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic in-memory metadata only. VM setter tests do not exercise Compose gestures or an archive job. */
@RunWith(AndroidJUnit4::class)
class GalleryArchiveQueryRoomTest {
    private val queryExecutor = Executors.newSingleThreadExecutor()
    private val transactionExecutor = Executors.newSingleThreadExecutor()
    private val queries = listOf("home", "artist", "collections", "collection", "artistMedia", "collectionMedia", "unassigned")
        .associateWith { AtomicInteger() }
    private val viewModels = ViewModelStore()
    private lateinit var database: GalleryDatabase
    private lateinit var model: GalleryViewModel

    @Before fun open() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java)
            .setQueryExecutor(queryExecutor)
            .setTransactionExecutor(transactionExecutor)
            .setQueryCallback({ sql, _ -> queryKind(sql)?.let { queries.getValue(it).incrementAndGet() } }, Executor { it.run() })
            .build()
    }

    @After fun close() = runBlocking {
        try {
            withTimeout(10_000) {
                withContext(Dispatchers.Main) {
                    viewModels.clear()
                    if (::model.isInitialized) model.viewModelScope.coroutineContext[Job]?.join()
                }
            }
        } finally {
            if (::database.isInitialized) database.close()
            queryExecutor.shutdownNow()
            transactionExecutor.shutdownNow()
        }
    }

    @Test fun thousandWritesCannotQueryCoveredArtistListsAndResumeReadsLatest() = runBlocking {
        verifyPause(GalleryRoute.Artist("a"), listOf("home", "artist", "collections", "artistMedia"))
    }

    @Test fun thousandWritesCannotQueryCoveredCollectionListsAndResumeReadsLatest() = runBlocking {
        verifyPause(GalleryRoute.Collection("c", "a"), listOf("home", "artist", "collections", "collection", "collectionMedia"))
    }

    @Test fun thousandWritesCannotQueryCoveredUnassignedListAndResumeReadsLatest() = runBlocking {
        verifyPause(GalleryRoute.Unassigned, listOf("home", "unassigned"))
    }

    private suspend fun verifyPause(route: GalleryRoute, activeQueries: List<String>) {
        val dao = database.galleryDao()
        database.withTransaction {
            dao.insertArtist(ArtistEntity("a", "合成画师", 1, 1))
            dao.insertCollection(CollectionEntity("c", "a", "合成作品集", false, 1, 1))
            insertMedia(0, route != GalleryRoute.Unassigned)
        }
        withContext(Dispatchers.Main) {
            val repository = RoomGalleryRepository(database)
            model = GalleryViewModel(repository, SavedStateHandle(), repository)
            viewModels.put("archive-query", model)
            model.onEvent(when (route) {
                is GalleryRoute.Artist -> GalleryEvent.OpenArtist(route.id)
                is GalleryRoute.Collection -> GalleryEvent.OpenCollection(route.id, route.artistId)
                else -> GalleryEvent.OpenUnassigned
            })
        }
        withTimeout(10_000) {
            model.state.first { it.route == route && it.loadState == LoadState.Ready && it.mediaLoadState == LoadState.Ready && it.mediaItems.size == 1 }
        }
        drainQueries()
        activeQueries.forEach { assertTrue("Expected initial $it SQL", queries.getValue(it).get() > 0) }
        val loaded = model.state.value
        withContext(Dispatchers.Main) { model.setGalleryQueriesPaused(true) }
        drainQueries()
        val pausedQueries = snapshot()
        repeat(1000) { index -> database.withTransaction { insertMedia(index + 1, route != GalleryRoute.Unassigned) } }
        drainQueries()
        assertEquals("Covered cold subscriptions must execute no further list SQL", pausedQueries, snapshot())
        assertSame(loaded.artists, model.state.value.artists)
        assertSame(loaded.artistDetail, model.state.value.artistDetail)
        assertSame(loaded.mediaItems, model.state.value.mediaItems)
        withContext(Dispatchers.Main) {
            model.setGalleryQueriesPaused(false)
            assertEquals(LoadState.Ready, model.state.value.loadState)
            assertEquals(LoadState.Ready, model.state.value.mediaLoadState)
            assertSame(loaded.mediaItems, model.state.value.mediaItems)
        }
        withTimeout(10_000) {
            model.state.first { current ->
                current.route == route && current.loadState == LoadState.Ready && current.mediaLoadState == LoadState.Ready &&
                    current.mediaItems.size == 1001 && (route == GalleryRoute.Unassigned ||
                    current.artistDetail?.artist?.imageCount == 1001 && current.artists.singleOrNull()?.imageCount == 1001)
            }
        }
        drainQueries()
        activeQueries.forEach { assertTrue("Resume must query latest $it data", queries.getValue(it).get() > pausedQueries.getValue(it)) }
        assertEquals(1001, model.state.value.mediaItems.size)
    }

    private suspend fun insertMedia(index: Int, referenced: Boolean) {
        val dao = database.galleryDao()
        dao.insertAsset(MediaAssetEntity("asset-$index", "$index.png", "IMAGE", 8, null, null,
            "synthetic-hash-$index", UUID.randomUUID().toString(), "READY", index.toLong() + 1))
        if (referenced) dao.insertCollectionMedia(CollectionMediaEntity("c", "asset-$index", index, "$index.png", null))
    }

    private fun snapshot() = queries.mapValues { it.value.get() }

    private suspend fun drainQueries() {
        // Drain cancellation and callbacks before measuring; do not assume one SQL per invalidation.
        withContext(Dispatchers.Main) { yield() }
        withContext(Dispatchers.IO) {
            repeat(2) {
                transactionExecutor.submit {}.get(10, TimeUnit.SECONDS)
                queryExecutor.submit {}.get(10, TimeUnit.SECONDS)
            }
        }
        withContext(Dispatchers.Main) { yield() }
    }

    private fun queryKind(raw: String): String? {
        val sql = raw.trim().replace(Regex("\\s+"), " ")
        return when {
            sql.startsWith("SELECT Artist.*") && sql.endsWith("FROM Artist ORDER BY createdAt ASC, id ASC") -> "home"
            sql.startsWith("SELECT Artist.*") && sql.endsWith("FROM Artist WHERE id = ?") -> "artist"
            sql.startsWith("SELECT WorkCollection.*") && sql.endsWith("FROM WorkCollection WHERE artistId = ? ORDER BY createdAt ASC, id ASC") -> "collections"
            sql.startsWith("SELECT WorkCollection.*") && sql.endsWith("FROM WorkCollection WHERE id = ?") -> "collection"
            sql.startsWith("SELECT ma.*") && sql.contains("WHERE wc.artistId = ?") && sql.contains("GROUP BY ma.id") -> "artistMedia"
            sql.startsWith("SELECT ma.*") && sql.contains("WHERE cm.collectionId = ? AND ma.state = 'READY'") -> "collectionMedia"
            sql.startsWith("SELECT ma.*") && sql.contains("LEFT JOIN CollectionMedia cm") && sql.contains("cm.assetId IS NULL") -> "unassigned"
            else -> null
        }
    }
}
