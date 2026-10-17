package dev.local.artistgallery.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.data.BrowsePreferences
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

/** Cold-source query counts simulate invalidations; actual SQL is covered by the Room test. */
@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class GalleryArchiveQueryPauseTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private var nextModel = 0

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }

    private fun model(repository: Repository, preferences: BrowsePreferences? = null): GalleryViewModel =
        GalleryViewModel(repository, SavedStateHandle(), repository.media, browsePreferences = preferences, computationDispatcher = dispatcher)
            .also { store.put("pause-${nextModel++}", it) }

    @Test fun thousandInvalidationsStopColdQueriesThenRefreshLatestOnce() = runTest(dispatcher) {
        val repo = Repository()
        val vm = model(repo)
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        val sources = listOf(repo.artists, repo.details.getValue("a"), repo.artistImages.getValue("a"))
        val initialQueries = sources.map { it.queries }
        repeat(1000) { index ->
            repo.artists.publish(listOf(artist("a").copy(imageCount = index + 1)))
            repo.details.getValue("a").publish(detail("a").copy(artist = artist("a").copy(imageCount = index + 1)))
            repo.artistImages.getValue("a").publish(listOf(image("one").copy(byteSize = index + 1L), image("two")))
            runCurrent()
        }
        assertEquals(initialQueries.map { it + 1000 }, sources.map { it.queries })
        val loaded = vm.state.value
        vm.setGalleryQueriesPaused(true)
        runCurrent()
        assertEquals(listOf(0, 0, 0), sources.map { it.activeCollectors })
        val pausedQueries = sources.map { it.queries }
        repeat(1000) { index ->
            repo.artists.publish(listOf(artist("a").copy(imageCount = 2000 + index)))
            repo.details.getValue("a").publish(detail("a").copy(artist = artist("a").copy(imageCount = 2000 + index)))
            repo.artistImages.getValue("a").publish(listOf(image("latest-$index")))
            runCurrent()
        }
        assertEquals(pausedQueries, sources.map { it.queries })
        assertSame(loaded.artists, vm.state.value.artists)
        assertSame(loaded.artistDetail, vm.state.value.artistDetail)
        assertSame(loaded.mediaItems, vm.state.value.mediaItems)
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        assertEquals(LoadState.Ready, vm.state.value.mediaLoadState)
        vm.setGalleryQueriesPaused(false)
        assertSame(loaded.mediaItems, vm.state.value.mediaItems)
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        runCurrent()
        assertEquals(pausedQueries.map { it + 1 }, sources.map { it.queries })
        assertEquals(listOf(1, 1, 1), sources.map { it.activeCollectors })
        assertEquals(2999, vm.state.value.artists.single().imageCount)
        assertEquals(2999, vm.state.value.artistDetail!!.artist.imageCount)
        assertEquals("latest-999", vm.state.value.mediaItems.single().id)
    }

    @Test fun repeatedPauseAndResumeAreNoOpsAndOrdinaryNavigationKeepsHomeSubscription() = runTest(dispatcher) {
        val repo = Repository()
        val vm = model(repo)
        runCurrent()
        val loaded = vm.state.value.artists
        repeat(3) { vm.setGalleryQueriesPaused(true) }
        runCurrent()
        assertEquals(1, repo.artists.subscriptions)
        assertSame(loaded, vm.state.value.artists)
        repeat(3) { vm.setGalleryQueriesPaused(false) }
        runCurrent()
        assertEquals(2, repo.artists.subscriptions)
        assertSame(loaded, vm.state.value.artists)
        repeat(3) {
            vm.onEvent(GalleryEvent.OpenArtist("a")); runCurrent()
            vm.onEvent(GalleryEvent.OpenManage); vm.onEvent(GalleryEvent.OpenHome); runCurrent()
            assertEquals(LoadState.Ready, vm.state.value.loadState)
            assertSame(loaded, vm.state.value.artists)
        }
        assertEquals(2, repo.artists.subscriptions)
        assertEquals(1, repo.artists.activeCollectors)
    }

    @Test fun failedHomeNeedsExplicitRetryAndRetryCanBeDeferredWhilePaused() = runTest(dispatcher) {
        val repo = Repository().apply { artists.failuresRemaining = 1 }
        val vm = model(repo)
        runCurrent()
        assertEquals(LoadState.Failed, vm.state.value.loadState)
        vm.setGalleryQueriesPaused(true); vm.setGalleryQueriesPaused(false); runCurrent()
        assertEquals(1, repo.artists.subscriptions)
        assertEquals(LoadState.Failed, vm.state.value.loadState)
        vm.setGalleryQueriesPaused(true)
        vm.onEvent(GalleryEvent.RetryLoad)
        runCurrent()
        assertEquals(1, repo.artists.subscriptions)
        assertEquals(LoadState.Loading, vm.state.value.loadState)
        vm.setGalleryQueriesPaused(false); runCurrent()
        assertEquals(2, repo.artists.subscriptions)
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        assertEquals(1, repo.artists.activeCollectors)
    }

    @Test fun pauseBeforeFirstReadKeepsLoadingAndResumeCompletesOneReplacement() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val repo = Repository().apply { artists.gate = gate }
        val vm = model(repo)
        runCurrent()
        vm.setGalleryQueriesPaused(true); runCurrent()
        assertEquals(LoadState.Loading, vm.state.value.loadState)
        assertEquals(0, repo.artists.activeCollectors)
        gate.complete(Unit)
        vm.setGalleryQueriesPaused(false); runCurrent()
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        assertEquals(2, repo.artists.subscriptions)
        assertEquals(1, repo.artists.activeCollectors)
    }

    @Test fun routeChangesWhilePausedDoNotQueryAndResumeUsesCurrentCollection() = runTest(dispatcher) {
        val repo = Repository()
        val vm = model(repo)
        vm.onEvent(GalleryEvent.OpenArtist("a")); runCurrent()
        vm.setGalleryQueriesPaused(true); runCurrent()
        vm.onEvent(GalleryEvent.OpenCollection("c", "a"))
        vm.onEvent(GalleryEvent.OpenArtist("b"))
        vm.onEvent(GalleryEvent.OpenUnassigned)
        vm.onEvent(GalleryEvent.OpenCollection("d", "b"))
        runCurrent()
        assertEquals(0, repo.collections.getValue("c").subscriptions)
        assertEquals(0, repo.details.getValue("b").subscriptions)
        assertEquals(0, repo.unassigned.subscriptions)
        assertEquals(0, repo.collectionMedia.getValue("d").subscriptions)
        vm.setGalleryQueriesPaused(false); runCurrent()
        assertEquals(GalleryRoute.Collection("d", "b"), vm.state.value.route)
        assertEquals(1, repo.details.getValue("b").subscriptions)
        assertEquals(1, repo.collections.getValue("d").subscriptions)
        assertEquals(1, repo.collectionMedia.getValue("d").subscriptions)
        assertEquals(listOf(image("b-one")), vm.state.value.mediaItems)
        assertEquals(0, repo.unassigned.subscriptions)
    }

    @Test fun unassignedRefreshesAfterPauseAndImportHistoryNeverPauses() = runTest(dispatcher) {
        val repo = Repository()
        val vm = model(repo)
        vm.onEvent(GalleryEvent.OpenUnassigned); runCurrent()
        val queries = repo.unassigned.queries
        vm.setGalleryQueriesPaused(true); runCurrent()
        repo.unassigned.publish(listOf(image("new-unassigned")))
        repo.history.publish(emptyList()); runCurrent()
        assertEquals(queries, repo.unassigned.queries)
        assertEquals(2, repo.history.queries)
        assertEquals(1, repo.history.activeCollectors)
        vm.setGalleryQueriesPaused(false); runCurrent()
        assertEquals(queries + 1, repo.unassigned.queries)
        assertEquals("new-unassigned", vm.state.value.mediaItems.single().id)
    }

    @Test fun pauseRetainsSearchSortScrollAndSelectionsThenRemovesMissingMediaIds() = runTest(dispatcher) {
        val repo = Repository()
        val preferences = Preferences()
        val vm = model(repo, preferences)
        vm.onEvent(GalleryEvent.OpenCollection("c", "a")); runCurrent()
        vm.onEvent(GalleryEvent.SetCollectionMediaSort(MediaSort.SizeDescending))
        runCurrent()
        vm.onEvent(GalleryEvent.SearchChanged("retained query"))
        vm.onEvent(GalleryEvent.RememberScrollPosition("collection:c", 2, 77))
        vm.onEvent(GalleryEvent.StartMediaSelection("one"))
        vm.onEvent(GalleryEvent.ToggleMediaSelection("two"))
        val loaded = vm.state.value
        vm.setGalleryQueriesPaused(true); runCurrent()
        repo.collectionMedia.getValue("c").publish(listOf(image("two"), image("new").copy(byteSize = 40)))
        runCurrent()
        assertSame(loaded.mediaItems, vm.state.value.mediaItems)
        assertEquals(setOf("one", "two"), vm.state.value.selectedMediaIds)
        vm.setGalleryQueriesPaused(false); runCurrent()
        assertEquals("retained query", vm.state.value.searchQuery)
        assertEquals(GalleryScrollPosition(2, 77), vm.state.value.scrollPositions["collection:c"])
        assertEquals(MediaSort.SizeDescending, vm.state.value.browseOptions.collectionMediaSorts["c"])
        assertEquals(listOf("new", "two"), vm.state.value.mediaItems.map { it.id })
        assertEquals(setOf("two"), vm.state.value.selectedMediaIds)
        assertTrue(vm.state.value.mediaSelectionMode)
    }

    @Test fun refreshedHomeAndCollectionListsIntersectEntitySelections() = runTest(dispatcher) {
        val repo = Repository()
        val vm = model(repo)
        runCurrent()
        vm.onEvent(GalleryEvent.StartEntitySelection(EntitySelectionKind.Artists, "a"))
        vm.onEvent(GalleryEvent.ToggleEntitySelection("b"))
        vm.setGalleryQueriesPaused(true); runCurrent()
        repo.artists.publish(listOf(artist("b")))
        vm.setGalleryQueriesPaused(false); runCurrent()
        assertEquals(setOf("b"), vm.state.value.selectedEntityIds)
        vm.onEvent(GalleryEvent.ExitEntitySelection)
        vm.onEvent(GalleryEvent.OpenArtist("a")); vm.onEvent(GalleryEvent.SwitchArtistTab(ArtistTab.Collections)); runCurrent()
        vm.onEvent(GalleryEvent.StartEntitySelection(EntitySelectionKind.Collections, "c"))
        vm.setGalleryQueriesPaused(true); runCurrent()
        repo.details.getValue("a").publish(detail("a").copy(collections = emptyList()))
        vm.setGalleryQueriesPaused(false); runCurrent()
        assertEquals(EntitySelectionKind.Collections, vm.state.value.entitySelectionKind)
        assertTrue(vm.state.value.selectedEntityIds.isEmpty())
    }

    @Test fun removedLoadedCollectionReturnsToParentAfterPause() = runTest(dispatcher) {
        val repo = Repository()
        val vm = model(repo)
        vm.onEvent(GalleryEvent.OpenArtist("a")); runCurrent()
        val parentImages = vm.state.value.mediaItems
        vm.onEvent(GalleryEvent.OpenCollection("c", "a")); runCurrent()
        vm.setGalleryQueriesPaused(true); runCurrent()
        repo.details.getValue("a").publish(detail("a").copy(collections = emptyList()))
        repo.collections.getValue("c").publish(null)
        vm.setGalleryQueriesPaused(false); runCurrent()
        assertEquals(GalleryRoute.Artist("a"), vm.state.value.route)
        assertEquals(ArtistTab.Collections, vm.state.value.artistTab)
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        assertEquals(parentImages, vm.state.value.mediaItems)
        assertNull(vm.state.value.collection)
    }

    @Test fun loadedCollectionWithMediaFailureStillReturnsToParentAfterPause() = runTest(dispatcher) {
        val repo = Repository().apply { collectionMedia.getValue("c").failuresRemaining = 1 }
        val vm = model(repo)
        vm.onEvent(GalleryEvent.OpenCollection("c", "a")); runCurrent()
        assertNotNull(vm.state.value.collection)
        assertEquals(LoadState.Failed, vm.state.value.mediaLoadState)
        vm.setGalleryQueriesPaused(true); runCurrent()
        repo.collections.getValue("c").publish(null)
        vm.setGalleryQueriesPaused(false); runCurrent()
        assertEquals(GalleryRoute.Artist("a"), vm.state.value.route)
        assertEquals(ArtistTab.Collections, vm.state.value.artistTab)
    }

    @Test fun initiallyMissingCollectionAndMissingParentRemainFailuresAfterPause() = runTest(dispatcher) {
        val missing = Repository().apply { collections.getValue("c").value = null }
        val first = model(missing)
        first.onEvent(GalleryEvent.OpenCollection("c", "a")); runCurrent()
        first.setGalleryQueriesPaused(true); runCurrent()
        first.setGalleryQueriesPaused(false); runCurrent()
        assertEquals(GalleryRoute.Collection("c", "a"), first.state.value.route)
        assertEquals(LoadState.Failed, first.state.value.loadState)
        val repo = Repository()
        val second = model(repo)
        second.onEvent(GalleryEvent.OpenCollection("c", "a")); runCurrent()
        second.setGalleryQueriesPaused(true); runCurrent()
        repo.details.getValue("a").publish(null)
        repo.collections.getValue("c").publish(null)
        second.setGalleryQueriesPaused(false); runCurrent()
        assertEquals(GalleryRoute.Collection("c", "a"), second.state.value.route)
        assertEquals(LoadState.Failed, second.state.value.loadState)
    }

    @Test fun cancelledHomeEmissionCannotReplaceFreshCacheAfterResume() = runTest(dispatcher) {
        val repo = Repository()
        val releases = mutableListOf<() -> Unit>()
        repo.artists.source = lateSource(repo.artists.value, releases) { emit(listOf(artist("stale"))) }
        val vm = model(repo); runCurrent()
        vm.setGalleryQueriesPaused(true); runCurrent()
        repo.artists.source = null
        repo.artists.value = listOf(artist("fresh"))
        vm.setGalleryQueriesPaused(false); runCurrent()
        assertEquals(1, releases.size)
        releases.single().invoke(); runCurrent()
        assertEquals(listOf("fresh"), vm.state.value.artists.map { it.id })
        assertEquals(LoadState.Ready, vm.state.value.loadState)
    }

    @Test fun cancelledHomeFailureCannotMarkFreshCacheFailedAfterResume() = runTest(dispatcher) {
        val repo = Repository()
        val releases = mutableListOf<() -> Unit>()
        repo.artists.source = lateSource(repo.artists.value, releases) { error("late home error") }
        val vm = model(repo); runCurrent()
        vm.setGalleryQueriesPaused(true); runCurrent()
        repo.artists.source = null
        vm.setGalleryQueriesPaused(false); runCurrent()
        assertEquals(1, releases.size)
        releases.single().invoke(); runCurrent()
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        vm.onEvent(GalleryEvent.OpenManage); vm.onEvent(GalleryEvent.OpenHome)
        assertEquals(LoadState.Ready, vm.state.value.loadState)
    }

    @Test fun cancelledRouteEmissionAndErrorsCannotOverwriteReenteredSameRoute() = runTest(dispatcher) {
        val repo = Repository()
        val releases = mutableListOf<() -> Unit>()
        repo.details.getValue("a").source = lateSource<ArtistDetail?>(detail("a"), releases) { emit(null) }
        repo.artistImages.getValue("a").source = lateSource(listOf(image("one")), releases) { error("late media error") }
        val vm = model(repo)
        vm.onEvent(GalleryEvent.OpenArtist("a")); runCurrent()
        vm.setGalleryQueriesPaused(true); runCurrent()
        repo.details.getValue("a").source = null
        repo.artistImages.getValue("a").source = null
        repo.artistImages.getValue("a").value = listOf(image("fresh"))
        vm.onEvent(GalleryEvent.OpenManage); vm.onEvent(GalleryEvent.OpenArtist("a"))
        vm.setGalleryQueriesPaused(false); runCurrent()
        assertEquals(2, releases.size)
        releases.forEach { it() }; runCurrent()
        assertEquals(detail("a"), vm.state.value.artistDetail)
        assertEquals(listOf(image("fresh")), vm.state.value.mediaItems)
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        assertEquals(LoadState.Ready, vm.state.value.mediaLoadState)
    }

    @Test fun clearingPausedViewModelCannotRestartQueriesDuringEffectCleanup() = runTest(dispatcher) {
        val repo = Repository()
        val vm = model(repo)
        vm.onEvent(GalleryEvent.OpenArtist("a")); runCurrent()
        vm.setGalleryQueriesPaused(true); runCurrent()
        val loaded = vm.state.value
        val subscriptions = repo.artists.subscriptions
        store.clear()
        vm.setGalleryQueriesPaused(false); runCurrent()
        repo.artists.publish(listOf(artist("late"))); runCurrent()
        assertEquals(subscriptions, repo.artists.subscriptions)
        assertEquals(0, repo.artists.activeCollectors)
        assertSame(loaded.artists, vm.state.value.artists)
        assertSame(loaded.mediaItems, vm.state.value.mediaItems)
        assertEquals(0, repo.history.activeCollectors)
    }

    private fun <T> lateSource(initial: T, releases: MutableList<() -> Unit>, late: suspend FlowCollector<T>.() -> Unit): Flow<T> =
        object : Flow<T> {
            override suspend fun collect(collector: FlowCollector<T>) {
                collector.emit(initial)
                try { awaitCancellation() } catch (_: CancellationException) {
                    suspendCoroutine<Unit> { continuation -> releases += { continuation.resume(Unit) } }
                    assertFalse(currentCoroutineContext().isActive)
                    collector.late()
                }
            }
        }

    private class ColdSource<T>(var value: T) {
        private val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var source: Flow<T>? = null
        var subscriptions = 0
        var activeCollectors = 0
        var queries = 0
        var failuresRemaining = 0
        var gate: CompletableDeferred<Unit>? = null
        fun observe(): Flow<T> = source ?: flow {
            subscriptions++; activeCollectors++
            try {
                suspend fun query() {
                    queries++
                    gate?.await()
                    if (failuresRemaining > 0) { failuresRemaining--; error("synthetic read failure") }
                    emit(value)
                }
                query()
                invalidations.collect { query() }
            } finally { activeCollectors-- }
        }
        suspend fun publish(next: T) { value = next; invalidations.emit(Unit) }
    }

    private class Preferences : BrowsePreferences {
        override val options = MutableStateFlow(BrowseOptions())
        override fun save(options: BrowseOptions) { this.options.value = options }
    }

    private class Repository : GalleryRepository {
        val artists = ColdSource(listOf(artist("a"), artist("b")))
        val details = listOf("a", "b").associateWith { ColdSource<ArtistDetail?>(detail(it)) }
        val collections = mapOf("c" to ColdSource<CollectionSummary?>(collection("c", "a")), "d" to ColdSource<CollectionSummary?>(collection("d", "b")))
        val artistImages = mapOf("a" to ColdSource(listOf(image("one"), image("two"))), "b" to ColdSource(listOf(image("b-one"))))
        val collectionMedia = mapOf("c" to ColdSource(listOf(image("one"), image("two"))), "d" to ColdSource(listOf(image("b-one"))))
        val unassigned = ColdSource(listOf(image("unassigned")))
        val history = ColdSource(emptyList<ImportBatchHistorySummary>())
        val media = Proxy.newProxyInstance(MediaLibraryRepository::class.java.classLoader, arrayOf(MediaLibraryRepository::class.java)) { _, method, args ->
            when (method.name) {
                "observeArtistImages" -> artistImages.getValue(args!![0] as String).observe()
                "observeCollectionMedia" -> collectionMedia.getValue(args!![0] as String).observe()
                "observeUnassignedMedia" -> unassigned.observe()
                "observeLatestImportBatches" -> history.observe()
                else -> error("Browsing must not call ${method.name}")
            }
        } as MediaLibraryRepository
        override fun observeArtists() = artists.observe()
        override fun observeArtist(id: String) = details.getValue(id).observe()
        override fun observeCollection(id: String) = collections.getValue(id).observe()
        override suspend fun createArtist(name: String): SaveResult = error("Browsing must not write")
        override suspend fun renameArtist(id: String, name: String): SaveResult = error("Browsing must not write")
        override suspend fun createCollection(artistId: String, name: String): SaveResult = error("Browsing must not write")
        override suspend fun renameCollection(id: String, name: String): SaveResult = error("Browsing must not write")
    }

    private companion object {
        fun artist(id: String) = ArtistSummary(id, id, 1, 2)
        fun collection(id: String, artistId: String) = CollectionSummary(id, artistId, id, false, 2, 0)
        fun detail(id: String) = ArtistDetail(artist(id), listOf(collection(if (id == "a") "c" else "d", id)))
        fun image(id: String) = MediaAssetSummary(id, "$id.png", MediaType.IMAGE, if (id == "one") 20 else 10, 0, "$id.png", "key-$id")
    }
}
