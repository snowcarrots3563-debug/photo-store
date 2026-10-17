package dev.local.artistgallery.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.local.artistgallery.data.ArtistImageLayout
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.data.BrowsePreferences
import dev.local.artistgallery.data.GalleryRepository
import dev.local.artistgallery.data.MediaLibraryRepository
import dev.local.artistgallery.model.*
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.ZoneId
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryMediaPreparationTest {
    private val main = StandardTestDispatcher()
    private val worker = QueuedDispatcher()
    private val store = ViewModelStore()
    private val source = MutableStateFlow(listOf(image("one", 1), image("two", 2)))
    private val preferences = Preferences()
    private var serial = 0

    @Before fun setup() { Dispatchers.setMain(main) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }

    private fun model(mediaSource: Flow<List<MediaAssetSummary>> = source): GalleryViewModel {
        val media = Proxy.newProxyInstance(MediaLibraryRepository::class.java.classLoader, arrayOf(MediaLibraryRepository::class.java)) { _, method, _ ->
            when (method.name) {
                "observeArtistImages", "observeCollectionMedia", "observeUnassignedMedia" -> mediaSource
                "observeLatestImportBatches" -> flowOf(emptyList<ImportBatchHistorySummary>())
                else -> error("Unexpected read: ${method.name}")
            }
        } as MediaLibraryRepository
        return GalleryViewModel(Repository(), SavedStateHandle(), media, browsePreferences = preferences,
            computationDispatcher = worker).also { store.put("preparation${serial++}", it) }
    }

    private fun TestScope.complete() { runCurrent(); worker.runAll(); runCurrent() }

    @Test fun publicationDoesNotTraverseThePreparedListsDuringStateEquality() {
        fun prepared(): PreparedMedia {
            val items = object : AbstractList<MediaAssetSummary>() {
                override val size = 10_000
                override fun get(index: Int): MediaAssetSummary = error("Main must not compare prepared items")
            }
            return PreparedMedia(items, MediaSort.Default, ArtistImageLayout.List, ZoneId.of("UTC"), emptyList())
        }
        assertNotEquals(GalleryUiState(preparedMedia = prepared()), GalleryUiState(preparedMedia = prepared()))
    }

    @Test fun mediaReadsWaitForWorkerAndRowsArePublishedWithTheirAppliedConfiguration() = runTest(main) {
        source.value = emptyList()
        val vm = model()
        vm.setGridZoneId(ZoneId.of("UTC"))
        vm.onEvent(GalleryEvent.SetImageLayout(ArtistImageLayout.Large))
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        vm.onEvent(GalleryEvent.SetImageLayout(ArtistImageLayout.Large))
        val items = listOf(image("one", 1), image("two", 2))
        source.value = object : AbstractList<MediaAssetSummary>() {
            override val size = items.size
            override fun get(index: Int): MediaAssetSummary {
                assertTrue("Media traversal must run on the computation dispatcher", worker.executing)
                return items[index]
            }
        }
        runCurrent()
        assertEquals(LoadState.Loading, vm.state.value.mediaLoadState)
        assertNull(vm.state.value.preparedMedia)
        complete()
        val prepared = vm.state.value.preparedMedia!!
        assertEquals(ArtistImageLayout.Large, prepared.layout)
        assertEquals(ZoneId.of("UTC"), prepared.zoneId)
        assertEquals(prepared.items, prepared.gridRows.flatMap { it.tiles }.map { it.media })
    }

    @Test fun sortChangeUsesLatestRawEmissionRatherThanLastDisplayedSnapshot() = runTest(main) {
        val vm = model(); vm.onEvent(GalleryEvent.OpenArtist("a")); runCurrent(); complete()
        val previous = vm.state.value.preparedMedia
        source.value = listOf(image("new", 20), image("fresh", 10)); runCurrent()
        vm.onEvent(GalleryEvent.SetArtistImageSort(MediaSort.SizeAscending))
        assertSame(previous, vm.state.value.preparedMedia)
        complete()
        assertEquals(listOf("fresh", "new"), vm.state.value.mediaItems.map { it.id })
        assertEquals(MediaSort.SizeAscending, vm.state.value.preparedMedia!!.sort)
    }

    @Test fun completedOldResultCannotPublishAfterLeavingAndReenteringTheSameRoute() = runTest(main) {
        val vm = model(); vm.onEvent(GalleryEvent.OpenArtist("a")); runCurrent()
        worker.runAll() // The completed result is queued for Main, which has not accepted it yet.
        vm.onEvent(GalleryEvent.OpenManage)
        source.value = listOf(image("fresh", 30))
        vm.onEvent(GalleryEvent.OpenArtist("a")); runCurrent(); complete()
        assertEquals(listOf("fresh"), vm.state.value.mediaItems.map { it.id })
    }

    @Test fun selectedIdsAreIntersectedAtPublicationWithoutLosingNewSelections() = runTest(main) {
        val vm = model(); vm.onEvent(GalleryEvent.OpenArtist("a")); runCurrent(); complete()
        source.value = source.value + image("new", 3); runCurrent()
        vm.onEvent(GalleryEvent.StartMediaSelection("one"))
        vm.onEvent(GalleryEvent.ToggleMediaSelection("two"))
        complete()
        assertEquals(setOf("one", "two"), vm.state.value.selectedMediaIds)
    }

    @Test fun immediateBackRestoresTheIdenticalCompleteGridWithoutAdvancingEitherDispatcher() = runTest(main) {
        preferences.save(BrowseOptions(artistLayouts = mapOf("a" to ArtistImageLayout.Large)))
        val vm = model(); vm.onEvent(GalleryEvent.OpenArtist("a")); runCurrent(); complete()
        vm.onEvent(GalleryEvent.RememberScrollPosition("artist:a:Images:Large:Default", 2, 77))
        val parent = vm.state.value.preparedMedia!!
        assertTrue(parent.gridRows.isNotEmpty())
        vm.onEvent(GalleryEvent.OpenCollection("c", "a")); runCurrent(); complete()
        vm.onEvent(GalleryEvent.NavigateBack)
        assertSame(parent, vm.state.value.preparedMedia)
        assertSame(parent.gridRows, vm.state.value.preparedMedia!!.gridRows)
        assertEquals(LoadState.Ready, vm.state.value.mediaLoadState)
        assertEquals(GalleryScrollPosition(2, 77), vm.state.value.scrollPositions["artist:a:Images:Large:Default"])
        runCurrent(); complete()
        assertSame("An unchanged return query must keep the cached snapshot", parent, vm.state.value.preparedMedia)
    }

    @Test fun equivalentSourceEmissionKeepsTheCompletedSnapshotAndGrid() = runTest(main) {
        val emissions = MutableSharedFlow<List<MediaAssetSummary>>(replay = 1)
        emissions.emit(source.value)
        preferences.save(BrowseOptions(artistLayouts = mapOf("a" to ArtistImageLayout.Large)))
        val vm = model(emissions); vm.onEvent(GalleryEvent.OpenArtist("a")); runCurrent(); complete()
        val prepared = vm.state.value.preparedMedia!!
        emissions.emit(source.value.map { it.copy() }); runCurrent(); complete()
        assertSame(prepared, vm.state.value.preparedMedia)
        assertSame(prepared.gridRows, vm.state.value.preparedMedia!!.gridRows)
    }

    @Test fun backDuringParentResortRestoresCompleteOldSnapshotThenAppliesLatestRequest() = runTest(main) {
        preferences.save(BrowseOptions(artistLayouts = mapOf("a" to ArtistImageLayout.Large)))
        val vm = model(); vm.onEvent(GalleryEvent.OpenArtist("a")); runCurrent(); complete()
        val parent = vm.state.value.preparedMedia!!
        vm.onEvent(GalleryEvent.OpenCollection("c", "a")); runCurrent(); complete()
        preferences.save(preferences.options.value.copy(artistImageSorts = mapOf("a" to MediaSort.SizeAscending)))
        runCurrent()
        vm.onEvent(GalleryEvent.NavigateBack)
        assertSame(parent, vm.state.value.preparedMedia)
        assertTrue(vm.state.value.preparedMedia!!.gridRows.isNotEmpty())
        runCurrent(); complete()
        assertEquals(MediaSort.SizeAscending, vm.state.value.preparedMedia!!.sort)
        assertEquals(listOf("one", "two"), vm.state.value.mediaItems.map { it.id })
    }

    @Test fun rapidRequestedLayoutsAndSortsPublishOnlyTheLatestCoherentSnapshot() = runTest(main) {
        val vm = model(); vm.onEvent(GalleryEvent.OpenArtist("a")); runCurrent(); complete()
        val previous = vm.state.value.preparedMedia
        vm.onEvent(GalleryEvent.CycleImageLayout)
        vm.onEvent(GalleryEvent.CycleImageLayout)
        vm.onEvent(GalleryEvent.SetArtistImageSort(MediaSort.SizeAscending))
        vm.onEvent(GalleryEvent.SetArtistImageSort(MediaSort.NameDescending))
        assertSame(previous, vm.state.value.preparedMedia)
        complete()
        val prepared = vm.state.value.preparedMedia!!
        assertEquals(ArtistImageLayout.Medium, prepared.layout)
        assertEquals(MediaSort.NameDescending, prepared.sort)
        assertEquals(prepared.items, prepared.gridRows.flatMap { it.tiles }.map { it.media })
    }

    @Test fun timezoneChangeRebuildsRowsWithoutBlankingTheCompletedSnapshot() = runTest(main) {
        val midnight = Instant.parse("2026-10-12T16:00:00Z").toEpochMilli()
        source.value = listOf(image("before", 1).copy(createdAt = midnight - 1), image("after", 2).copy(createdAt = midnight))
        preferences.save(BrowseOptions(artistLayouts = mapOf("a" to ArtistImageLayout.Large)))
        val vm = model(); vm.setGridZoneId(ZoneId.of("UTC"))
        vm.onEvent(GalleryEvent.OpenArtist("a")); runCurrent(); complete()
        val previous = vm.state.value.preparedMedia!!
        assertEquals(1, previous.gridRows.count { it.dateTitle != null })
        vm.setGridZoneId(ZoneId.of("Asia/Shanghai"))
        assertSame(previous, vm.state.value.preparedMedia)
        complete()
        assertEquals(2, vm.state.value.preparedMedia!!.gridRows.count { it.dateTitle != null })
        val completed = vm.state.value.preparedMedia
        vm.setGridZoneId(ZoneId.of("Asia/Shanghai"))
        assertEquals(0, worker.pending)
        assertSame(completed, vm.state.value.preparedMedia)
    }

    @Test fun pauseRejectsPendingResultsAndResumePreparesLatestSource() = runTest(main) {
        val vm = model(); vm.onEvent(GalleryEvent.OpenArtist("a")); runCurrent(); complete()
        val previous = vm.state.value.preparedMedia
        source.value = listOf(image("stale", 10)); runCurrent()
        vm.setGalleryQueriesPaused(true)
        source.value = listOf(image("fresh", 20)); complete()
        assertSame(previous, vm.state.value.preparedMedia)
        vm.setGalleryQueriesPaused(false); runCurrent(); complete()
        assertEquals(listOf("fresh"), vm.state.value.mediaItems.map { it.id })
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        var executing = false
            private set
        val pending get() = tasks.size
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun runAll() {
            while (tasks.isNotEmpty()) {
                executing = true
                try { tasks.removeFirst().run() } finally { executing = false }
            }
        }
    }

    private class Preferences : BrowsePreferences {
        override val options = MutableStateFlow(BrowseOptions())
        override fun save(options: BrowseOptions) { this.options.value = options }
    }

    private class Repository : GalleryRepository {
        private val collection = CollectionSummary("c", "a", "Collection", false, 2, 0)
        override fun observeArtists() = flowOf(emptyList<ArtistSummary>())
        override fun observeArtist(id: String) = flowOf(ArtistDetail(ArtistSummary(id, id, 1, 2), listOf(collection)))
        override fun observeCollection(id: String) = flowOf(collection)
        override suspend fun createArtist(name: String): SaveResult = error("read only")
        override suspend fun renameArtist(id: String, name: String): SaveResult = error("read only")
        override suspend fun createCollection(artistId: String, name: String): SaveResult = error("read only")
        override suspend fun renameCollection(id: String, name: String): SaveResult = error("read only")
    }

    private companion object {
        fun image(id: String, size: Long) = MediaAssetSummary(id, "$id.png", MediaType.IMAGE, size, 0, "$id.png", "key-$id", size)
    }
}
