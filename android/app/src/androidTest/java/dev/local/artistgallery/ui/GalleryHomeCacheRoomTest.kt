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
import dev.local.artistgallery.model.CoverRef
import dev.local.artistgallery.model.SaveResult
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryHomeCacheRoomTest {
    private val homeQueries = AtomicInteger()
    private val viewModels = ViewModelStore()
    private lateinit var database: GalleryDatabase
    private lateinit var repository: RoomGalleryRepository
    private lateinit var model: GalleryViewModel

    @Before fun open() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java)
            .setQueryCallback({ sql, _ ->
                if (sql.trim().endsWith("FROM Artist ORDER BY createdAt ASC, id ASC")) homeQueries.incrementAndGet()
            }, Executor { it.run() })
            .build()
        repository = RoomGalleryRepository(database)
    }

    @After fun close() = runBlocking {
        withTimeout(5_000) {
            withContext(Dispatchers.Main) {
                viewModels.clear()
                if (::model.isInitialized) model.viewModelScope.coroutineContext[Job]?.join()
            }
        }
        if (::database.isInitialized) database.close()
    }

    private suspend fun seed() {
        database.withTransaction {
            val dao = database.galleryDao()
            dao.insertArtist(ArtistEntity("a", "合成画师", 1, 1))
            dao.insertCollection(CollectionEntity("c", "a", "合成作品集", false, 1, 1))
        }
    }

    private suspend fun start() {
        withContext(Dispatchers.Main) {
            model = GalleryViewModel(repository, SavedStateHandle(), repository)
            viewModels.put("home-cache", model)
        }
        withTimeout(5_000) { model.state.first { it.loadState == LoadState.Ready && it.route == GalleryRoute.Home } }
    }

    private suspend fun awaitReady(route: GalleryRoute) {
        withTimeout(5_000) {
            model.state.first { it.route == route && it.loadState == LoadState.Ready && it.mediaLoadState == LoadState.Ready }
        }
    }

    @Test fun navigationWithoutWritesRetainsListAndDoesNotQueryHomeAgain() = runBlocking {
        seed()
        start()
        val loaded = model.state.value.artists
        val initialQueries = homeQueries.get()
        assertEquals(1, initialQueries)
        repeat(3) {
            withContext(Dispatchers.Main) { model.onEvent(GalleryEvent.OpenArtist("a")) }
            awaitReady(GalleryRoute.Artist("a"))
            withContext(Dispatchers.Main) { model.onEvent(GalleryEvent.OpenCollection("c", "a")) }
            awaitReady(GalleryRoute.Collection("c", "a"))
            withContext(Dispatchers.Main) { model.onEvent(GalleryEvent.NavigateBack) }
            awaitReady(GalleryRoute.Artist("a"))
            withContext(Dispatchers.Main) {
                model.onEvent(GalleryEvent.NavigateBack)
                assertEquals(LoadState.Ready, model.state.value.loadState)
                assertSame(loaded, model.state.value.artists)
                model.onEvent(GalleryEvent.OpenManage)
                model.onEvent(GalleryEvent.OpenHome)
                assertEquals(LoadState.Ready, model.state.value.loadState)
                assertSame(loaded, model.state.value.artists)
            }
        }
        assertEquals(initialQueries, homeQueries.get())
    }

    @Test fun realRoomWritesRefreshHiddenHomeThenReturnWithoutLoading() = runBlocking {
        seed()
        start()
        withContext(Dispatchers.Main) { model.onEvent(GalleryEvent.OpenManage) }
        assertEquals(SaveResult.Saved("a"), repository.renameArtist("a", "已修改姓名"))
        withTimeout(5_000) { model.state.first { it.artists.singleOrNull()?.name == "已修改姓名" } }
        assertTrue(repository.createCollection("a", "新增作品集") is SaveResult.Saved)
        withTimeout(5_000) { model.state.first { it.artists.singleOrNull()?.collectionCount == 2 } }
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        database.withTransaction {
            val dao = database.galleryDao()
            listOf("x" to first, "y" to second).forEachIndexed { index, (id, key) ->
                dao.insertAsset(MediaAssetEntity(id, "$id.png", "IMAGE", 4, null, null, id, key, "READY", 1L + index))
                dao.insertCollectionMedia(CollectionMediaEntity("c", id, index, "$id.png", null))
            }
        }
        withTimeout(5_000) { model.state.first { it.artists.singleOrNull()?.imageCount == 2 } }
        assertTrue(repository.setArtistCover("a", "y"))
        withTimeout(5_000) { model.state.first { it.artists.singleOrNull()?.cover == CoverRef.Asset(second) } }
        database.withTransaction {
            val dao = database.galleryDao()
            dao.deleteAllReferences("x")
            dao.deleteAsset("x")
        }
        withTimeout(5_000) { model.state.first { it.artists.singleOrNull()?.imageCount == 1 } }
        assertEquals(GalleryRoute.Manage, model.state.value.route)
        assertEquals(LoadState.Ready, model.state.value.loadState)
        val updated = model.state.value.artists
        withContext(Dispatchers.Main) {
            model.onEvent(GalleryEvent.OpenHome)
            assertEquals(LoadState.Ready, model.state.value.loadState)
            assertSame(updated, model.state.value.artists)
        }
        assertEquals("已修改姓名", updated.single().name)
        assertEquals(CoverRef.Asset(second), updated.single().cover)
        assertTrue(homeQueries.get() > 1)
    }
}
