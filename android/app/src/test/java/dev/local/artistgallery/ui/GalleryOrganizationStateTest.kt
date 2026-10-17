package dev.local.artistgallery.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.local.artistgallery.data.ArtistImageLayout
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.data.BrowsePreferences
import dev.local.artistgallery.data.GalleryRepository
import dev.local.artistgallery.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryOrganizationStateTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private var serial = 0
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }

    private fun model(repo: OrganizationRepository = OrganizationRepository(), prefs: Preferences = Preferences(), handle: SavedStateHandle = SavedStateHandle()): GalleryViewModel =
        GalleryViewModel(repo, handle, browsePreferences = prefs, computationDispatcher = dispatcher).also { store.put("organization${serial++}", it) }

    @Test fun homeLongPressThenTapSelectsWithoutNavigatingAndZeroRemainsInMode() = runTest(dispatcher) {
        val vm = model()
        runCurrent()
        vm.onEvent(GalleryEvent.StartEntitySelection(EntitySelectionKind.Collections, "c"))
        vm.onEvent(GalleryEvent.StartEntitySelection(EntitySelectionKind.Artists, "missing"))
        assertNull(vm.state.value.entitySelectionKind)
        vm.onEvent(GalleryEvent.StartEntitySelection(EntitySelectionKind.Artists, "a"))
        vm.onEvent(GalleryEvent.OpenArtist("b"))
        assertEquals(GalleryRoute.Home, vm.state.value.route)
        assertEquals(setOf("a", "b"), vm.state.value.selectedEntityIds)
        vm.onEvent(GalleryEvent.ToggleEntitySelection("a"))
        vm.onEvent(GalleryEvent.ToggleEntitySelection("b"))
        vm.onEvent(GalleryEvent.OpenDeleteSelectedEntities)
        assertEquals(EntitySelectionKind.Artists, vm.state.value.entitySelectionKind)
        assertTrue(vm.state.value.selectedEntityIds.isEmpty())
        assertEquals(GalleryDialog.None, vm.state.value.dialog)
        assertFalse(vm.state.value.mediaSelectionMode)
    }

    @Test fun entityConfirmationFreezesIdsAndSurvivesRecreationWhileBackCancelsThenExits() = runTest(dispatcher) {
        val repo = OrganizationRepository()
        val handle = SavedStateHandle()
        val vm = model(repo, handle = handle)
        runCurrent()
        vm.onEvent(GalleryEvent.StartEntitySelection(EntitySelectionKind.Artists, "a"))
        vm.onEvent(GalleryEvent.ToggleEntitySelection("b"))
        vm.onEvent(GalleryEvent.OpenDeleteSelectedEntities)
        vm.onEvent(GalleryEvent.ToggleEntitySelection("b"))
        vm.onEvent(GalleryEvent.ToggleEntitySelection("other"))
        assertEquals(GalleryDialog.DeleteArtists(listOf("a", "b")), vm.state.value.dialog)
        val restored = model(repo, handle = handle)
        assertEquals(GalleryDialog.DeleteArtists(listOf("a", "b")), restored.state.value.dialog)
        assertEquals(setOf("a", "other"), restored.state.value.selectedEntityIds)
        restored.onEvent(GalleryEvent.NavigateBack)
        assertEquals(GalleryDialog.None, restored.state.value.dialog)
        assertEquals(EntitySelectionKind.Artists, restored.state.value.entitySelectionKind)
        restored.onEvent(GalleryEvent.NavigateBack)
        assertNull(restored.state.value.entitySelectionKind)
        assertEquals(GalleryRoute.Home, restored.state.value.route)
        assertEquals(0, repo.writes)
    }

    @Test fun collectionSelectionRequiresCollectionTabAndTabOrRouteChangesClearConfirmation() = runTest(dispatcher) {
        val vm = model()
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        vm.onEvent(GalleryEvent.StartEntitySelection(EntitySelectionKind.Collections, "c"))
        assertNull(vm.state.value.entitySelectionKind)
        vm.onEvent(GalleryEvent.SwitchArtistTab(ArtistTab.Collections))
        vm.onEvent(GalleryEvent.StartEntitySelection(EntitySelectionKind.Collections, "c"))
        vm.onEvent(GalleryEvent.OpenCollection("d", "a"))
        assertEquals(setOf("c", "d"), vm.state.value.selectedEntityIds)
        assertEquals(GalleryRoute.Artist("a"), vm.state.value.route)
        vm.onEvent(GalleryEvent.OpenDeleteSelectedEntities)
        assertEquals(GalleryDialog.DeleteCollections(listOf("c", "d")), vm.state.value.dialog)
        vm.onEvent(GalleryEvent.SwitchArtistTab(ArtistTab.Images))
        assertNull(vm.state.value.entitySelectionKind)
        assertEquals(GalleryDialog.None, vm.state.value.dialog)
        vm.onEvent(GalleryEvent.SwitchArtistTab(ArtistTab.Collections))
        vm.onEvent(GalleryEvent.StartEntitySelection(EntitySelectionKind.Collections, "c"))
        vm.onEvent(GalleryEvent.OpenHome)
        assertNull(vm.state.value.entitySelectionKind)
        assertTrue(vm.state.value.selectedEntityIds.isEmpty())
    }

    @Test fun missingArtistUpdatePrunesSelectionWithoutChangingFrozenConfirmation() = runTest(dispatcher) {
        val repo = OrganizationRepository()
        val vm = model(repo)
        runCurrent()
        vm.onEvent(GalleryEvent.StartEntitySelection(EntitySelectionKind.Artists, "a"))
        vm.onEvent(GalleryEvent.ToggleEntitySelection("b"))
        vm.onEvent(GalleryEvent.OpenDeleteSelectedEntities)
        repo.artists.value = repo.artists.value.filterNot { it.id == "a" }
        runCurrent()
        assertEquals(setOf("b"), vm.state.value.selectedEntityIds)
        assertEquals(GalleryDialog.DeleteArtists(listOf("a", "b")), vm.state.value.dialog)
    }

    @Test fun partialArtistAndCollectionOrderSurvivesRecreationAndNewItemsAppend() = runTest(dispatcher) {
        val repo = OrganizationRepository()
        val prefs = Preferences()
        val vm = model(repo, prefs)
        runCurrent()
        vm.onEvent(GalleryEvent.ReorderArtists(listOf("other", "a")))
        assertEquals(listOf("other", "b", "a"), vm.state.value.artists.map { it.id })
        repo.artists.value = repo.artists.value + ArtistSummary("new", "新", 0, 0)
        runCurrent()
        assertEquals(listOf("other", "b", "a", "new"), vm.state.value.artists.map { it.id })
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        vm.onEvent(GalleryEvent.SwitchArtistTab(ArtistTab.Collections))
        vm.onEvent(GalleryEvent.ReorderCollections("a", listOf("e", "c")))
        assertEquals(listOf("e", "d", "c"), vm.state.value.artistDetail!!.collections.map { it.id })
        vm.onEvent(GalleryEvent.OpenCollection("c", "a"))
        runCurrent()
        vm.onEvent(GalleryEvent.NavigateBack)
        assertEquals(listOf("e", "d", "c"), vm.state.value.artistDetail!!.collections.map { it.id })
        val restored = model(repo, prefs)
        runCurrent()
        assertEquals(listOf("other", "b", "a", "new"), restored.state.value.artists.map { it.id })
        assertEquals(listOf("e", "d", "c"), prefs.options.value.collectionOrders["a"])
    }

    @Test fun eachArtistCyclesIndependentlyAndSettingsKeepLegacyFallback() = runTest(dispatcher) {
        val prefs = Preferences(BrowseOptions(imageLayout = ArtistImageLayout.Medium))
        val vm = model(prefs = prefs)
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        assertEquals(ArtistImageLayout.Medium, vm.state.value.browseOptions.imageLayout)
        vm.onEvent(GalleryEvent.CycleImageLayout)
        assertEquals(ArtistImageLayout.List, vm.state.value.browseOptions.imageLayout)
        vm.onEvent(GalleryEvent.CycleImageLayout)
        assertEquals(ArtistImageLayout.Large, vm.state.value.browseOptions.imageLayout)
        vm.onEvent(GalleryEvent.CycleImageLayout)
        assertEquals(ArtistImageLayout.Medium, vm.state.value.browseOptions.imageLayout)
        vm.onEvent(GalleryEvent.CycleImageLayout)
        vm.onEvent(GalleryEvent.SetPlayGifs(false))
        vm.onEvent(GalleryEvent.SetSwipeDelete(false))
        vm.onEvent(GalleryEvent.DismissStorageNotice)
        assertEquals(ArtistImageLayout.Medium, prefs.options.value.imageLayout)
        assertEquals(ArtistImageLayout.List, prefs.options.value.artistLayouts["a"])
        vm.onEvent(GalleryEvent.OpenArtist("b"))
        assertEquals(ArtistImageLayout.Medium, vm.state.value.browseOptions.imageLayout)
        vm.onEvent(GalleryEvent.SetImageLayout(ArtistImageLayout.Large))
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        assertEquals(ArtistImageLayout.List, vm.state.value.browseOptions.imageLayout)
        assertEquals(ArtistImageLayout.Large, prefs.options.value.artistLayouts["b"])
        assertFalse(prefs.options.value.playGifs)
        assertFalse(prefs.options.value.swipeDelete)
        assertTrue(model(prefs = prefs).state.value.browseOptions.storageNoticeDismissed)
    }

    @Test fun newArtistAlwaysStartsInListEvenWithLegacyOrAnotherArtistLayout() = runTest(dispatcher) {
        val prefs = Preferences(BrowseOptions(imageLayout = ArtistImageLayout.Large))
        val vm = model(prefs = prefs)
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.AddArtist("新画师")))
        vm.onEvent(GalleryEvent.Submit)
        runCurrent()
        assertEquals(ArtistImageLayout.List, prefs.options.value.artistLayouts["new"])
        vm.onEvent(GalleryEvent.OpenArtist("new"))
        assertEquals(ArtistImageLayout.List, vm.state.value.browseOptions.imageLayout)
        assertEquals(ArtistImageLayout.Large, prefs.options.value.imageLayout)
    }

    @Test fun archiveCreationOriginAndDraftSurviveRecreationAndSuccessReturnsToImportOnSameRoute() = runTest(dispatcher) {
        val repo = OrganizationRepository()
        val handle = SavedStateHandle()
        val vm = model(repo, handle = handle)
        vm.onEvent(GalleryEvent.OpenManage)
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.AddArtist(fromArchiveImport = true)))
        vm.onEvent(GalleryEvent.DraftChanged("新画师"))
        val restored = model(repo, handle = handle)
        assertEquals(GalleryDialog.AddArtist("新画师", true), restored.state.value.dialog)
        val effects = mutableListOf<GalleryEffect>()
        val messages = mutableListOf<GalleryFeedback>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { restored.effects.toList(effects) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { restored.feedback.toList(messages) }
        restored.onEvent(GalleryEvent.Submit)
        runCurrent()
        assertEquals(GalleryRoute.Manage, restored.state.value.route)
        assertEquals(GalleryDialog.None, restored.state.value.dialog)
        assertEquals(listOf(GalleryEffect.ResumeArchiveArtistSelection), effects)
        assertEquals(listOf(GalleryFeedback.Saved("已添加画师")), messages)
    }

    @Test fun archiveBlankFailureAndCancelRetainRouteAndResumeOnlyWhenDismissed() = runTest(dispatcher) {
        val repo = OrganizationRepository().apply { result = SaveResult.StorageFailure }
        val vm = model(repo)
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        val effects = mutableListOf<GalleryEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.AddArtist(" ", true)))
        vm.onEvent(GalleryEvent.Submit)
        assertEquals(0, repo.writes)
        vm.onEvent(GalleryEvent.DraftChanged("新画师"))
        vm.onEvent(GalleryEvent.Submit)
        runCurrent()
        assertEquals(GalleryDialog.AddArtist("新画师", true), vm.state.value.dialog)
        assertTrue(effects.isEmpty())
        vm.onEvent(GalleryEvent.NavigateBack)
        assertEquals(GalleryRoute.Artist("a"), vm.state.value.route)
        assertEquals(listOf(GalleryEffect.ResumeArchiveArtistSelection), effects)
    }

    private class Preferences(initial: BrowseOptions = BrowseOptions()) : BrowsePreferences {
        override val options = MutableStateFlow(initial)
        override fun save(options: BrowseOptions) { this.options.value = options }
    }

    private class OrganizationRepository : GalleryRepository {
        val artists = MutableStateFlow(listOf("a", "b", "other").map { ArtistSummary(it, "画师$it", 3, 0) })
        var writes = 0
        var result: SaveResult = SaveResult.Saved("new")
        override fun observeArtists(): Flow<List<ArtistSummary>> = artists
        override fun observeArtist(id: String): Flow<ArtistDetail?> = flowOf(ArtistDetail(ArtistSummary(id, "画师$id", 3, 0),
            listOf("c", "d", "e").map { CollectionSummary(it, id, "作品$it", false, 0, 0) }))
        override fun observeCollection(id: String): Flow<CollectionSummary?> = flowOf(CollectionSummary(id, "a", "作品$id", false, 0, 0))
        override suspend fun createArtist(name: String): SaveResult { writes++; return result }
        override suspend fun renameArtist(id: String, name: String): SaveResult { writes++; return result }
        override suspend fun createCollection(artistId: String, name: String): SaveResult { writes++; return result }
        override suspend fun renameCollection(id: String, name: String): SaveResult { writes++; return result }
    }
}
