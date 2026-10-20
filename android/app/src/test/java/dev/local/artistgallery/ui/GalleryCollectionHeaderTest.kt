package dev.local.artistgallery.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.local.artistgallery.data.GalleryRepository
import dev.local.artistgallery.data.MediaLibraryRepository
import dev.local.artistgallery.model.*
import java.lang.reflect.Proxy
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class GalleryCollectionHeaderTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }

    private fun model(repo: Repository): GalleryViewModel =
        GalleryViewModel(repo, SavedStateHandle(), repo.media, computationDispatcher = dispatcher).also { store.put("header", it) }

    @Test fun collectionUsesHeaderWithoutReadingFullArtistOrSiblings() = runTest(dispatcher) {
        val repo = Repository()
        val vm = model(repo)
        vm.onEvent(GalleryEvent.OpenCollection("c", "a"))
        runCurrent()
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        assertEquals(ArtistHeader("a", "画师"), vm.state.value.artistHeader)
        assertNull(vm.state.value.artistDetail)
        assertEquals(1, repo.headerSubscriptions)
        assertEquals(0, repo.detailSubscriptions)
        repo.header.value = ArtistHeader("a", "新名字")
        runCurrent()
        assertEquals("新名字", vm.state.value.artistHeader!!.name)
        assertEquals(0, repo.detailSubscriptions)
    }

    @Test fun backRestoresFullParentWithKnownHeaderAndCurrentCollectionUpdates() = runTest(dispatcher) {
        val repo = Repository()
        val vm = model(repo)
        vm.onEvent(GalleryEvent.OpenArtist("a")); runCurrent()
        val parent = vm.state.value
        vm.onEvent(GalleryEvent.OpenCollection("c", "a")); runCurrent()
        assertEquals(1, repo.detailSubscriptions)
        repo.header.value = ArtistHeader("a", "新名字")
        repo.collection.value = repo.collection.value!!.copy(imageCount = 7)
        repo.detail.value = repo.detail.value!!.let { it.copy(artist = it.artist.copy(name = "新名字", imageCount = 42)) }
        runCurrent()
        vm.onEvent(GalleryEvent.NavigateBack)
        val returned = vm.state.value
        assertSame(parent.preparedMedia, returned.preparedMedia)
        assertEquals("新名字", returned.artistDetail!!.artist.name)
        assertEquals(2, returned.artistDetail!!.artist.imageCount)
        assertEquals(listOf("c", "sibling"), returned.artistDetail!!.collections.map { it.id })
        assertEquals(7, returned.artistDetail!!.collections.first().imageCount)
        assertEquals(parent.artistDetail!!.collections.last(), returned.artistDetail!!.collections.last())
        assertNull(returned.artistHeader)
        runCurrent()
        assertEquals(42, vm.state.value.artistDetail!!.artist.imageCount)
        assertEquals(2, repo.detailSubscriptions)
    }

    @Test fun missingHeaderCannotResurrectCachedParentOnBack() = runTest(dispatcher) {
        val repo = Repository()
        val vm = model(repo)
        vm.onEvent(GalleryEvent.OpenArtist("a")); runCurrent()
        vm.onEvent(GalleryEvent.OpenCollection("c", "a")); runCurrent()
        repo.header.value = null
        repo.detail.value = null
        runCurrent()
        assertEquals(LoadState.Failed, vm.state.value.loadState)
        vm.onEvent(GalleryEvent.NavigateBack)
        assertNull(vm.state.value.artistDetail)
        assertEquals(LoadState.Loading, vm.state.value.loadState)
        runCurrent()
        assertEquals(LoadState.Failed, vm.state.value.loadState)
    }

    @Test fun removedCurrentCollectionPrunesKnownCacheEntryWithoutLosingSiblings() = runTest(dispatcher) {
        val repo = Repository()
        val vm = model(repo)
        vm.onEvent(GalleryEvent.OpenArtist("a")); runCurrent()
        val parent = vm.state.value
        vm.onEvent(GalleryEvent.OpenCollection("c", "a")); runCurrent()
        repo.detailSource = emptyFlow()
        repo.collection.value = null
        runCurrent()
        assertEquals(GalleryRoute.Artist("a"), vm.state.value.route)
        assertEquals(ArtistTab.Collections, vm.state.value.artistTab)
        assertEquals(listOf("sibling"), vm.state.value.artistDetail!!.collections.map { it.id })
        assertEquals(1, vm.state.value.artistDetail!!.artist.collectionCount)
        assertSame(parent.preparedMedia, vm.state.value.preparedMedia)
    }

    @Test fun cancelledHeaderCannotOverwriteReenteredCollection() = runTest(dispatcher) {
        val repo = Repository()
        var release: (() -> Unit)? = null
        repo.headerSource = object : Flow<ArtistHeader?> {
            override suspend fun collect(collector: FlowCollector<ArtistHeader?>) {
                collector.emit(ArtistHeader("a", "旧名字"))
                try { awaitCancellation() } catch (_: CancellationException) {
                    suspendCoroutine<Unit> { continuation -> release = { continuation.resume(Unit) } }
                    collector.emit(null)
                }
            }
        }
        val vm = model(repo)
        vm.onEvent(GalleryEvent.OpenCollection("c", "a")); runCurrent()
        vm.onEvent(GalleryEvent.OpenManage); runCurrent()
        repo.headerSource = repo.header
        repo.header.value = ArtistHeader("a", "新名字")
        vm.onEvent(GalleryEvent.OpenCollection("c", "a")); runCurrent()
        assertNotNull(release)
        release!!.invoke(); runCurrent()
        assertEquals(GalleryRoute.Collection("c", "a"), vm.state.value.route)
        assertEquals("新名字", vm.state.value.artistHeader!!.name)
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        assertEquals(0, repo.detailSubscriptions)
    }

    private class Repository : GalleryRepository {
        val collection = MutableStateFlow<CollectionSummary?>(CollectionSummary("c", "a", "图集", false, 1, 0))
        val sibling = CollectionSummary("sibling", "a", "其他图集", false, 1, 0)
        val header = MutableStateFlow<ArtistHeader?>(ArtistHeader("a", "画师"))
        val detail = MutableStateFlow<ArtistDetail?>(ArtistDetail(ArtistSummary("a", "画师", 2, 2), listOf(collection.value!!, sibling)))
        var headerSource: Flow<ArtistHeader?> = header
        var detailSource: Flow<ArtistDetail?> = detail
        var detailSubscriptions = 0
        var headerSubscriptions = 0
        val images = listOf(MediaAssetSummary("one", "one.png", MediaType.IMAGE, 8, 0, "one.png", "one"),
            MediaAssetSummary("two", "two.png", MediaType.IMAGE, 8, 1, "two.png", "two"))
        val media = Proxy.newProxyInstance(MediaLibraryRepository::class.java.classLoader, arrayOf(MediaLibraryRepository::class.java)) { _, method, _ ->
            when (method.name) {
                "observeArtistImages" -> flowOf(images)
                "observeCollectionMedia" -> flowOf(listOf(images.first()))
                "observeLatestImportBatches" -> flowOf(emptyList<ImportBatchHistorySummary>())
                else -> error("Browsing must not call ${method.name}")
            }
        } as MediaLibraryRepository
        override fun observeArtists(): Flow<List<ArtistSummary>> = flowOf(emptyList())
        override fun observeArtist(id: String): Flow<ArtistDetail?> { detailSubscriptions++; return detailSource }
        override fun observeArtistHeader(id: String): Flow<ArtistHeader?> { headerSubscriptions++; return headerSource }
        override fun observeCollection(id: String): Flow<CollectionSummary?> = collection
        override suspend fun createArtist(name: String): SaveResult = error("Browsing must not write")
        override suspend fun renameArtist(id: String, name: String): SaveResult = error("Browsing must not write")
        override suspend fun createCollection(artistId: String, name: String): SaveResult = error("Browsing must not write")
        override suspend fun renameCollection(id: String, name: String): SaveResult = error("Browsing must not write")
    }
}
