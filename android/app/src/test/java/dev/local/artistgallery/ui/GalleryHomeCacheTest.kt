package dev.local.artistgallery.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.data.BrowsePreferences
import dev.local.artistgallery.data.GalleryRepository
import dev.local.artistgallery.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryHomeCacheTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private var serial = 0
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }

    private fun model(repo: HomeRepository, handle: SavedStateHandle = SavedStateHandle(), preferences: BrowsePreferences? = null): GalleryViewModel =
        GalleryViewModel(repo, handle, browsePreferences = preferences, computationDispatcher = dispatcher).also { store.put("home${serial++}", it) }

    @Test fun returningFromArtistCollectionAndManageImmediatelyReusesHomeListWithoutNewSubscription() = runTest(dispatcher) {
        val repo = HomeRepository(listOf(artist("a"), artist("b")))
        val vm = model(repo)
        runCurrent()
        val loaded = vm.state.value.artists
        repeat(3) {
            vm.onEvent(GalleryEvent.OpenArtist("a"))
            runCurrent()
            vm.onEvent(GalleryEvent.OpenCollection("c", "a"))
            runCurrent()
            vm.onEvent(GalleryEvent.NavigateBack)
            runCurrent()
            vm.onEvent(GalleryEvent.NavigateBack)
            assertEquals(GalleryRoute.Home, vm.state.value.route)
            assertEquals(LoadState.Ready, vm.state.value.loadState)
            assertSame(loaded, vm.state.value.artists)
            vm.onEvent(GalleryEvent.OpenManage)
            vm.onEvent(GalleryEvent.OpenHome)
            assertEquals(LoadState.Ready, vm.state.value.loadState)
            assertSame(loaded, vm.state.value.artists)
            runCurrent()
        }
        assertEquals(1, repo.subscriptions)
        assertEquals(1, repo.activeCollectors)
    }

    @Test fun successfulEmptyLibraryRemainsReadyAcrossNavigation() = runTest(dispatcher) {
        val repo = HomeRepository(emptyList())
        val vm = model(repo)
        runCurrent()
        vm.onEvent(GalleryEvent.OpenManage)
        vm.onEvent(GalleryEvent.OpenHome)
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        assertTrue(vm.state.value.artists.isEmpty())
        runCurrent()
        assertEquals(1, repo.subscriptions)
    }

    @Test fun returnBeforeFirstReadKeepsLoadingAndDoesNotRestartPendingRead() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repo = HomeRepository(listOf(artist("a"))).apply { firstReadGate = gate }
        val vm = model(repo)
        runCurrent()
        vm.onEvent(GalleryEvent.OpenManage)
        vm.onEvent(GalleryEvent.OpenHome)
        assertEquals(LoadState.Loading, vm.state.value.loadState)
        runCurrent()
        assertEquals(1, repo.subscriptions)
        gate.complete(Unit)
        runCurrent()
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        assertEquals("a", vm.state.value.artists.single().id)
    }

    @Test fun restoredDetailRouteStartsHomeReadOnceWithoutRestoringStaleHomeReadiness() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repo = HomeRepository(listOf(artist("a"))).apply { firstReadGate = gate }
        val handle = SavedStateHandle(mapOf("route" to arrayListOf("artist", "a")))
        val vm = model(repo, handle)
        runCurrent()
        assertEquals(GalleryRoute.Artist("a"), vm.state.value.route)
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        vm.onEvent(GalleryEvent.OpenHome)
        assertEquals(LoadState.Loading, vm.state.value.loadState)
        gate.complete(Unit)
        runCurrent()
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        assertEquals(1, repo.subscriptions)
    }

    @Test fun hiddenHomeReceivesInsertRenameCountsCoverAndDeletionThenReturnsCurrentList() = runTest(dispatcher) {
        val repo = HomeRepository(listOf(artist("a")))
        val vm = model(repo)
        runCurrent()
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        repo.publish(listOf(artist("a"), artist("b")))
        runCurrent()
        assertEquals(listOf("a", "b"), vm.state.value.artists.map { it.id })
        val changed = artist("a").copy(name = "修改后的名字", collectionCount = 4, imageCount = 12, cover = CoverRef.Asset("new-cover"))
        repo.publish(listOf(changed, artist("b")))
        runCurrent()
        assertEquals(changed, vm.state.value.artists.first())
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        repo.publish(listOf(changed))
        runCurrent()
        val latest = vm.state.value.artists
        vm.onEvent(GalleryEvent.OpenHome)
        assertSame(latest, vm.state.value.artists)
        assertEquals(listOf(changed), latest)
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        assertEquals(1, repo.subscriptions)
    }

    @Test fun hiddenHomeResultCannotReleasePendingDetailLoad() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repo = HomeRepository(listOf(artist("a"))).apply { detailGate = gate }
        val vm = model(repo)
        runCurrent()
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        assertEquals(LoadState.Loading, vm.state.value.loadState)
        repo.publish(listOf(artist("b")))
        runCurrent()
        assertEquals(LoadState.Loading, vm.state.value.loadState)
        assertEquals("b", vm.state.value.artists.single().id)
        gate.complete(Unit)
        runCurrent()
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        vm.onEvent(GalleryEvent.OpenHome)
        assertEquals(LoadState.Ready, vm.state.value.loadState)
    }

    @Test fun equalNewListEmissionRetainsLoadedObjectWhileRealChangeReplacesIt() = runTest(dispatcher) {
        val repo = HomeRepository(listOf(artist("a"), artist("b")))
        val vm = model(repo)
        runCurrent()
        val loaded = vm.state.value.artists
        repo.publish(listOf(artist("a"), artist("b")))
        runCurrent()
        assertSame(loaded, vm.state.value.artists)
        repo.publish(listOf(artist("a").copy(imageCount = 9), artist("b")))
        runCurrent()
        assertNotSame(loaded, vm.state.value.artists)
        assertEquals(9, vm.state.value.artists.first().imageCount)
        assertEquals(1, repo.subscriptions)
    }

    @Test fun searchManualOrderAndScrollRecordSurviveHomeReturnWithoutResubscription() = runTest(dispatcher) {
        val repo = HomeRepository(listOf(artist("a"), artist("b")))
        val preferences = Preferences()
        val vm = model(repo, preferences = preferences)
        runCurrent()
        vm.onEvent(GalleryEvent.ReorderArtists(listOf("b", "a")))
        runCurrent()
        vm.onEvent(GalleryEvent.SearchChanged("a"))
        vm.onEvent(GalleryEvent.RememberScrollPosition("home", 0, 340))
        val loaded = vm.state.value.artists
        vm.onEvent(GalleryEvent.OpenManage)
        vm.onEvent(GalleryEvent.OpenHome)
        assertEquals("a", vm.state.value.searchQuery)
        assertEquals(listOf("b", "a"), vm.state.value.artists.map { it.id })
        assertSame(loaded, vm.state.value.artists)
        assertEquals(GalleryScrollPosition(0, 340), vm.state.value.scrollPositions["home"])
        runCurrent()
        assertEquals(1, repo.subscriptions)
    }

    @Test fun immediateFirstFailureStaysFailedUntilOneExplicitHomeRetry() = runTest(dispatcher) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = HomeRepository(listOf(artist("a"))).apply { readFailuresRemaining = 1 }
        val vm = model(repo)
        assertEquals(1, repo.subscriptions)
        assertEquals(LoadState.Failed, vm.state.value.loadState)
        vm.onEvent(GalleryEvent.OpenManage)
        vm.onEvent(GalleryEvent.OpenHome)
        assertEquals(1, repo.subscriptions)
        assertEquals(LoadState.Failed, vm.state.value.loadState)
        vm.onEvent(GalleryEvent.RetryLoad)
        assertEquals(2, repo.subscriptions)
        assertEquals(1, repo.activeCollectors)
        assertEquals(LoadState.Ready, vm.state.value.loadState)
    }

    @Test fun hiddenHomeFailureKeepsDetailReadyAndSurfacesOnReturnWithoutAutomaticRetry() = runTest(dispatcher) {
        val repo = HomeRepository(listOf(artist("a")))
        val vm = model(repo)
        runCurrent()
        val loaded = vm.state.value.artists
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        repo.fail(IllegalStateException("模拟读取失败"))
        runCurrent()
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        vm.onEvent(GalleryEvent.OpenHome)
        assertEquals(LoadState.Failed, vm.state.value.loadState)
        assertSame(loaded, vm.state.value.artists)
        runCurrent()
        assertEquals(1, repo.subscriptions)
        vm.onEvent(GalleryEvent.RetryLoad)
        assertEquals(LoadState.Loading, vm.state.value.loadState)
        runCurrent()
        assertEquals(2, repo.subscriptions)
        assertEquals(LoadState.Ready, vm.state.value.loadState)
    }

    @Test fun detailFailureAndRetryDoNotRestartHomeOrLoseItsReadyState() = runTest(dispatcher) {
        val repo = HomeRepository(listOf(artist("a"))).apply { detailFails = true }
        val vm = model(repo)
        runCurrent()
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        assertEquals(LoadState.Failed, vm.state.value.loadState)
        repo.detailFails = false
        vm.onEvent(GalleryEvent.RetryLoad)
        runCurrent()
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        assertEquals(1, repo.subscriptions)
        vm.onEvent(GalleryEvent.OpenHome)
        assertEquals(LoadState.Ready, vm.state.value.loadState)
    }

    @Test fun homeRetryCancelsPreviousCollectorBeforeStartingReplacement() = runTest(dispatcher) {
        val repo = HomeRepository(listOf(artist("a")))
        val vm = model(repo)
        runCurrent()
        vm.onEvent(GalleryEvent.RetryLoad)
        runCurrent()
        assertEquals(2, repo.subscriptions)
        assertEquals(1, repo.activeCollectors)
        assertEquals(LoadState.Ready, vm.state.value.loadState)
    }

    @Test fun cancellationIsNotConvertedToReadFailureOrAutomaticallyResubscribed() = runTest(dispatcher) {
        val repo = HomeRepository(listOf(artist("a")))
        val vm = model(repo)
        runCurrent()
        repo.fail(CancellationException("模拟取消"))
        runCurrent()
        assertEquals(0, repo.activeCollectors)
        vm.onEvent(GalleryEvent.OpenManage)
        vm.onEvent(GalleryEvent.OpenHome)
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        runCurrent()
        assertEquals(1, repo.subscriptions)
    }

    @Test fun clearingViewModelCancelsHomeCollectorAndStopsFurtherUpdates() = runTest(dispatcher) {
        val repo = HomeRepository(listOf(artist("a")))
        val vm = model(repo)
        runCurrent()
        val loaded = vm.state.value.artists
        store.clear()
        runCurrent()
        assertEquals(0, repo.activeCollectors)
        repo.publish(listOf(artist("b")))
        runCurrent()
        assertSame(loaded, vm.state.value.artists)
    }

    private class Preferences : BrowsePreferences {
        override val options = MutableStateFlow(BrowseOptions())
        override fun save(options: BrowseOptions) { this.options.value = options }
    }

    private class HomeRepository(var artists: List<ArtistSummary>) : GalleryRepository {
        private val updates = MutableSharedFlow<Result<List<ArtistSummary>>>(extraBufferCapacity = 1)
        var firstReadGate: CompletableDeferred<Unit>? = null
        var detailGate: CompletableDeferred<Unit>? = null
        var readFailuresRemaining = 0
        var detailFails = false
        var subscriptions = 0
        var activeCollectors = 0
        override fun observeArtists(): Flow<List<ArtistSummary>> = flow {
            subscriptions++
            activeCollectors++
            try {
                firstReadGate?.await()
                if (readFailuresRemaining > 0) { readFailuresRemaining--; error("模拟首次读取失败") }
                emit(artists)
                updates.collect { emit(it.getOrThrow()) }
            } finally { activeCollectors-- }
        }
        suspend fun publish(value: List<ArtistSummary>) { artists = value; updates.emit(Result.success(value)) }
        suspend fun fail(error: Exception) { updates.emit(Result.failure(error)) }
        override fun observeArtist(id: String): Flow<ArtistDetail?> = flow {
            detailGate?.await()
            if (detailFails) error("模拟详情读取失败")
            emit(ArtistDetail(artist(id), emptyList()))
        }
        override fun observeCollection(id: String): Flow<CollectionSummary?> = flowOf(CollectionSummary(id, "a", "作品集", false, 0, 0))
        override suspend fun createArtist(name: String): SaveResult = SaveResult.Saved("new")
        override suspend fun createCollection(artistId: String, name: String): SaveResult = SaveResult.Saved("new")
        override suspend fun renameCollection(id: String, name: String): SaveResult = SaveResult.Saved(id)
        override suspend fun renameArtist(id: String, name: String): SaveResult = SaveResult.Saved(id)
    }

    companion object {
        private fun artist(id: String) = ArtistSummary(id, id, 0, 0)
    }
}
