package dev.local.artistgallery.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.data.BrowsePreferences
import dev.local.artistgallery.data.GalleryRepository
import dev.local.artistgallery.data.MediaLibraryRepository
import dev.local.artistgallery.model.*
import java.lang.reflect.Proxy
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
class GalleryDefaultImportTargetTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private var serial = 0
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    private fun model(repo: TargetRepository = TargetRepository(), handle: SavedStateHandle = SavedStateHandle(),
                      preferences: BrowsePreferences? = null, media: MediaLibraryRepository? = null) =
        GalleryViewModel(repo, handle, media, browsePreferences = preferences).also { store.put("default${serial++}", it) }

    @Test fun defaultTargetLaunchesFilePickerWithoutCreatingACollection() = runTest(dispatcher) {
        val repo = TargetRepository()
        val vm = model(repo)
        val effects = mutableListOf<GalleryEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.ImportTarget("a", null, fromFiles = true, allowDefault = true)))
        vm.onEvent(GalleryEvent.ConfirmImportTarget)
        assertEquals(listOf(GalleryEffect.LaunchMediaPicker(null, true)), effects)
        assertEquals("a", vm.state.value.pendingImportDefaultArtistId)
        assertNull(vm.state.value.pendingImportTargetCollectionId)
        assertEquals(0, repo.ensures)
        assertEquals(GalleryDialog.None, vm.state.value.dialog)
    }

    @Test fun explicitCollectionAndUnassignedEachOverrideDefaultTarget() = runTest(dispatcher) {
        val repo = TargetRepository()
        val vm = model(repo)
        val effects = mutableListOf<GalleryEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.ImportTarget("a", "ordinary", allowDefault = true)))
        vm.onEvent(GalleryEvent.ConfirmImportTarget)
        assertEquals("ordinary", vm.state.value.pendingImportTargetCollectionId)
        assertNull(vm.state.value.pendingImportDefaultArtistId)
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.ImportTarget("a", "ordinary", toUnassigned = true, allowDefault = true)))
        vm.onEvent(GalleryEvent.ConfirmImportTarget)
        assertNull(vm.state.value.pendingImportTargetCollectionId)
        assertNull(vm.state.value.pendingImportDefaultArtistId)
        assertEquals(listOf(GalleryEffect.LaunchMediaPicker("ordinary"), GalleryEffect.LaunchMediaPicker(null)), effects)
        assertEquals(0, repo.ensures)
    }

    @Test fun managementOldArtistStillRequiresExplicitTargetWhileFlaggedDefaultIsOptional() = runTest(dispatcher) {
        val repo = TargetRepository()
        val vm = model(repo)
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.ImportTarget("a", null)))
        vm.onEvent(GalleryEvent.ImportArtistSelected("a"))
        runCurrent()
        vm.onEvent(GalleryEvent.ConfirmImportTarget)
        assertNotNull(vm.state.value.fieldError)
        assertTrue(vm.state.value.dialog is GalleryDialog.ImportTarget)
        repo.detail.value = repo.detail.value!!.copy(collections = listOf(collection("default", isDefault = true)))
        vm.onEvent(GalleryEvent.ImportArtistSelected("a"))
        runCurrent()
        vm.onEvent(GalleryEvent.ConfirmImportTarget)
        assertEquals("a", vm.state.value.pendingImportDefaultArtistId)
        assertEquals(0, repo.ensures)
    }

    @Test fun changingArtistRetainsSourceButDoesNotExtendLazyCreationBeyondCurrentArtist() = runTest(dispatcher) {
        val vm = model()
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.ImportTarget("a", null, fromFiles = true, allowDefault = true)))
        vm.onEvent(GalleryEvent.ImportArtistSelected("b"))
        runCurrent()
        assertEquals(GalleryDialog.ImportTarget("b", null, fromFiles = true), vm.state.value.dialog)
        vm.onEvent(GalleryEvent.ImportArtistSelected("a"))
        runCurrent()
        assertEquals(GalleryDialog.ImportTarget("a", null, fromFiles = true, allowDefault = true), vm.state.value.dialog)
    }

    @Test fun defaultChoiceCanBeRestoredAfterAnExplicitCollectionAndUnassigned() = runTest(dispatcher) {
        val vm = model()
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.ImportTarget("a", null, fromFiles = true, allowDefault = true)))
        vm.onEvent(GalleryEvent.ImportCollectionSelected("ordinary"))
        vm.onEvent(GalleryEvent.ToggleUnassignedImportTarget)
        vm.onEvent(GalleryEvent.ImportCollectionSelected(null))
        vm.onEvent(GalleryEvent.ConfirmImportTarget)
        assertEquals("a", vm.state.value.pendingImportDefaultArtistId)
        assertNull(vm.state.value.pendingImportTargetCollectionId)
    }

    @Test fun newTargetStateAndPendingDefaultSurviveRecreationAndOldStateRemainsCompatible() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val repo = TargetRepository()
        val vm = model(repo, handle)
        val target = GalleryDialog.ImportTarget("a", null, fromFiles = true, allowDefault = true)
        vm.onEvent(GalleryEvent.OpenDialog(target))
        val restored = model(repo, handle)
        assertEquals(target, restored.state.value.dialog)
        restored.onEvent(GalleryEvent.ConfirmImportTarget)
        assertEquals("a", model(repo, handle).state.value.pendingImportDefaultArtistId)
        val old = model(repo, SavedStateHandle(mapOf("dialog" to arrayListOf("importTarget", "a", "ordinary", "false"))))
        assertEquals(GalleryDialog.ImportTarget("a", "ordinary"), old.state.value.dialog)
        old.onEvent(GalleryEvent.ConfirmImportTarget)
        assertEquals("ordinary", old.state.value.pendingImportTargetCollectionId)
        assertNull(old.state.value.pendingImportDefaultArtistId)
    }

    @Test fun cancelTargetAndEmptyPickerResultClearDefaultWithoutCreatingIt() = runTest(dispatcher) {
        val repo = TargetRepository()
        val vm = model(repo)
        val target = GalleryDialog.ImportTarget("a", null, allowDefault = true)
        vm.onEvent(GalleryEvent.OpenDialog(target))
        vm.onEvent(GalleryEvent.DismissDialog)
        assertNull(vm.state.value.pendingImportDefaultArtistId)
        vm.onEvent(GalleryEvent.OpenDialog(target))
        vm.onEvent(GalleryEvent.ConfirmImportTarget)
        vm.onMediaPicked(emptyList())
        assertNull(vm.state.value.pendingImportDefaultArtistId)
        assertNull(vm.state.value.pendingImportTargetCollectionId)
        assertTrue(vm.state.value.importCandidates.isEmpty())
        assertEquals(0, repo.ensures)
    }

    @Test fun restoredPreviewKeepsCandidatesAndDefaultUntilDismissal() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf("dialog" to arrayListOf("importPreview"),
            "pendingImportDefaultArtistId" to "a", "importPreviewId" to "preview-key",
            "importCandidates" to arrayListOf(arrayListOf("content://neutral/image.png", "image.png", "IMAGE", "", ""))))
        val vm = model(handle = handle)
        assertEquals(GalleryDialog.ImportPreview, vm.state.value.dialog)
        assertEquals(ImportCandidate("content://neutral/image.png", "image.png", MediaType.IMAGE), vm.state.value.importCandidates.single())
        val restoredKey = vm.importPermissionActionKey(GalleryEvent.ConfirmImport)
        assertNotNull(restoredKey)
        assertNotEquals("preview-key", restoredKey)
        assertFalse(handle.contains("importPreviewId"))
        vm.continueImportAfterPermission(GalleryEvent.ConfirmImport, "preview-key")
        assertEquals(GalleryDialog.ImportPreview, vm.state.value.dialog)
        assertEquals("a", vm.state.value.pendingImportDefaultArtistId)
        assertEquals(restoredKey, vm.importPermissionActionKey(GalleryEvent.ConfirmImport))
        vm.onEvent(GalleryEvent.DismissDialog)
        assertNull(vm.state.value.pendingImportDefaultArtistId)
        assertTrue(vm.state.value.importCandidates.isEmpty())
        assertNull(vm.importPermissionActionKey(GalleryEvent.ConfirmImport))
    }

    @Test fun addingExistingMediaStillRequiresAnExplicitCollection() = runTest(dispatcher) {
        val repo = TargetRepository()
        val media = Proxy.newProxyInstance(MediaLibraryRepository::class.java.classLoader, arrayOf(MediaLibraryRepository::class.java)) { _, method, _ ->
            when (method.name) {
                "observeArtistImages" -> flowOf(listOf(MediaAssetSummary("image", "image.png", MediaType.IMAGE, 6000, 0, "image.png", "key")))
                "observeLatestImportBatches" -> flowOf(emptyList<ImportBatchSummary>())
                else -> error("Unexpected media method ${method.name}")
            }
        } as MediaLibraryRepository
        val vm = model(repo, media = media)
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        vm.onEvent(GalleryEvent.ToggleMediaSelection("image"))
        vm.onEvent(GalleryEvent.OpenAddMediaTarget)
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.ImportTarget("a", null, allowDefault = true)))
        vm.onEvent(GalleryEvent.ConfirmImportTarget)
        assertEquals("请选择一个作品集。", vm.state.value.fieldError)
        assertNull(vm.state.value.pendingImportDefaultArtistId)
        assertEquals(0, repo.ensures)
    }

    @Test fun defaultCollectionCannotStartOrToggleSelectionOrEnterDeleteConfirmation() = runTest(dispatcher) {
        val repo = TargetRepository(listOf(collection("default", isDefault = true), collection("ordinary")))
        val vm = model(repo)
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        vm.onEvent(GalleryEvent.SwitchArtistTab(ArtistTab.Collections))
        vm.onEvent(GalleryEvent.StartEntitySelection(EntitySelectionKind.Collections, "default"))
        assertNull(vm.state.value.entitySelectionKind)
        vm.onEvent(GalleryEvent.StartEntitySelection(EntitySelectionKind.Collections, "ordinary"))
        vm.onEvent(GalleryEvent.ToggleEntitySelection("default"))
        vm.onEvent(GalleryEvent.OpenDeleteSelectedEntities)
        assertEquals(setOf("ordinary"), vm.state.value.selectedEntityIds)
        assertEquals(GalleryDialog.DeleteCollections(listOf("ordinary")), vm.state.value.dialog)
    }

    @Test fun defaultFlagsPruneRestoredSelectionAndOrderStillIncludesDefault() = runTest(dispatcher) {
        val repo = TargetRepository(listOf(collection("default", isDefault = true), collection("ordinary")))
        val handle = SavedStateHandle(mapOf("route" to arrayListOf("artist", "a"), "tab" to "Collections",
            "entitySelectionKind" to "Collections", "selectedEntityIds" to arrayListOf("default", "ordinary")))
        val preferences = object : BrowsePreferences {
            override val options = MutableStateFlow(BrowseOptions())
            override fun save(options: BrowseOptions) { this.options.value = options }
        }
        val vm = model(repo, handle, preferences)
        runCurrent()
        assertEquals(setOf("ordinary"), vm.state.value.selectedEntityIds)
        vm.onEvent(GalleryEvent.ReorderCollections("a", listOf("ordinary", "default")))
        assertEquals(listOf("ordinary", "default"), preferences.options.value.collectionOrders["a"])
        vm.onEvent(GalleryEvent.OpenCollection("default", "a"))
        assertEquals(GalleryRoute.Collection("default", "a"), vm.state.value.route)
        assertNull(vm.state.value.entitySelectionKind)
    }

    private class TargetRepository(collections: List<CollectionSummary> = emptyList()) : GalleryRepository {
        val detail = MutableStateFlow<ArtistDetail?>(ArtistDetail(ArtistSummary("a", "画师", collections.size, 0), collections))
        var ensures = 0
        override fun observeArtists() = flowOf(listOf(ArtistSummary("a", "画师", 0, 0), ArtistSummary("b", "旧画师", 0, 0)))
        override fun observeArtist(id: String): Flow<ArtistDetail?> = if (id == "a") detail else flowOf(ArtistDetail(ArtistSummary(id, "旧画师", 0, 0), emptyList()))
        override fun observeCollection(id: String) = flowOf(detail.value?.collections?.firstOrNull { it.id == id })
        override suspend fun ensureDefaultCollection(artistId: String): SaveResult { ensures++; return SaveResult.Saved("default") }
        override suspend fun createArtist(name: String): SaveResult = error("Unexpected write")
        override suspend fun renameArtist(id: String, name: String): SaveResult = error("Unexpected write")
        override suspend fun createCollection(artistId: String, name: String): SaveResult = error("Unexpected write")
        override suspend fun renameCollection(id: String, name: String): SaveResult = error("Unexpected write")
    }

    companion object {
        private fun collection(id: String, isDefault: Boolean = false) = CollectionSummary(id, "a", id, false, 0, 0, isDefault = isDefault)
    }
}
