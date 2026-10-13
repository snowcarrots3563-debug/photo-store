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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryMediaSelectionTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private var serial = 0
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }

    private fun model(repo: SelectionRepository, media: SelectionMedia, handle: SavedStateHandle = SavedStateHandle()): GalleryViewModel =
        GalleryViewModel(repo, handle, media.repository).also { store.put("selection${serial++}", it) }

    @Test fun longPressAndTapSelectValidItemsWithoutOpeningViewerAndZeroStaysInMode() = runTest(dispatcher) {
        val vm = model(SelectionRepository(), SelectionMedia())
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        vm.onEvent(GalleryEvent.StartMediaSelection("missing"))
        assertFalse(vm.state.value.mediaSelectionMode)
        vm.onEvent(GalleryEvent.StartMediaSelection("first"))
        assertTrue(vm.state.value.mediaSelectionMode)
        assertEquals(setOf("first"), vm.state.value.selectedMediaIds)
        vm.onEvent(GalleryEvent.OpenMedia("second"))
        vm.onEvent(GalleryEvent.ToggleMediaSelection("missing"))
        assertEquals(setOf("first", "second"), vm.state.value.selectedMediaIds)
        assertEquals(GalleryOverlay.None, vm.state.value.overlay)
        vm.onEvent(GalleryEvent.ToggleMediaSelection("first"))
        vm.onEvent(GalleryEvent.ToggleMediaSelection("second"))
        assertTrue(vm.state.value.selectedMediaIds.isEmpty())
        assertTrue(vm.state.value.mediaSelectionMode)
        vm.onEvent(GalleryEvent.OpenDeleteSelectedMedia)
        assertEquals(GalleryDialog.None, vm.state.value.dialog)
    }

    @Test fun existingSelectionForAddingToCollectionDoesNotEnableBulkDelete() = runTest(dispatcher) {
        val vm = model(SelectionRepository(), SelectionMedia())
        vm.onEvent(GalleryEvent.OpenUnassigned)
        runCurrent()
        vm.onEvent(GalleryEvent.ToggleMediaSelection("first"))
        assertEquals(setOf("first"), vm.state.value.selectedMediaIds)
        assertFalse(vm.state.value.mediaSelectionMode)
        vm.onEvent(GalleryEvent.OpenDeleteSelectedMedia)
        assertEquals(GalleryDialog.None, vm.state.value.dialog)
        vm.onEvent(GalleryEvent.OpenAddMediaTarget)
        assertEquals(listOf("first"), vm.state.value.pendingAddAssetIds)
        assertEquals(GalleryDialog.Unavailable("从相册导入", 2), vm.state.value.dialog)
    }

    @Test fun backDismissesDialogThenExitsSelectionBeforeLeavingPage() = runTest(dispatcher) {
        val vm = model(SelectionRepository(), SelectionMedia())
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        vm.onEvent(GalleryEvent.StartMediaSelection("first"))
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.About))
        vm.onEvent(GalleryEvent.NavigateBack)
        assertEquals(GalleryDialog.None, vm.state.value.dialog)
        assertTrue(vm.state.value.mediaSelectionMode)
        vm.onEvent(GalleryEvent.NavigateBack)
        assertFalse(vm.state.value.mediaSelectionMode)
        assertTrue(vm.state.value.selectedMediaIds.isEmpty())
        assertEquals(GalleryRoute.Artist("a"), vm.state.value.route)
        vm.onEvent(GalleryEvent.NavigateBack)
        assertEquals(GalleryRoute.Home, vm.state.value.route)
    }

    @Test fun overlayBackHasPriorityOverExitingSelection() = runTest(dispatcher) {
        val vm = model(SelectionRepository(), SelectionMedia())
        vm.onEvent(GalleryEvent.OpenCollection("c", "a"))
        runCurrent()
        vm.onEvent(GalleryEvent.StartMediaSelection("second"))
        vm.onEvent(GalleryEvent.OpenComic)
        assertEquals(GalleryOverlay.ComicReader, vm.state.value.overlay)
        vm.onEvent(GalleryEvent.NavigateBack)
        assertEquals(GalleryOverlay.None, vm.state.value.overlay)
        assertTrue(vm.state.value.mediaSelectionMode)
        assertEquals(setOf("second"), vm.state.value.selectedMediaIds)
        assertEquals(GalleryRoute.Collection("c", "a"), vm.state.value.route)
    }

    @Test fun confirmationFreezesOriginalIdsAndCancelDoesNotWriteOrClearSelection() = runTest(dispatcher) {
        val repo = SelectionRepository()
        val vm = model(repo, SelectionMedia())
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        vm.onEvent(GalleryEvent.StartMediaSelection("first"))
        vm.onEvent(GalleryEvent.ToggleMediaSelection("second"))
        vm.onEvent(GalleryEvent.OpenDeleteSelectedMedia)
        assertEquals(GalleryDialog.DeleteSelectedMedia(listOf("first", "second")), vm.state.value.dialog)
        vm.onEvent(GalleryEvent.ToggleMediaSelection("second"))
        vm.onEvent(GalleryEvent.ToggleMediaSelection("third"))
        assertEquals(GalleryDialog.DeleteSelectedMedia(listOf("first", "second")), vm.state.value.dialog)
        vm.onEvent(GalleryEvent.NavigateBack)
        vm.onEvent(GalleryEvent.ConfirmDeleteSelectedMedia)
        runCurrent()
        assertEquals(GalleryDialog.None, vm.state.value.dialog)
        assertEquals(setOf("first", "third"), vm.state.value.selectedMediaIds)
        assertTrue(vm.state.value.mediaSelectionMode)
        assertEquals(0, repo.writes)
        assertFalse(vm.state.value.isSaving)
    }

    @Test fun selectionAndFrozenConfirmationSurviveRecreation() = runTest(dispatcher) {
        val repo = SelectionRepository()
        val media = SelectionMedia()
        val handle = SavedStateHandle()
        val vm = model(repo, media, handle)
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        vm.onEvent(GalleryEvent.StartMediaSelection("first"))
        vm.onEvent(GalleryEvent.ToggleMediaSelection("second"))
        vm.onEvent(GalleryEvent.OpenDeleteSelectedMedia)
        val restored = model(repo, media, handle)
        assertEquals(GalleryRoute.Artist("a"), restored.state.value.route)
        assertTrue(restored.state.value.mediaSelectionMode)
        assertEquals(setOf("first", "second"), restored.state.value.selectedMediaIds)
        assertEquals(GalleryDialog.DeleteSelectedMedia(listOf("first", "second")), restored.state.value.dialog)
        runCurrent()
        assertEquals(setOf("first", "second"), restored.state.value.selectedMediaIds)
        restored.onEvent(GalleryEvent.ExitMediaSelection)
        assertEquals(GalleryDialog.None, restored.state.value.dialog)
        assertFalse(restored.state.value.mediaSelectionMode)
        assertTrue(restored.state.value.selectedMediaIds.isEmpty())
    }

    @Test fun layoutKeepsSelectionButTabAndRouteChangesClearItAndOldConfirmation() = runTest(dispatcher) {
        val vm = model(SelectionRepository(), SelectionMedia())
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        vm.onEvent(GalleryEvent.StartMediaSelection("first"))
        vm.onEvent(GalleryEvent.SetImageLayout(ArtistImageLayout.Large))
        assertTrue(vm.state.value.mediaSelectionMode)
        assertEquals(setOf("first"), vm.state.value.selectedMediaIds)
        vm.onEvent(GalleryEvent.SwitchArtistTab(ArtistTab.Collections))
        assertFalse(vm.state.value.mediaSelectionMode)
        assertTrue(vm.state.value.selectedMediaIds.isEmpty())
        vm.onEvent(GalleryEvent.StartMediaSelection("first"))
        assertFalse(vm.state.value.mediaSelectionMode)
        vm.onEvent(GalleryEvent.SwitchArtistTab(ArtistTab.Images))
        vm.onEvent(GalleryEvent.StartMediaSelection("first"))
        vm.onEvent(GalleryEvent.OpenDeleteSelectedMedia)
        vm.onEvent(GalleryEvent.OpenCollection("c", "a"))
        assertFalse(vm.state.value.mediaSelectionMode)
        assertTrue(vm.state.value.selectedMediaIds.isEmpty())
        assertEquals(GalleryDialog.None, vm.state.value.dialog)
        vm.onEvent(GalleryEvent.ConfirmDeleteSelectedMedia)
        assertFalse(vm.state.value.isSaving)
    }

    @Test fun mediaUpdatesPruneMissingSelectionsAndConfirmationUsesOnlyCurrentItems() = runTest(dispatcher) {
        val media = SelectionMedia()
        val vm = model(SelectionRepository(), media)
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        vm.onEvent(GalleryEvent.StartMediaSelection("first"))
        vm.onEvent(GalleryEvent.ToggleMediaSelection("second"))
        media.images.value = listOf(image("second"), image("third"))
        runCurrent()
        assertTrue(vm.state.value.mediaSelectionMode)
        assertEquals(setOf("second"), vm.state.value.selectedMediaIds)
        vm.onEvent(GalleryEvent.OpenDeleteSelectedMedia)
        assertEquals(GalleryDialog.DeleteSelectedMedia(listOf("second")), vm.state.value.dialog)
    }

    @Test fun swipeDeletePreferenceSurvivesRecreationAndPreservesOtherBrowseOptions() = runTest(dispatcher) {
        val original = BrowseOptions(cardEffects = true, playGifs = false, imageLayout = ArtistImageLayout.Medium, swipeDelete = true)
        val preferences = object : BrowsePreferences {
            override val options = MutableStateFlow(original)
            override fun save(options: BrowseOptions) { this.options.value = options }
        }
        val vm = GalleryViewModel(SelectionRepository(), SavedStateHandle(), browsePreferences = preferences).also { store.put("swipe1", it) }
        runCurrent()
        vm.onEvent(GalleryEvent.SetSwipeDelete(false))
        runCurrent()
        assertEquals(original.copy(swipeDelete = false), preferences.options.value)
        assertEquals(original.copy(swipeDelete = false), vm.state.value.browseOptions)
        val recreated = GalleryViewModel(SelectionRepository(), SavedStateHandle(), browsePreferences = preferences).also { store.put("swipe2", it) }
        assertEquals(original.copy(swipeDelete = false), recreated.state.value.browseOptions)
    }

    private class SelectionRepository : GalleryRepository {
        var writes = 0
        override fun observeArtists(): Flow<List<ArtistSummary>> = flowOf(emptyList())
        override fun observeArtist(id: String): Flow<ArtistDetail?> = flowOf(ArtistDetail(ArtistSummary(id, "画师", 1, 3),
            listOf(CollectionSummary("c", id, "作品集", false, 1, 0))))
        override fun observeCollection(id: String): Flow<CollectionSummary?> = flowOf(CollectionSummary(id, "a", "作品集", false, 1, 0))
        private fun write(): SaveResult { writes++; return SaveResult.Saved("unexpected") }
        override suspend fun createArtist(name: String): SaveResult = write()
        override suspend fun renameArtist(id: String, name: String): SaveResult = write()
        override suspend fun createCollection(artistId: String, name: String): SaveResult = write()
        override suspend fun renameCollection(id: String, name: String): SaveResult = write()
    }

    private class SelectionMedia {
        val images = MutableStateFlow(listOf(image("first"), image("second"), image("third")))
        val repository = Proxy.newProxyInstance(MediaLibraryRepository::class.java.classLoader, arrayOf(MediaLibraryRepository::class.java)) { _, method, _ ->
            when (method.name) {
                "observeArtistImages", "observeUnassignedMedia" -> images
                "observeCollectionMedia" -> flowOf(listOf(image("second")))
                "observeLatestImportBatches" -> flowOf(emptyList<ImportBatchSummary>())
                else -> error("此测试不得执行删除或其他媒体写入：${method.name}")
            }
        } as MediaLibraryRepository
    }

    private companion object {
        fun image(id: String) = MediaAssetSummary(id, "$id.png", MediaType.IMAGE, 8, 0, "$id.png", "key-$id")
    }
}
