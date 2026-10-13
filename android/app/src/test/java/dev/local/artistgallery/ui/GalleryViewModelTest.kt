package dev.local.artistgallery.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.local.artistgallery.data.GalleryRepository
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.data.ArtistImageLayout
import dev.local.artistgallery.data.BrowsePreferences
import dev.local.artistgallery.data.MediaLibraryRepository
import java.lang.reflect.Proxy
import dev.local.artistgallery.model.*
import kotlinx.coroutines.CompletableDeferred
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
class GalleryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private var serial = 0
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    private fun model(repository: FakeRepository, handle: SavedStateHandle = SavedStateHandle()): GalleryViewModel =
        GalleryViewModel(repository, handle).also { store.put("vm${serial++}", it) }

    @Test fun browseDefaultsAndChangesSurviveRecreationWithoutChangingGallery() = runTest(dispatcher) {
        val preferences = object : BrowsePreferences {
            override val options = MutableStateFlow(BrowseOptions())
            override fun save(options: BrowseOptions) { this.options.value = options }
        }
        val repo = FakeRepository()
        val vm = GalleryViewModel(repo, SavedStateHandle(), browsePreferences = preferences).also { store.put("settings1", it) }
        assertFalse(vm.state.value.browseOptions.cardEffects)
        assertTrue(vm.state.value.browseOptions.playGifs)
        vm.onEvent(GalleryEvent.SetCardEffects(true))
        vm.onEvent(GalleryEvent.SetPlayGifs(false))
        runCurrent()
        val restored = GalleryViewModel(repo, SavedStateHandle(), browsePreferences = preferences).also { store.put("settings2", it) }
        assertEquals(BrowseOptions(true, false), restored.state.value.browseOptions)
        restored.onEvent(GalleryEvent.SetPlayGifs(true))
        runCurrent()
        assertEquals(BrowseOptions(true, true), vm.state.value.browseOptions)
        assertEquals(0, repo.writes)
    }

    @Test fun restoredDeleteConfirmationCanBeCancelledWithoutWriting() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = model(repo, SavedStateHandle(mapOf("dialog" to arrayListOf("deleteMedia", "shared", "共享图片.png"))))
        assertEquals(GalleryDialog.DeleteMedia("shared", "共享图片.png"), vm.state.value.dialog)
        vm.onEvent(GalleryEvent.DismissDialog)
        advanceUntilIdle()
        assertEquals(GalleryDialog.None, vm.state.value.dialog)
        assertEquals(0, repo.writes)
    }

    @Test fun artistRenameRejectsBlankAndPreservesRouteWithSavedDraft() = runTest(dispatcher) {
        val repo = FakeRepository().apply { result = SaveResult.Saved("a") }
        val handle = SavedStateHandle()
        val vm = model(repo, handle)
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.RenameArtist("a", "画师")))
        vm.onEvent(GalleryEvent.DraftChanged("  "))
        vm.onEvent(GalleryEvent.Submit)
        assertEquals(0, repo.writes)
        assertNotNull(vm.state.value.fieldError)
        vm.onEvent(GalleryEvent.DraftChanged("新名字"))
        val restored = model(repo, handle)
        assertEquals(GalleryDialog.RenameArtist("a", "新名字"), restored.state.value.dialog)
        restored.onEvent(GalleryEvent.Submit)
        advanceUntilIdle()
        assertEquals(1, repo.writes)
        assertEquals("a", repo.lastId)
        assertEquals("新名字", repo.lastName)
        assertEquals(GalleryRoute.Artist("a"), restored.state.value.route)
        assertEquals(GalleryDialog.None, restored.state.value.dialog)
    }

    @Test fun restoredArtistDeleteRequiresConfirmationAndCancelDoesNotWrite() = runTest(dispatcher) {
        val repo = FakeRepository()
        val vm = model(repo, SavedStateHandle(mapOf("dialog" to arrayListOf("deleteArtist", "a", "共享画师"))))
        assertEquals(GalleryDialog.DeleteArtist("a", "共享画师"), vm.state.value.dialog)
        vm.onEvent(GalleryEvent.DismissDialog)
        advanceUntilIdle()
        assertEquals(0, repo.writes)
        assertEquals(GalleryDialog.None, vm.state.value.dialog)
    }

    @Test fun displayChoicePersistsWithoutChangingExistingBrowseSettingsOrGallery() = runTest(dispatcher) {
        val prefs = object : BrowsePreferences {
            override val options = MutableStateFlow(BrowseOptions(true, false))
            override fun save(options: BrowseOptions) { this.options.value = options }
        }
        val repo = FakeRepository()
        val vm = GalleryViewModel(repo, SavedStateHandle(), browsePreferences = prefs).also { store.put("layout1", it) }
        assertEquals(ArtistImageLayout.List, vm.state.value.browseOptions.imageLayout)
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.ImageDisplayMenu))
        vm.onEvent(GalleryEvent.SetImageLayout(ArtistImageLayout.Medium))
        runCurrent()
        val restored = GalleryViewModel(repo, SavedStateHandle(), browsePreferences = prefs).also { store.put("layout2", it) }
        assertEquals(BrowseOptions(true, false, ArtistImageLayout.Medium), restored.state.value.browseOptions)
        assertEquals(GalleryDialog.None, vm.state.value.dialog)
        assertEquals(0, repo.writes)
    }

    @Test fun scrollCoordinatesSurviveCollectionRoundTripAndRecreation() = runTest(dispatcher) {
        val repo = FakeRepository()
        val handle = SavedStateHandle()
        val vm = model(repo, handle)
        val artistKey = "artist:a:Collections:List"
        val imageKey = "artist:a:Images:Medium:Default"
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        vm.onEvent(GalleryEvent.SwitchArtistTab(ArtistTab.Collections))
        vm.onEvent(GalleryEvent.RememberScrollPosition(artistKey, 3, 701))
        vm.onEvent(GalleryEvent.RememberScrollPosition(imageKey, 18, 23))
        vm.onEvent(GalleryEvent.OpenCollection("c", "a"))
        vm.onEvent(GalleryEvent.RememberScrollPosition("collection:c", 27, 35))
        vm.onEvent(GalleryEvent.NavigateBack)
        val restored = model(repo, handle)
        advanceUntilIdle()
        assertEquals(GalleryRoute.Artist("a"), restored.state.value.route)
        assertEquals(GalleryScrollPosition(3, 701), restored.state.value.scrollPositions[artistKey])
        assertEquals(GalleryScrollPosition(18, 23), restored.state.value.scrollPositions[imageKey])
        assertEquals(GalleryScrollPosition(27, 35), restored.state.value.scrollPositions["collection:c"])
        assertEquals(0, repo.writes)
    }

    @Test fun detailReadinessDoesNotReleaseScrollRestorationBeforeFirstMediaEmission() = runTest(dispatcher) {
        val images = MutableSharedFlow<List<MediaAssetSummary>>()
        val vm = GalleryViewModel(FakeRepository(), SavedStateHandle(), mediaRepository = mediaSource(images)).also { store.put("mediaWait", it) }
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        vm.onEvent(GalleryEvent.RememberScrollPosition("artist:a:Images:List:Default", 22, 45))
        runCurrent()
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        assertEquals(LoadState.Loading, vm.state.value.mediaLoadState)
        assertEquals(GalleryScrollPosition(22, 45), vm.state.value.scrollPositions["artist:a:Images:List:Default"])
        images.emit(emptyList())
        runCurrent()
        assertEquals(LoadState.Ready, vm.state.value.mediaLoadState)
    }

    @Test fun laterMediaEmissionCannotHideMissingArtistFailure() = runTest(dispatcher) {
        val images = MutableSharedFlow<List<MediaAssetSummary>>()
        val repo = FakeRepository().apply { detailFlow = flowOf(null) }
        val vm = GalleryViewModel(repo, SavedStateHandle(), mediaRepository = mediaSource(images)).also { store.put("mediaMissing", it) }
        vm.onEvent(GalleryEvent.OpenArtist("missing"))
        runCurrent()
        assertEquals(LoadState.Failed, vm.state.value.loadState)
        images.emit(emptyList())
        runCurrent()
        assertEquals(LoadState.Ready, vm.state.value.mediaLoadState)
        assertEquals(LoadState.Failed, vm.state.value.loadState)
    }

    private fun mediaSource(images: Flow<List<MediaAssetSummary>>): MediaLibraryRepository = Proxy.newProxyInstance(
        MediaLibraryRepository::class.java.classLoader, arrayOf(MediaLibraryRepository::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "observeArtistImages", "observeCollectionMedia", "observeUnassignedMedia" -> images
            "observeLatestImportBatches" -> flowOf(emptyList<ImportBatchSummary>())
            else -> error("测试不应调用 ${method.name}")
        }
    } as MediaLibraryRepository

    private fun sortImages() = listOf(
        MediaAssetSummary("ten", "10.png", MediaType.IMAGE, 300, 0, "10.png", "ten", 30),
        MediaAssetSummary("one", "1.png", MediaType.IMAGE, 200, 1, "1.png", "one", 10),
        MediaAssetSummary("two", "2.png", MediaType.GIF, 100, 2, "2.png", "two", 20),
    )

    private class SortPreferences(initial: BrowseOptions = BrowseOptions()) : BrowsePreferences {
        override val options = MutableStateFlow(initial)
        var saves = 0
        override fun save(options: BrowseOptions) { saves++; this.options.value = options }
    }

    @Test fun artistSortsAreIndependentAndSurviveViewModelRecreation() = runTest(dispatcher) {
        val preferences = SortPreferences()
        val repository = FakeRepository()
        val vm = GalleryViewModel(repository, SavedStateHandle(), mediaSource(flowOf(sortImages())), browsePreferences = preferences)
            .also { store.put("sortIndependent", it) }
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        assertEquals(listOf("one", "two", "ten"), vm.state.value.mediaItems.map { it.id })
        vm.onEvent(GalleryEvent.SetArtistImageSort(MediaSort.SizeAscending))
        assertEquals(listOf("two", "one", "ten"), vm.state.value.mediaItems.map { it.id })
        vm.onEvent(GalleryEvent.OpenArtist("b"))
        runCurrent()
        assertEquals(listOf("one", "two", "ten"), vm.state.value.mediaItems.map { it.id })
        vm.onEvent(GalleryEvent.SetArtistImageSort(MediaSort.NameDescending))
        runCurrent()
        assertEquals(mapOf("a" to MediaSort.SizeAscending, "b" to MediaSort.NameDescending), preferences.options.value.artistImageSorts)
        val restored = GalleryViewModel(repository, SavedStateHandle(mapOf("route" to arrayListOf("artist", "a"))),
            mediaSource(flowOf(sortImages())), browsePreferences = preferences).also { store.put("sortRestored", it) }
        runCurrent()
        assertEquals(listOf("two", "one", "ten"), restored.state.value.mediaItems.map { it.id })
        assertEquals(0, repository.writes)
    }

    @Test fun mediaFlowChangesKeepSelectedSortAndDefaultRestoresOriginalOrder() = runTest(dispatcher) {
        val images = MutableStateFlow(sortImages())
        val vm = GalleryViewModel(FakeRepository(), SavedStateHandle(), mediaSource(images)).also { store.put("sortFlow", it) }
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        vm.onEvent(GalleryEvent.SetArtistImageSort(MediaSort.AddedDescending))
        assertEquals(listOf("ten", "two", "one"), vm.state.value.mediaItems.map { it.id })
        images.value = listOf(sortImages()[1], sortImages()[2].copy(id = "new", createdAt = 40))
        runCurrent()
        assertEquals(listOf("new", "one"), vm.state.value.mediaItems.map { it.id })
        vm.onEvent(GalleryEvent.SetArtistImageSort(MediaSort.Default))
        assertEquals(listOf("one", "new"), vm.state.value.mediaItems.map { it.id })
    }

    @Test fun preferencesReorderParentArtistCacheBeforeReturningMediaFlowEmits() = runTest(dispatcher) {
        val preferences = SortPreferences()
        val images = MutableSharedFlow<List<MediaAssetSummary>>()
        val vm = GalleryViewModel(FakeRepository(), SavedStateHandle(), mediaSource(images), browsePreferences = preferences)
            .also { store.put("sortParent", it) }
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        images.emit(sortImages())
        runCurrent()
        vm.onEvent(GalleryEvent.OpenCollection("c", "a"))
        runCurrent()
        preferences.save(BrowseOptions(artistImageSorts = mapOf("a" to MediaSort.SizeAscending)))
        runCurrent()
        vm.onEvent(GalleryEvent.NavigateBack)
        assertEquals(LoadState.Ready, vm.state.value.mediaLoadState)
        assertEquals(listOf("two", "one", "ten"), vm.state.value.mediaItems.map { it.id })
        runCurrent()
        images.emit(sortImages())
        runCurrent()
        assertEquals(listOf("two", "one", "ten"), vm.state.value.mediaItems.map { it.id })
    }

    @Test fun viewerStepsUseArtistSortAndSortingIsBlockedWhileViewerIsOpen() = runTest(dispatcher) {
        val vm = GalleryViewModel(FakeRepository(), SavedStateHandle(), mediaSource(flowOf(sortImages())))
            .also { store.put("sortViewer", it) }
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        vm.onEvent(GalleryEvent.SetArtistImageSort(MediaSort.SizeAscending))
        vm.onEvent(GalleryEvent.OpenMedia("two"))
        vm.onEvent(GalleryEvent.SetArtistImageSort(MediaSort.NameDescending))
        vm.onEvent(GalleryEvent.StepViewer(1))
        assertEquals(GalleryOverlay.CardViewer("one"), vm.state.value.overlay)
        vm.onEvent(GalleryEvent.StepViewer(1))
        assertEquals(GalleryOverlay.CardViewer("ten"), vm.state.value.overlay)
        assertEquals(MediaSort.SizeAscending, vm.state.value.browseOptions.artistImageSorts["a"])
    }

    @Test fun artistSortDoesNotChangeCollectionUnassignedOrCollectionTabEvents() = runTest(dispatcher) {
        val preferences = SortPreferences(BrowseOptions(artistImageSorts = mapOf("a" to MediaSort.SizeAscending)))
        val vm = GalleryViewModel(FakeRepository(), SavedStateHandle(), mediaSource(flowOf(sortImages())), browsePreferences = preferences)
            .also { store.put("sortRoutes", it) }
        vm.onEvent(GalleryEvent.OpenCollection("c", "a"))
        runCurrent()
        assertEquals(sortImages(), vm.state.value.mediaItems)
        vm.onEvent(GalleryEvent.SetArtistImageSort(MediaSort.NameDescending))
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.ArtistImageSort))
        assertEquals(GalleryDialog.None, vm.state.value.dialog)
        vm.onEvent(GalleryEvent.OpenUnassigned)
        runCurrent()
        assertEquals(sortImages(), vm.state.value.mediaItems)
        vm.onEvent(GalleryEvent.SetArtistImageSort(MediaSort.AddedDescending))
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        vm.onEvent(GalleryEvent.SwitchArtistTab(ArtistTab.Collections))
        vm.onEvent(GalleryEvent.SetArtistImageSort(MediaSort.NameAscending))
        assertEquals(0, preferences.saves)
    }

    @Test fun selectionAndSavingRejectArtistSortWithoutChangingPreferences() = runTest(dispatcher) {
        val preferences = SortPreferences()
        val repository = FakeRepository().apply { gate = CompletableDeferred() }
        val vm = GalleryViewModel(repository, SavedStateHandle(), mediaSource(flowOf(sortImages())), browsePreferences = preferences)
            .also { store.put("sortGuards", it) }
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        runCurrent()
        vm.onEvent(GalleryEvent.StartMediaSelection("one"))
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.ArtistImageSort))
        vm.onEvent(GalleryEvent.SetArtistImageSort(MediaSort.SizeDescending))
        assertEquals(GalleryDialog.None, vm.state.value.dialog)
        assertEquals(0, preferences.saves)
        vm.onEvent(GalleryEvent.ExitMediaSelection)
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.CreateCollection("a", "新作品集")))
        vm.onEvent(GalleryEvent.Submit)
        runCurrent()
        assertTrue(vm.state.value.isSaving)
        vm.onEvent(GalleryEvent.SetArtistImageSort(MediaSort.NameDescending))
        assertEquals(0, preferences.saves)
        repository.gate!!.complete(Unit)
        runCurrent()
    }

    @Test fun artistSortDialogRestoresAndCancelDoesNotSaveAChoice() = runTest(dispatcher) {
        val preferences = SortPreferences()
        val repository = FakeRepository()
        val handle = SavedStateHandle()
        val vm = GalleryViewModel(repository, handle, browsePreferences = preferences).also { store.put("sortDialog", it) }
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.ArtistImageSort))
        val restored = GalleryViewModel(repository, handle, browsePreferences = preferences).also { store.put("sortDialogRestored", it) }
        assertEquals(GalleryDialog.ArtistImageSort, restored.state.value.dialog)
        restored.onEvent(GalleryEvent.NavigateBack)
        assertEquals(GalleryRoute.Artist("a"), restored.state.value.route)
        assertEquals(GalleryDialog.None, restored.state.value.dialog)
        assertEquals(0, preferences.saves)
        val invalid = model(repository, SavedStateHandle(mapOf("route" to arrayListOf("collection", "c", "a"),
            "dialog" to arrayListOf("artistImageSort"))))
        assertEquals(GalleryDialog.None, invalid.state.value.dialog)
    }

    @Test fun newArtistStartsWithDefaultSortAndKeepsExistingArtistChoices() = runTest(dispatcher) {
        val preferences = SortPreferences(BrowseOptions(artistImageSorts = mapOf("a" to MediaSort.NameDescending)))
        val vm = GalleryViewModel(FakeRepository(), SavedStateHandle(), browsePreferences = preferences).also { store.put("sortNew", it) }
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.AddArtist("新画师")))
        vm.onEvent(GalleryEvent.Submit)
        runCurrent()
        assertEquals(mapOf("a" to MediaSort.NameDescending, "new" to MediaSort.Default), preferences.options.value.artistImageSorts)
        assertEquals(ArtistImageLayout.List, preferences.options.value.artistLayouts["new"])
    }

    @Test fun switchingAwayFromImagesClosesSortDialogAndCannotSaveHiddenChoice() = runTest(dispatcher) {
        val preferences = SortPreferences()
        val vm = GalleryViewModel(FakeRepository(), SavedStateHandle(), browsePreferences = preferences).also { store.put("sortTab", it) }
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.ArtistImageSort))
        assertEquals(GalleryDialog.ArtistImageSort, vm.state.value.dialog)
        vm.onEvent(GalleryEvent.SwitchArtistTab(ArtistTab.Collections))
        assertEquals(GalleryDialog.None, vm.state.value.dialog)
        vm.onEvent(GalleryEvent.SetArtistImageSort(MediaSort.NameDescending))
        assertEquals(0, preferences.saves)
    }

    @Test fun fileSourceSurvivesTargetChangesAndRecreationThenLaunchesFilePicker() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf("dialog" to arrayListOf("importTarget", "a", "c", "false", "true")))
        val vm = model(FakeRepository(), handle)
        vm.onEvent(GalleryEvent.ImportArtistSelected("b"))
        advanceUntilIdle()
        val restored = model(FakeRepository(), handle)
        assertEquals(GalleryDialog.ImportTarget("b", null, false, true), restored.state.value.dialog)
        val effects = mutableListOf<GalleryEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { restored.effects.toList(effects) }
        restored.onEvent(GalleryEvent.ToggleUnassignedImportTarget)
        restored.onEvent(GalleryEvent.ConfirmImportTarget)
        runCurrent()
        assertEquals(listOf(GalleryEffect.LaunchMediaPicker(null, true)), effects)
        assertEquals(GalleryDialog.None, restored.state.value.dialog)
    }

    @Test fun olderImportTargetStateStillLaunchesAlbumPickerWithItsCollection() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf("dialog" to arrayListOf("importTarget", "a", "c", "false")))
        val vm = model(FakeRepository(), handle)
        val effects = mutableListOf<GalleryEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        vm.onEvent(GalleryEvent.ConfirmImportTarget)
        runCurrent()
        assertEquals(listOf(GalleryEffect.LaunchMediaPicker("c", false)), effects)
        assertEquals("c", vm.state.value.pendingImportTargetCollectionId)
    }

    @Test fun blankArtistAndCancelledDraftDoNotWrite() = runTest(dispatcher) {
        val repo = FakeRepository(); val vm = model(repo)
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.AddArtist("  ")))
        vm.onEvent(GalleryEvent.Submit)
        assertNotNull(vm.state.value.fieldError)
        vm.onEvent(GalleryEvent.DraftChanged("松风"))
        vm.onEvent(GalleryEvent.DismissDialog)
        advanceUntilIdle()
        assertEquals(0, repo.writes)
        assertEquals(GalleryDialog.None, vm.state.value.dialog)
    }

    @Test fun savingRejectsDoubleSubmitAndDismissThenEmitsOnce() = runTest(dispatcher) {
        val repo = FakeRepository().apply { gate = CompletableDeferred() }
        val vm = model(repo)
        val events = mutableListOf<GalleryFeedback>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.feedback.toList(events) }
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.AddArtist("松风")))
        vm.onEvent(GalleryEvent.Submit)
        vm.onEvent(GalleryEvent.Submit)
        vm.onEvent(GalleryEvent.DismissDialog)
        vm.onEvent(GalleryEvent.NavigateBack)
        runCurrent()
        assertTrue(vm.state.value.isSaving)
        assertTrue(vm.state.value.dialog is GalleryDialog.AddArtist)
        assertEquals(1, repo.writes)
        assertTrue(events.isEmpty())
        repo.gate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.isSaving)
        assertEquals(GalleryDialog.None, vm.state.value.dialog)
        assertEquals(listOf(GalleryFeedback.Saved("已添加画师", "new")), events)
        val later = mutableListOf<GalleryFeedback>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.feedback.toList(later) }
        runCurrent()
        assertTrue(later.isEmpty())
    }

    @Test fun storageFailureKeepsDraftAndRetryCanSave() = runTest(dispatcher) {
        val repo = FakeRepository().apply { result = SaveResult.StorageFailure }
        val vm = model(repo)
        val events = mutableListOf<GalleryFeedback>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.feedback.toList(events) }
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.AddArtist("  松风  ")))
        vm.onEvent(GalleryEvent.Submit)
        advanceUntilIdle()
        assertEquals(GalleryDialog.AddArtist("  松风  "), vm.state.value.dialog)
        assertEquals("保存失败，请重试。内容尚未保存。", vm.state.value.fieldError)
        assertTrue(events.isEmpty())
        repo.result = SaveResult.Saved("new")
        vm.onEvent(GalleryEvent.Submit)
        advanceUntilIdle()
        assertEquals(2, repo.writes)
        assertEquals(1, events.size)
    }

    @Test fun readFailureIsNotEmptyAndCanRetry() = runTest(dispatcher) {
        val repo = FakeRepository().apply { readFails = true }
        val vm = model(repo)
        advanceUntilIdle()
        assertEquals(LoadState.Failed, vm.state.value.loadState)
        repo.readFails = false
        repo.artists.value = listOf(ArtistSummary("a", "保留内容", 0, 0))
        vm.onEvent(GalleryEvent.RetryLoad)
        advanceUntilIdle()
        assertEquals(LoadState.Ready, vm.state.value.loadState)
        assertEquals("保留内容", vm.state.value.artists.single().name)
    }

    @Test fun savedStateRestoresQueryRouteTabAndDraftWithoutSuccess() = runTest(dispatcher) {
        val handle = SavedStateHandle(); val repo = FakeRepository(); val vm = model(repo, handle)
        vm.onEvent(GalleryEvent.SearchChanged(" 墨 "))
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        vm.onEvent(GalleryEvent.SwitchArtistTab(ArtistTab.Collections))
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.CreateCollection("a", "草稿")))
        val restored = model(repo, handle)
        assertEquals(" 墨 ", restored.state.value.searchQuery)
        assertEquals(GalleryRoute.Artist("a"), restored.state.value.route)
        assertEquals(ArtistTab.Collections, restored.state.value.artistTab)
        assertEquals(GalleryDialog.CreateCollection("a", "草稿"), restored.state.value.dialog)
        assertFalse(restored.state.value.isSaving)
        assertEquals(0, repo.writes)
    }

    @Test fun immediateFirstReadFailureRemainsUntilExplicitRetry() = runTest(dispatcher) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeRepository().apply { readFailuresRemaining = 1 }
        val vm = model(repo)
        assertEquals(1, repo.readSubscriptions)
        assertEquals(LoadState.Failed, vm.state.value.loadState)
        advanceUntilIdle()
        assertEquals(1, repo.readSubscriptions)
        assertEquals(LoadState.Failed, vm.state.value.loadState)
        vm.onEvent(GalleryEvent.RetryLoad)
        assertEquals(2, repo.readSubscriptions)
        assertEquals(LoadState.Ready, vm.state.value.loadState)
    }

    @Test fun hiddenNewArtistClearsSearchAndCollectionsBackSelectsCollections() = runTest(dispatcher) {
        val repo = FakeRepository(); val vm = model(repo)
        vm.onEvent(GalleryEvent.SearchChanged("墨"))
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.AddArtist("松风")))
        vm.onEvent(GalleryEvent.Submit)
        advanceUntilIdle()
        assertEquals("", vm.state.value.searchQuery)
        vm.onEvent(GalleryEvent.OpenCollection("c", "a"))
        vm.onEvent(GalleryEvent.NavigateBack)
        assertEquals(GalleryRoute.Artist("a"), vm.state.value.route)
        assertEquals(ArtistTab.Collections, vm.state.value.artistTab)
        vm.onEvent(GalleryEvent.OpenArtist("a"))
        assertEquals(ArtistTab.Images, vm.state.value.artistTab)
    }

    @Test fun duplicateCollectionRetainsDraftAndBlankRenameReachesRepository() = runTest(dispatcher) {
        val repo = FakeRepository().apply { result = SaveResult.DuplicateCollectionName }
        val vm = model(repo)
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.CreateCollection("a", "已有名称")))
        vm.onEvent(GalleryEvent.Submit)
        advanceUntilIdle()
        assertEquals("该画师已有同名作品集，请换一个名称。", vm.state.value.fieldError)
        assertEquals(GalleryDialog.CreateCollection("a", "已有名称"), vm.state.value.dialog)
        vm.onEvent(GalleryEvent.DismissDialog)
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.RenameCollection("c", "")))
        repo.result = SaveResult.Saved("c")
        vm.onEvent(GalleryEvent.Submit)
        advanceUntilIdle()
        assertEquals("", repo.lastName)
        assertEquals("c", repo.lastId)
        assertEquals(GalleryDialog.None, vm.state.value.dialog)
    }

    @Test fun collectionSortsRemainIndependentAndRestoreWithTheirRoute() = runTest(dispatcher) {
        val preferences = SortPreferences()
        val repository = FakeRepository()
        val vm = GalleryViewModel(repository, SavedStateHandle(), mediaSource(flowOf(sortImages())), browsePreferences = preferences)
            .also { store.put("collectionSorts", it) }
        vm.onEvent(GalleryEvent.OpenCollection("c", "a")); runCurrent()
        assertEquals(listOf("ten", "one", "two"), vm.state.value.mediaItems.map { it.id })
        vm.onEvent(GalleryEvent.SetCollectionMediaSort(MediaSort.SizeAscending))
        assertEquals(listOf("two", "one", "ten"), vm.state.value.mediaItems.map { it.id })
        vm.onEvent(GalleryEvent.OpenCollection("other", "a")); runCurrent()
        assertEquals(listOf("ten", "one", "two"), vm.state.value.mediaItems.map { it.id })
        vm.onEvent(GalleryEvent.SetCollectionMediaSort(MediaSort.NameDescending)); runCurrent()
        assertEquals(mapOf("c" to MediaSort.SizeAscending, "other" to MediaSort.NameDescending), preferences.options.value.collectionMediaSorts)
        val restored = GalleryViewModel(repository, SavedStateHandle(mapOf("route" to arrayListOf("collection", "c", "a"))),
            mediaSource(flowOf(sortImages())), browsePreferences = preferences).also { store.put("collectionRestored", it) }
        runCurrent()
        assertEquals(listOf("two", "one", "ten"), restored.state.value.mediaItems.map { it.id })
        assertEquals(0, repository.writes)
    }

    @Test fun collectionSortAcceptsFlowChangesAndExternalChoicesThenReturnsToManualOrder() = runTest(dispatcher) {
        val images = MutableStateFlow(sortImages())
        val preferences = SortPreferences()
        val vm = GalleryViewModel(FakeRepository(), SavedStateHandle(), mediaSource(images), browsePreferences = preferences)
            .also { store.put("collectionFlow", it) }
        vm.onEvent(GalleryEvent.OpenCollection("c", "a")); runCurrent()
        preferences.save(BrowseOptions(collectionMediaSorts = mapOf("c" to MediaSort.AddedDescending))); runCurrent()
        assertEquals(listOf("ten", "two", "one"), vm.state.value.mediaItems.map { it.id })
        images.value = listOf(sortImages()[1], sortImages()[2].copy(id = "new", createdAt = 40)); runCurrent()
        assertEquals(listOf("new", "one"), vm.state.value.mediaItems.map { it.id })
        vm.onEvent(GalleryEvent.SetCollectionMediaSort(MediaSort.Default))
        assertEquals(listOf("one", "new"), vm.state.value.mediaItems.map { it.id })
    }

    @Test fun collectionViewerFollowsSortedImagesSkipsVideoAndKeepsComicDirectionSeparate() = runTest(dispatcher) {
        val preferences = SortPreferences()
        val items = sortImages().map { if (it.id == "two") it.copy(type = MediaType.VIDEO) else it }
        val vm = GalleryViewModel(FakeRepository(), SavedStateHandle(), mediaSource(flowOf(items)), browsePreferences = preferences)
            .also { store.put("collectionViewer", it) }
        vm.onEvent(GalleryEvent.OpenCollection("c", "a")); runCurrent()
        vm.onEvent(GalleryEvent.SetCollectionMediaSort(MediaSort.SizeAscending))
        vm.onEvent(GalleryEvent.OpenMedia("one")); vm.onEvent(GalleryEvent.StepViewer(1))
        assertEquals(GalleryOverlay.CardViewer("ten"), vm.state.value.overlay)
        vm.onEvent(GalleryEvent.SetCollectionMediaSort(MediaSort.NameDescending))
        assertEquals(MediaSort.SizeAscending, preferences.options.value.collectionMediaSorts["c"])
        vm.onEvent(GalleryEvent.CloseOverlay); vm.onEvent(GalleryEvent.OpenMedia("two"))
        assertEquals(GalleryOverlay.VideoPlayer("two"), vm.state.value.overlay)
        vm.onEvent(GalleryEvent.CloseOverlay); vm.onEvent(GalleryEvent.OpenComic)
        val sorted = vm.state.value.mediaItems
        vm.onEvent(GalleryEvent.ToggleComicDirection)
        assertSame(sorted, vm.state.value.mediaItems)
        assertEquals(ComicDirection.NameDescending, vm.state.value.comicDirection)
    }

    @Test fun collectionSortGuardsDoNotChangeOtherRoutesOrSelection() = runTest(dispatcher) {
        val preferences = SortPreferences(BrowseOptions(collectionMediaSorts = mapOf("c" to MediaSort.SizeAscending)))
        val vm = GalleryViewModel(FakeRepository(), SavedStateHandle(), mediaSource(flowOf(sortImages())), browsePreferences = preferences)
            .also { store.put("collectionGuards", it) }
        vm.onEvent(GalleryEvent.SetCollectionMediaSort(MediaSort.NameDescending))
        vm.onEvent(GalleryEvent.OpenArtist("a")); runCurrent()
        assertEquals(listOf("one", "two", "ten"), vm.state.value.mediaItems.map { it.id })
        vm.onEvent(GalleryEvent.SetCollectionMediaSort(MediaSort.NameDescending))
        vm.onEvent(GalleryEvent.OpenUnassigned); runCurrent()
        assertEquals(sortImages(), vm.state.value.mediaItems)
        vm.onEvent(GalleryEvent.OpenCollection("c", "a")); runCurrent()
        vm.onEvent(GalleryEvent.StartMediaSelection("one"))
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.CollectionMediaSort))
        vm.onEvent(GalleryEvent.SetCollectionMediaSort(MediaSort.NameDescending))
        assertEquals(GalleryDialog.None, vm.state.value.dialog)
        assertEquals(0, preferences.saves)
    }

    @Test fun collectionSortDialogRestoresAndCancelKeepsRouteAndPreferences() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val preferences = SortPreferences()
        val vm = GalleryViewModel(FakeRepository(), handle, mediaSource(flowOf(sortImages())), browsePreferences = preferences)
            .also { store.put("collectionDialog", it) }
        vm.onEvent(GalleryEvent.OpenCollection("c", "a")); runCurrent()
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.CollectionMediaSort))
        val restored = GalleryViewModel(FakeRepository(), handle, mediaSource(flowOf(sortImages())), browsePreferences = preferences)
            .also { store.put("collectionDialogRestored", it) }
        runCurrent()
        assertEquals(GalleryDialog.CollectionMediaSort, restored.state.value.dialog)
        restored.onEvent(GalleryEvent.NavigateBack)
        assertEquals(GalleryRoute.Collection("c", "a"), restored.state.value.route)
        assertEquals(GalleryDialog.None, restored.state.value.dialog)
        assertEquals(0, preferences.saves)
    }

    private class FakeRepository : GalleryRepository {
        val artists = MutableStateFlow<List<ArtistSummary>>(emptyList())
        var writes = 0
        var readFails = false
        var readFailuresRemaining = 0
        var readSubscriptions = 0
        var result: SaveResult = SaveResult.Saved("new")
        var gate: CompletableDeferred<Unit>? = null
        var lastName: String? = null
        var lastId: String? = null
        var detailFlow: Flow<ArtistDetail?>? = null
        override fun observeArtists(): Flow<List<ArtistSummary>> = flow {
            readSubscriptions++
            if (readFailuresRemaining > 0) { readFailuresRemaining--; error("首次读取失败") }
            if (readFails) error("读取失败")
            emitAll(artists)
        }
        override fun observeArtist(id: String): Flow<ArtistDetail?> = detailFlow ?: flowOf(ArtistDetail(ArtistSummary(id, "画师", 0, 0), emptyList()))
        override fun observeCollection(id: String): Flow<CollectionSummary?> = flowOf(CollectionSummary(id, "a", "作品集", false, 0, 0))
        private suspend fun save(name: String): SaveResult { writes++; lastName = name; gate?.await(); return result }
        override suspend fun createArtist(name: String) = save(name)
        override suspend fun createCollection(artistId: String, name: String): SaveResult { lastId = artistId; return save(name) }
        override suspend fun renameCollection(id: String, name: String): SaveResult { lastId = id; return save(name) }
        override suspend fun renameArtist(id: String, name: String): SaveResult { lastId = id; return save(name) }
    }
}
