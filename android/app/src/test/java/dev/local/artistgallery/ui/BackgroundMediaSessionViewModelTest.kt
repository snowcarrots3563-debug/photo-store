package dev.local.artistgallery.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.local.artistgallery.data.GalleryRepository
import dev.local.artistgallery.data.MediaLibraryRepository
import dev.local.artistgallery.data.importing.MediaImportSession
import dev.local.artistgallery.model.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BackgroundMediaSessionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private var serial = 0
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    private val gallery = object : GalleryRepository {
        override fun observeArtists() = flowOf(emptyList<ArtistSummary>())
        override fun observeArtist(id: String) = flowOf<ArtistDetail?>(null)
        override fun observeCollection(id: String) = flowOf<CollectionSummary?>(null)
        override suspend fun createArtist(name: String) = SaveResult.StorageFailure
        override suspend fun renameArtist(id: String, name: String) = SaveResult.StorageFailure
        override suspend fun createCollection(artistId: String, name: String) = SaveResult.StorageFailure
        override suspend fun renameCollection(id: String, name: String) = SaveResult.StorageFailure
    }
    private val old = ImportBatchSummary("old", "COMPLETED", emptyList())
    private val result = ImportBatchSummary("new", "COMPLETED", emptyList())
    private val media = Proxy.newProxyInstance(MediaLibraryRepository::class.java.classLoader, arrayOf(MediaLibraryRepository::class.java)) { _, method, _ ->
        when (method.name) {
            "observeLatestImportBatches" -> flowOf(emptyList<ImportBatchSummary>())
            "getImportBatchResult" -> old
            else -> error("Unexpected repository call ${method.name}")
        }
    } as MediaLibraryRepository
    private fun model(session: MediaImportSession, saved: SavedStateHandle = SavedStateHandle()) =
        GalleryViewModel(gallery, saved, mediaRepository = media, mediaImportSession = session).also { store.put("session${serial++}", it) }

    @Test fun clearingFirstViewModelKeepsWorkAndSecondReattachesWithoutDuplicateCompletion() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var imports = 0; var completions = 0
        val session = MediaImportSession(backgroundScope, gallery, { _, _, progress ->
            imports++; progress(ImportProgress("new", 1, 1, "synthetic.png", 0)); gate.await(); result
        }, { _, _ -> result }, onImportCompleted = { completions++ })
        val first = model(session)
        session.start(listOf("synthetic"), null, null); runCurrent()
        assertEquals(GalleryDialog.ImportProgress, first.state.value.dialog)
        store.clear(); runCurrent()
        assertTrue(session.isRunning)
        val second = model(session); runCurrent()
        assertEquals(GalleryDialog.ImportProgress, second.state.value.dialog)
        assertEquals("new", second.state.value.importProgress?.batchId)
        gate.complete(Unit); runCurrent()
        assertEquals(GalleryDialog.ImportResult("new"), second.state.value.dialog)
        assertEquals(1, imports); assertEquals(1, completions)
        val third = model(session); runCurrent()
        assertEquals(GalleryDialog.None, third.state.value.dialog)
        assertEquals(1, completions)
    }

    @Test fun dismissedProgressStaysHiddenDuringProgressAndCompletion() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val session = MediaImportSession(backgroundScope, gallery, { _, _, progress ->
            progress(ImportProgress("new", 1, 2, "a.png", 0)); gate.await()
            progress(ImportProgress("new", 2, 2, "b.png", 0, 1)); finish.await(); result
        }, { _, _ -> result })
        val vm = model(session)
        session.start(listOf("synthetic"), null, null); runCurrent()
        vm.onEvent(GalleryEvent.DismissDialog)
        gate.complete(Unit); runCurrent()
        assertEquals(GalleryDialog.None, vm.state.value.dialog)
        assertEquals("b.png", vm.state.value.importProgress?.sourceName)
        finish.complete(Unit); runCurrent()
        assertEquals(GalleryDialog.None, vm.state.value.dialog)
        assertEquals(result, vm.state.value.importResult)
    }

    @Test fun finishedOutcomeWaitsForAnObserverAndIsHandledOnce() = runTest(dispatcher) {
        var completions = 0
        val session = MediaImportSession(backgroundScope, gallery, { _, _, _ -> result }, { _, _ -> result }, onImportCompleted = { completions++ })
        session.start(listOf("synthetic"), null, null); runCurrent()
        assertEquals(result, session.state.value.result)
        val vm = model(session); runCurrent()
        assertEquals(result, vm.state.value.importResult)
        assertNull(session.state.value.result)
        model(session); runCurrent()
        assertEquals(1, completions)
    }

    @Test fun queuedNotificationResultDrainsAfterApplicationOwnedAttempt() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val session = MediaImportSession(backgroundScope, gallery, { _, _, _ -> gate.await(); result }, { _, _ -> result })
        val vm = model(session)
        session.start(listOf("synthetic"), null, null); runCurrent()
        vm.onEvent(GalleryEvent.OpenImportResult("old")); runCurrent()
        assertEquals(GalleryDialog.ImportProgress, vm.state.value.dialog)
        gate.complete(Unit); runCurrent()
        assertEquals(GalleryDialog.ImportResult("old"), vm.state.value.dialog)
        assertEquals(old, vm.state.value.importResult)
    }

    @Test fun consumingAPriorOutcomePreservesNewPreviewAndItsExplicitImportTarget() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val requests = mutableListOf<Pair<List<String>, String?>>()
        val session = MediaImportSession(backgroundScope, gallery, { uris, target, _ ->
            requests.add(uris to target)
            if (requests.size == 2) gate.await()
            result
        }, { _, _ -> result })
        session.start(listOf("previous"), null, null); runCurrent()
        val saved = SavedStateHandle(mapOf("dialog" to arrayListOf("importPreview"),
            "pendingImportTargetCollectionId" to "new-target",
            "importCandidates" to arrayListOf(arrayListOf("new-source", "synthetic.png", "IMAGE", "", "")),
            "hiddenImportAttempt" to 1L))
        val vm = model(session, saved); runCurrent()
        assertEquals(GalleryDialog.ImportPreview, vm.state.value.dialog)
        assertEquals("new-source", vm.state.value.importCandidates.single().uri)
        assertEquals("new-target", vm.state.value.pendingImportTargetCollectionId)
        vm.onEvent(GalleryEvent.ConfirmImport); runCurrent()
        assertEquals(listOf("new-source") to "new-target", requests.last())
        assertNull(saved.get<Long>("hiddenImportAttempt"))
        assertEquals(GalleryDialog.ImportProgress, vm.state.value.dialog)
        gate.complete(Unit); runCurrent()
    }
}
