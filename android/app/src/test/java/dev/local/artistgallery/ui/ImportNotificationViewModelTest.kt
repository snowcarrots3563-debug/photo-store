package dev.local.artistgallery.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.local.artistgallery.data.GalleryRepository
import dev.local.artistgallery.data.MediaLibraryRepository
import dev.local.artistgallery.data.archive.ArchiveEntry
import dev.local.artistgallery.data.archive.ArchiveImportState
import dev.local.artistgallery.data.archive.ArchivePreviewGroup
import dev.local.artistgallery.data.archive.ArchivePreviewItem
import dev.local.artistgallery.data.archive.ArchiveStep
import dev.local.artistgallery.data.archive.ArchiveTaskEntity
import dev.local.artistgallery.model.*
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImportNotificationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private var serial = 0
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }

    private fun model(source: Results, saved: SavedStateHandle = SavedStateHandle()) =
        GalleryViewModel(Gallery(), saved, mediaRepository = source.repository()).also { store.put("notification${serial++}", it) }

    @Test fun notificationReadsAnOldBatchOutsideLatestTenWithoutRetryOrWrites() = runTest(dispatcher) {
        val source = Results().apply { batches["old"] = batch("old") }
        val vm = model(source)
        runCurrent()
        assertEquals(10, vm.state.value.recentImportBatches.size)
        vm.onEvent(GalleryEvent.OpenImportResult("old"))
        runCurrent()
        assertEquals(listOf("old"), source.reads)
        assertEquals(source.batches["old"], vm.state.value.importResult)
        assertEquals(GalleryDialog.ImportResult("old"), vm.state.value.dialog)
        assertEquals(0, source.writes)
    }

    @Test fun missingNotificationRecordKeepsExistingDialogAndGivesFeedback() = runTest(dispatcher) {
        val source = Results()
        val vm = model(source)
        val messages = mutableListOf<GalleryFeedback>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.feedback.toList(messages) }
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.About))
        vm.onEvent(GalleryEvent.OpenImportResult("missing"))
        runCurrent()
        assertEquals(GalleryDialog.About, vm.state.value.dialog)
        assertNull(vm.state.value.importResult)
        assertEquals(listOf(GalleryFeedback.Saved("导入记录不存在。")), messages)
    }

    @Test fun historyReadFailureDoesNotReplaceCurrentDialogOrAttemptImport() = runTest(dispatcher) {
        val source = Results().apply { failure = IllegalStateException("合成读取失败") }
        val vm = model(source)
        val messages = mutableListOf<GalleryFeedback>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.feedback.toList(messages) }
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.About))
        vm.onEvent(GalleryEvent.OpenImportResult("old"))
        runCurrent()
        assertEquals(GalleryDialog.About, vm.state.value.dialog)
        assertEquals(listOf(GalleryFeedback.Saved("合成读取失败")), messages)
        assertEquals(0, source.writes)
    }

    @Test fun aNewNotificationCancelsSlowerOldResultRead() = runTest(dispatcher) {
        val source = Results().apply { batches["slow"] = batch("slow"); gates["slow"] = CompletableDeferred() }
        val vm = model(source)
        vm.onEvent(GalleryEvent.OpenImportResult("slow"))
        runCurrent()
        vm.onEvent(GalleryEvent.OpenImportResult("recent-0"))
        runCurrent()
        assertEquals("recent-0", vm.state.value.importResult?.id)
        source.gates.getValue("slow").complete(Unit)
        runCurrent()
        assertEquals("recent-0", vm.state.value.importResult?.id)
        assertEquals(listOf("slow", "recent-0"), source.reads)
    }

    @Test fun delayedResultCannotReopenAfterDismissalWhileDialogIsStillNone() = runTest(dispatcher) {
        val source = slowResults()
        val vm = model(source)
        vm.onEvent(GalleryEvent.OpenImportResult("slow"))
        runCurrent()
        assertEquals(GalleryDialog.None, vm.state.value.dialog)
        vm.onEvent(GalleryEvent.DismissDialog)
        source.gates.getValue("slow").complete(Unit)
        runCurrent()
        assertEquals(GalleryDialog.None, vm.state.value.dialog)
        assertNull(vm.state.value.importResult)
    }

    @Test fun delayedResultCannotReplaceANewDialog() = runTest(dispatcher) {
        val source = slowResults()
        val vm = model(source)
        vm.onEvent(GalleryEvent.OpenImportResult("slow"))
        runCurrent()
        vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.About))
        source.gates.getValue("slow").complete(Unit)
        runCurrent()
        assertEquals(GalleryDialog.About, vm.state.value.dialog)
        assertNull(vm.state.value.importResult)
    }

    @Test fun delayedResultCannotFollowUserNavigationToAnotherPage() = runTest(dispatcher) {
        val source = slowResults()
        val vm = model(source)
        vm.onEvent(GalleryEvent.OpenImportResult("slow"))
        runCurrent()
        vm.onEvent(GalleryEvent.OpenManage)
        source.gates.getValue("slow").complete(Unit)
        runCurrent()
        assertEquals(GalleryRoute.Manage, vm.state.value.route)
        assertEquals(GalleryDialog.None, vm.state.value.dialog)
        assertNull(vm.state.value.importResult)
    }

    @Test fun systemPickerCallbackInvalidatesDelayedResultEvenWhenTheSelectionWasCancelled() = runTest(dispatcher) {
        val source = slowResults()
        val vm = model(source)
        vm.onEvent(GalleryEvent.OpenImportResult("slow"))
        runCurrent()
        vm.onMediaPicked(emptyList())
        source.gates.getValue("slow").complete(Unit)
        runCurrent()
        assertEquals(GalleryDialog.None, vm.state.value.dialog)
        assertNull(vm.state.value.importResult)
    }

    @Test fun scrollPositionMemoryDoesNotCancelARequestedResult() = runTest(dispatcher) {
        val source = slowResults()
        val vm = model(source)
        vm.onEvent(GalleryEvent.OpenImportResult("slow"))
        runCurrent()
        vm.onEvent(GalleryEvent.RememberScrollPosition("home", 4, 72))
        source.gates.getValue("slow").complete(Unit)
        runCurrent()
        assertEquals(GalleryDialog.ImportResult("slow"), vm.state.value.dialog)
        assertEquals("slow", vm.state.value.importResult?.id)
        assertEquals(GalleryScrollPosition(4, 72), vm.state.value.scrollPositions["home"])
    }

    @Test fun aNonCooperativeLateRepositoryCallbackCannotWriteAfterDismissal() = runTest(dispatcher) {
        val detachedScope = CoroutineScope(SupervisorJob() + dispatcher)
        val source = slowResults().apply { readScope = detachedScope }
        try {
            val vm = model(source)
            vm.onEvent(GalleryEvent.OpenImportResult("slow"))
            runCurrent()
            vm.onEvent(GalleryEvent.DismissDialog)
            source.gates.getValue("slow").complete(Unit)
            runCurrent()
            assertEquals(listOf("slow"), source.returnedReads)
            assertEquals(GalleryDialog.None, vm.state.value.dialog)
            assertNull(vm.state.value.importResult)
        } finally {
            source.gates.getValue("slow").complete(Unit)
            detachedScope.cancel()
        }
    }

    @Test fun historyUpdatesDoNotLoseOldDirectlyLoadedResult() = runTest(dispatcher) {
        val source = Results().apply { batches["old"] = batch("old") }
        val vm = model(source)
        vm.onEvent(GalleryEvent.OpenImportResult("old"))
        runCurrent()
        val loaded = vm.state.value.importResult
        source.latest.value = listOf(batch("newest"))
        runCurrent()
        assertSame(loaded, vm.state.value.importResult)
        assertEquals(GalleryDialog.ImportResult("old"), vm.state.value.dialog)
    }

    @Test fun retryPermissionKeyRequiresTheCurrentRetryableResultAndExpiresAfterDismissal() = runTest(dispatcher) {
        val source = Results().apply { batches["failed"] = batch("failed", retry = true) }
        val vm = model(source)
        vm.onEvent(GalleryEvent.OpenImportResult("failed"))
        runCurrent()
        assertEquals("failed", vm.importPermissionActionKey(GalleryEvent.RetryImport("failed")))
        assertNull(vm.importPermissionActionKey(GalleryEvent.RetryImport("recent-0")))
        vm.onEvent(GalleryEvent.DismissDialog)
        assertNull(vm.importPermissionActionKey(GalleryEvent.RetryImport("failed")))
        vm.continueImportAfterPermission(GalleryEvent.RetryImport("failed"), "failed")
        assertEquals(GalleryDialog.None, vm.state.value.dialog)
        assertEquals(0, source.writes)
    }

    @Test fun completedResultDoesNotAskForRetryPermission() = runTest(dispatcher) {
        val vm = model(Results())
        vm.onEvent(GalleryEvent.OpenImportResult("recent-0"))
        runCurrent()
        assertNull(vm.importPermissionActionKey(GalleryEvent.RetryImport("recent-0")))
    }

    @Test fun aRestoredProcessCannotExecuteAnOldPreviewPermissionAction() = runTest(dispatcher) {
        val source = Results()
        val vm = model(source, SavedStateHandle(mapOf("dialog" to arrayListOf("importPreview"))))
        assertNull(vm.importPermissionActionKey(GalleryEvent.ConfirmImport))
        vm.continueImportAfterPermission(GalleryEvent.ConfirmImport, "old-preview-id")
        assertEquals(GalleryDialog.None, vm.state.value.dialog)
        assertEquals(0, source.writes)
    }

    @Test fun archivePermissionConfirmIsBoundToTheVisibleImportableSession() {
        val preview = ArchiveImportState(visible = true, step = ArchiveStep.Preview, taskId = "session",
            groups = listOf(ArchivePreviewGroup("", "test", listOf(ArchivePreviewItem(ArchiveEntry(0, "a.png", 4096, false, false), MediaType.IMAGE, null)))))
        assertTrue(isArchiveNotificationActionCurrent(NotificationImportAction.ArchiveConfirm, "session", preview))
        assertFalse(isArchiveNotificationActionCurrent(NotificationImportAction.ArchiveConfirm, "other", preview))
        assertFalse(isArchiveNotificationActionCurrent(NotificationImportAction.ArchiveConfirm, "session", preview.copy(visible = false)))
        assertFalse(isArchiveNotificationActionCurrent(NotificationImportAction.ArchiveConfirm, "session", preview.copy(groups = emptyList())))
        assertFalse(isArchiveNotificationActionCurrent(NotificationImportAction.ArchiveConfirm, "session", ArchiveImportState()))
    }

    @Test fun archiveRetryPermissionCannotRunOnANewOrActiveSession() {
        val result = ArchiveImportState(visible = true, step = ArchiveStep.Result, taskId = "session")
        assertTrue(isArchiveNotificationActionCurrent(NotificationImportAction.ArchiveRetry, "session", result))
        assertTrue(isArchiveNotificationActionCurrent(NotificationImportAction.ArchiveRetry, "session", result.copy(step = ArchiveStep.Error)))
        assertFalse(isArchiveNotificationActionCurrent(NotificationImportAction.ArchiveRetry, "old", result))
        assertFalse(isArchiveNotificationActionCurrent(NotificationImportAction.ArchiveRetry, "session", result.copy(step = ArchiveStep.Importing)))
    }

    @Test fun archiveHistoryPermissionExpiresAfterHistoryIsClosedOrProcessRestarts() {
        val history = ArchiveImportState(visible = true, step = ArchiveStep.History,
            history = listOf(ArchiveTaskEntity("old", "artist", "ZIP", "test.zip", "[]", 0)))
        assertTrue(isArchiveNotificationActionCurrent(NotificationImportAction.ArchiveResume, "old", history))
        assertFalse(isArchiveNotificationActionCurrent(NotificationImportAction.ArchiveResume, "missing", history))
        assertFalse(isArchiveNotificationActionCurrent(NotificationImportAction.ArchiveResume, "old", history.copy(visible = false)))
        assertFalse(isArchiveNotificationActionCurrent(NotificationImportAction.ArchiveResume, "old", history.copy(step = ArchiveStep.Source)))
        assertFalse(isArchiveNotificationActionCurrent(NotificationImportAction.ArchiveResume, "old", ArchiveImportState()))
    }

    private fun batch(id: String, retry: Boolean = false) = ImportBatchSummary(id, if (retry) "FAILED" else "COMPLETED",
        listOf(ImportItemSummary("item-$id", "synthetic.png", if (retry) ImportItemStatus.FAILED else ImportItemStatus.COMPLETE, null, retry)))

    private fun slowResults() = Results().apply { batches["slow"] = batch("slow"); gates["slow"] = CompletableDeferred() }

    private inner class Results {
        val batches = (0 until 10).associate { "recent-$it" to batch("recent-$it") }.toMutableMap()
        val latest = MutableStateFlow(batches.values.toList())
        val reads = mutableListOf<String>()
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val returnedReads = mutableListOf<String>()
        var readScope: CoroutineScope? = null
        var failure: Exception? = null
        var writes = 0
        @Suppress("UNCHECKED_CAST")
        fun repository(): MediaLibraryRepository = Proxy.newProxyInstance(MediaLibraryRepository::class.java.classLoader,
            arrayOf(MediaLibraryRepository::class.java)) { _, method, arguments ->
            when (method.name) {
                "observeLatestImportBatches" -> latest
                "getImportBatchResult" -> {
                    val id = arguments!![0] as String
                    reads.add(id)
                    failure?.let { throw it }
                    val gate = gates[id]
                    if (gate == null) batches[id] else {
                        val continuation = arguments.last() as Continuation<ImportBatchSummary?>
                        (readScope ?: CoroutineScope(continuation.context)).launch {
                            try { gate.await(); continuation.resume(batches[id]); returnedReads.add(id) }
                            catch (cancelled: CancellationException) { continuation.resumeWithException(cancelled) }
                        }
                        COROUTINE_SUSPENDED
                    }
                }
                else -> { writes++; error("不应调用 ${method.name}") }
            }
        } as MediaLibraryRepository
    }

    private class Gallery : GalleryRepository {
        override fun observeArtists() = flowOf(emptyList<ArtistSummary>())
        override fun observeArtist(id: String) = flowOf<ArtistDetail?>(null)
        override fun observeCollection(id: String) = flowOf<CollectionSummary?>(null)
        override suspend fun createArtist(name: String) = SaveResult.StorageFailure
        override suspend fun renameArtist(id: String, name: String) = SaveResult.StorageFailure
        override suspend fun createCollection(artistId: String, name: String) = SaveResult.StorageFailure
        override suspend fun renameCollection(id: String, name: String) = SaveResult.StorageFailure
    }
}
