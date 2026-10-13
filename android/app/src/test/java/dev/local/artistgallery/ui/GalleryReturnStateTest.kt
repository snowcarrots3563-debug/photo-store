package dev.local.artistgallery.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.local.artistgallery.data.GalleryRepository
import dev.local.artistgallery.data.MediaLibraryRepository
import dev.local.artistgallery.model.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class GalleryReturnStateTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }

    private fun model(repository: ReturnRepository, media: ReturnMedia): GalleryViewModel =
        GalleryViewModel(repository, SavedStateHandle(), media.repository).also { store.put("return", it) }

    @Test fun collectionBackImmediatelyRestoresParentImagesAndTabThenObservesUpdates() = runTest(dispatcher) {
        val repo = ReturnRepository()
        val media = ReturnMedia()
        val vm = model(repo, media)
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        vm.onEvent(GalleryEvent.SwitchArtistTab(ArtistTab.Collections))
        vm.onEvent(GalleryEvent.RememberScrollPosition("artist:a:Collections:List", 3, 400))
        val parent = vm.state.value
        vm.onEvent(GalleryEvent.OpenCollection("c", "a"))
        runCurrent()
        assertEquals(listOf(image("second")), vm.state.value.mediaItems)
        repo.detail.value = repo.detail.value!!.let { it.copy(artist = it.artist.copy(name = "新名字")) }
        runCurrent()
        vm.onEvent(GalleryEvent.NavigateBack)
        val returned = vm.state.value
        assertEquals(GalleryRoute.Artist("a"), returned.route)
        assertEquals(LoadState.Ready, returned.loadState)
        assertEquals(LoadState.Ready, returned.mediaLoadState)
        assertEquals(ArtistTab.Collections, returned.artistTab)
        assertSame(parent.mediaItems, returned.mediaItems)
        assertEquals("新名字", returned.artistDetail!!.artist.name)
        assertNull(returned.collection)
        assertEquals(GalleryScrollPosition(3, 400), returned.scrollPositions["artist:a:Collections:List"])
        runCurrent()
        assertEquals(2, media.artistSubscriptions)
        media.images.value = listOf(image("newly-imported"))
        repo.detail.value = repo.detail.value!!.let { it.copy(artist = it.artist.copy(imageCount = 1)) }
        runCurrent()
        assertEquals(listOf(image("newly-imported")), vm.state.value.mediaItems)
        assertEquals(1, vm.state.value.artistDetail!!.artist.imageCount)
    }

    @Test fun unrelatedNavigationDiscardsImmediateParentSnapshot() = runTest(dispatcher) {
        val repo = ReturnRepository()
        val media = ReturnMedia()
        val vm = model(repo, media)
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        vm.onEvent(GalleryEvent.OpenCollection("c", "a"))
        runCurrent()
        vm.onEvent(GalleryEvent.OpenManage)
        runCurrent()
        vm.onEvent(GalleryEvent.OpenCollection("c", "a"))
        runCurrent()
        vm.onEvent(GalleryEvent.NavigateBack)
        assertEquals(GalleryRoute.Artist("a"), vm.state.value.route)
        assertEquals(LoadState.Loading, vm.state.value.loadState)
        assertEquals(LoadState.Loading, vm.state.value.mediaLoadState)
        assertNull(vm.state.value.artistDetail)
        assertTrue(vm.state.value.mediaItems.isEmpty())
        runCurrent()
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        assertEquals(media.images.value, vm.state.value.mediaItems)
    }

    @Test fun quickBackBeforeCollectionLoadsRestoresAlreadyReadyParent() = runTest(dispatcher) {
        val repo = ReturnRepository()
        val media = ReturnMedia()
        val vm = model(repo, media)
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        val parent = vm.state.value
        vm.onEvent(GalleryEvent.OpenCollection("c", "a"))
        assertEquals(LoadState.Loading, vm.state.value.loadState)
        vm.onEvent(GalleryEvent.NavigateBack)
        assertEquals(GalleryRoute.Artist("a"), vm.state.value.route)
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        assertEquals(LoadState.Ready, vm.state.value.mediaLoadState)
        assertEquals(ArtistTab.Images, vm.state.value.artistTab)
        assertSame(parent.artistDetail, vm.state.value.artistDetail)
        assertSame(parent.mediaItems, vm.state.value.mediaItems)
        runCurrent()
        assertEquals(2, media.artistSubscriptions)
    }

    @Test fun overlayBackKeepsListAndScrollWithoutRestartingRouteObservers() = runTest(dispatcher) {
        val repo = ReturnRepository()
        val media = ReturnMedia()
        val vm = model(repo, media)
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        vm.onEvent(GalleryEvent.RememberScrollPosition("artist:a:Images:List:Default", 2, 175))
        val before = vm.state.value
        val detailSubscriptions = repo.artistSubscriptions
        val imageSubscriptions = media.artistSubscriptions
        vm.onEvent(GalleryEvent.OpenMedia("first"))
        assertEquals(GalleryOverlay.CardViewer("first"), vm.state.value.overlay)
        vm.onEvent(GalleryEvent.NavigateBack)
        assertEquals(GalleryOverlay.None, vm.state.value.overlay)
        assertSame(before.mediaItems, vm.state.value.mediaItems)
        assertSame(before.artistDetail, vm.state.value.artistDetail)
        assertEquals(before.scrollPositions, vm.state.value.scrollPositions)
        assertEquals(LoadState.Ready, vm.state.value.mediaLoadState)
        runCurrent()
        assertEquals(detailSubscriptions, repo.artistSubscriptions)
        assertEquals(imageSubscriptions, media.artistSubscriptions)
    }

    @Test fun incompleteParentCannotSeedAReadyReturn() = runTest(dispatcher) {
        val repo = ReturnRepository()
        val media = ReturnMedia().apply { artistSource = emptyFlow() }
        val vm = model(repo, media)
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        assertEquals(LoadState.Loading, vm.state.value.mediaLoadState)
        vm.onEvent(GalleryEvent.OpenCollection("c", "a"))
        runCurrent()
        vm.onEvent(GalleryEvent.NavigateBack)
        assertEquals(LoadState.Loading, vm.state.value.loadState)
        assertEquals(LoadState.Loading, vm.state.value.mediaLoadState)
        assertTrue(vm.state.value.mediaItems.isEmpty())
    }

    @Test fun removedLoadedCollectionReturnsToCollectionsWithoutErasingReadyParent() = runTest(dispatcher) {
        val repo = ReturnRepository()
        val media = ReturnMedia()
        val vm = model(repo, media)
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        val parentImages = vm.state.value.mediaItems
        vm.onEvent(GalleryEvent.OpenCollection("c", "a"))
        runCurrent()
        repo.detail.value = repo.detail.value!!.copy(collections = emptyList())
        repo.collection.value = null
        runCurrent()
        assertEquals(GalleryRoute.Artist("a"), vm.state.value.route)
        assertEquals(ArtistTab.Collections, vm.state.value.artistTab)
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        assertEquals(LoadState.Ready, vm.state.value.mediaLoadState)
        assertEquals(repo.detail.value, vm.state.value.artistDetail)
        assertEquals(parentImages, vm.state.value.mediaItems)
        assertEquals(listOf("first", "second"), vm.state.value.mediaItems.map { it.id })
        runCurrent()
        assertEquals(LoadState.Ready, vm.state.value.loadState)
    }

    @Test fun initiallyMissingCollectionRemainsAnErrorInsteadOfNavigating() = runTest(dispatcher) {
        val repo = ReturnRepository().apply { collection.value = null }
        val vm = model(repo, ReturnMedia())
        vm.onEvent(GalleryEvent.OpenCollection("c", "a"))
        runCurrent()
        assertEquals(GalleryRoute.Collection("c", "a"), vm.state.value.route)
        assertEquals(LoadState.Failed, vm.state.value.loadState)
    }

    @Test fun missingParentDoesNotRedirectRemovedLoadedCollection() = runTest(dispatcher) {
        val repo = ReturnRepository()
        val vm = model(repo, ReturnMedia())
        vm.onEvent(GalleryEvent.OpenCollection("c", "a"))
        runCurrent()
        repo.detail.value = null
        repo.collection.value = null
        runCurrent()
        assertEquals(GalleryRoute.Collection("c", "a"), vm.state.value.route)
        assertEquals(LoadState.Failed, vm.state.value.loadState)
    }

    @Test fun removedLoadedCollectionWithoutParentSnapshotReturnsToCollections() = runTest(dispatcher) {
        val repo = ReturnRepository()
        val vm = model(repo, ReturnMedia())
        vm.onEvent(GalleryEvent.OpenCollection("c", "a"))
        runCurrent()
        repo.collection.value = null
        runCurrent()
        assertEquals(GalleryRoute.Artist("a"), vm.state.value.route)
        assertEquals(ArtistTab.Collections, vm.state.value.artistTab)
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        assertNull(vm.state.value.collection)
    }

    @Test fun cancelledDetailEmissionCannotOverwriteReenteredArtistWithSameId() = runTest(dispatcher) {
        val repo = ReturnRepository()
        var releaseLateEmission: (() -> Unit)? = null
        repo.artistSource = object : Flow<ArtistDetail?> {
            override suspend fun collect(collector: FlowCollector<ArtistDetail?>) {
                collector.emit(repo.detail.value)
                try { awaitCancellation() } catch (_: CancellationException) {
                    suspendCoroutine<Unit> { continuation -> releaseLateEmission = { continuation.resume(Unit) } }
                    assertFalse(currentCoroutineContext().isActive)
                    collector.emit(null)
                }
            }
        }
        val vm = model(repo, ReturnMedia())
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        repo.artistSource = repo.detail
        vm.onEvent(GalleryEvent.OpenManage)
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        assertNotNull(releaseLateEmission)
        releaseLateEmission!!.invoke()
        runCurrent()
        assertEquals(GalleryRoute.Artist("a"), vm.state.value.route)
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        assertEquals(repo.detail.value, vm.state.value.artistDetail)
    }

    @Test fun cancelledMediaEmissionCannotOverwriteManageOrReenteredCollection() = runTest(dispatcher) {
        val repo = ReturnRepository()
        val media = ReturnMedia()
        val releaseLateEmissions = mutableListOf<() -> Unit>()
        media.collectionSource = object : Flow<List<MediaAssetSummary>> {
            override suspend fun collect(collector: FlowCollector<List<MediaAssetSummary>>) {
                collector.emit(listOf(image("second")))
                try { awaitCancellation() } catch (_: CancellationException) {
                    suspendCoroutine<Unit> { continuation -> releaseLateEmissions += { continuation.resume(Unit) } }
                    assertFalse(currentCoroutineContext().isActive)
                    collector.emit(listOf(image("stale")))
                }
            }
        }
        val vm = model(repo, media)
        vm.onEvent(GalleryEvent.OpenCollection("c", "a"))
        runCurrent()
        vm.onEvent(GalleryEvent.OpenManage)
        runCurrent()
        assertEquals(1, releaseLateEmissions.size)
        releaseLateEmissions.first().invoke()
        runCurrent()
        assertEquals(GalleryRoute.Manage, vm.state.value.route)
        assertTrue(vm.state.value.mediaItems.isEmpty())
        vm.onEvent(GalleryEvent.OpenCollection("c", "a"))
        runCurrent()
        media.collectionSource = flowOf(listOf(image("new")))
        vm.onEvent(GalleryEvent.OpenManage)
        vm.onEvent(GalleryEvent.OpenCollection("c", "a"))
        runCurrent()
        assertEquals(2, releaseLateEmissions.size)
        releaseLateEmissions.last().invoke()
        runCurrent()
        assertEquals(GalleryRoute.Collection("c", "a"), vm.state.value.route)
        assertEquals(listOf(image("new")), vm.state.value.mediaItems)
        assertEquals(LoadState.Ready, vm.state.value.mediaLoadState)
    }

    @Test fun cancelledDetailAndMediaErrorsCannotMarkAnotherRouteFailed() = runTest(dispatcher) {
        val repo = ReturnRepository()
        val media = ReturnMedia()
        val releaseLateErrors = mutableListOf<() -> Unit>()
        repo.artistSource = object : Flow<ArtistDetail?> {
            override suspend fun collect(collector: FlowCollector<ArtistDetail?>) {
                collector.emit(repo.detail.value)
                try { awaitCancellation() } catch (_: CancellationException) {
                    suspendCoroutine<Unit> { continuation -> releaseLateErrors += { continuation.resume(Unit) } }
                    assertFalse(currentCoroutineContext().isActive)
                    error("late detail failure")
                }
            }
        }
        media.artistSource = object : Flow<List<MediaAssetSummary>> {
            override suspend fun collect(collector: FlowCollector<List<MediaAssetSummary>>) {
                collector.emit(media.images.value)
                try { awaitCancellation() } catch (_: CancellationException) {
                    suspendCoroutine<Unit> { continuation -> releaseLateErrors += { continuation.resume(Unit) } }
                    assertFalse(currentCoroutineContext().isActive)
                    error("late media failure")
                }
            }
        }
        val vm = model(repo, media)
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        vm.onEvent(GalleryEvent.OpenManage)
        runCurrent()
        assertEquals(2, releaseLateErrors.size)
        releaseLateErrors.forEach { it() }
        runCurrent()
        assertEquals(GalleryRoute.Manage, vm.state.value.route)
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        assertEquals(LoadState.Ready, vm.state.value.mediaLoadState)
    }

    private class ReturnRepository : GalleryRepository {
        val collection = MutableStateFlow<CollectionSummary?>(CollectionSummary("c", "a", "作品集", false, 1, 0))
        val detail = MutableStateFlow<ArtistDetail?>(ArtistDetail(ArtistSummary("a", "画师", 1, 2), listOf(collection.value!!)))
        var artistSource: Flow<ArtistDetail?> = detail
        var artistSubscriptions = 0
        override fun observeArtists(): Flow<List<ArtistSummary>> = flowOf(emptyList())
        override fun observeArtist(id: String): Flow<ArtistDetail?> { artistSubscriptions++; return artistSource }
        override fun observeCollection(id: String): Flow<CollectionSummary?> = collection
        override suspend fun createArtist(name: String): SaveResult = error("浏览不应写入")
        override suspend fun renameArtist(id: String, name: String): SaveResult = error("浏览不应写入")
        override suspend fun createCollection(artistId: String, name: String): SaveResult = error("浏览不应写入")
        override suspend fun renameCollection(id: String, name: String): SaveResult = error("浏览不应写入")
    }

    private class ReturnMedia {
        val images = MutableStateFlow(listOf(image("first"), image("second")))
        var artistSource: Flow<List<MediaAssetSummary>> = images
        var collectionSource: Flow<List<MediaAssetSummary>> = flowOf(listOf(image("second")))
        var artistSubscriptions = 0
        val repository = Proxy.newProxyInstance(MediaLibraryRepository::class.java.classLoader, arrayOf(MediaLibraryRepository::class.java)) { _, method, _ ->
            when (method.name) {
                "observeArtistImages" -> { artistSubscriptions++; artistSource }
                "observeCollectionMedia" -> collectionSource
                "observeLatestImportBatches" -> flowOf(emptyList<ImportBatchSummary>())
                else -> error("浏览不应调用 ${method.name}")
            }
        } as MediaLibraryRepository
    }

    private companion object {
        fun image(id: String) = MediaAssetSummary(id, "$id.png", MediaType.IMAGE, 8, 0, "$id.png", "key-$id")
    }
}
