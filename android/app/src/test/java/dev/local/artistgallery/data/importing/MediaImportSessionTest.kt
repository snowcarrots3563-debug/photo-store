package dev.local.artistgallery.data.importing

import dev.local.artistgallery.data.GalleryRepository
import dev.local.artistgallery.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MediaImportSessionTest {
    private class Gallery : GalleryRepository {
        var ensures = 0
        override fun observeArtists() = kotlinx.coroutines.flow.flowOf(emptyList<ArtistSummary>())
        override fun observeArtist(id: String) = kotlinx.coroutines.flow.flowOf<ArtistDetail?>(null)
        override fun observeCollection(id: String) = kotlinx.coroutines.flow.flowOf<CollectionSummary?>(null)
        override suspend fun createArtist(name: String) = SaveResult.StorageFailure
        override suspend fun renameArtist(id: String, name: String) = SaveResult.StorageFailure
        override suspend fun createCollection(artistId: String, name: String) = SaveResult.StorageFailure
        override suspend fun renameCollection(id: String, name: String) = SaveResult.StorageFailure
        override suspend fun ensureDefaultCollection(artistId: String): SaveResult { ensures++; return SaveResult.Saved("default") }
    }
    private fun result(id: String = "batch") = ImportBatchSummary(id, "COMPLETED", emptyList())

    @Test fun recoveryAndForegroundBothGateDefaultCreationAndImport() = runTest {
        val gallery = Gallery()
        val recovery = CompletableDeferred<Unit>()
        val controller = ImportWorkController({})
        val targets = mutableListOf<String?>()
        val session = MediaImportSession(backgroundScope, gallery, { _, target, _ -> targets.add(target); result() }, { _, _ -> result() },
            controller, { recovery.await() })
        assertTrue(session.start(listOf("synthetic"), null, "artist"))
        assertTrue(session.isRunning)
        assertEquals(1, controller.activeSnapshot().size)
        runCurrent()
        assertEquals(0, gallery.ensures)
        controller.serviceReady(controller.serviceCreated())
        runCurrent()
        assertEquals(0, gallery.ensures)
        recovery.complete(Unit); runCurrent()
        assertEquals(listOf("default"), targets)
        assertEquals(1, gallery.ensures)
        assertFalse(session.isRunning)
        assertTrue(controller.activeSnapshot().isEmpty())
    }

    @Test fun failedProtectionDoesNoWritesAndLeavesAnAcknowledgableError() = runTest {
        val gallery = Gallery()
        var imports = 0
        val controller = ImportWorkController({ throw SecurityException("synthetic denied start") })
        val session = MediaImportSession(backgroundScope, gallery, { _, _, _ -> imports++; result() }, { _, _ -> result() }, controller)
        assertTrue(session.start(listOf("synthetic"), null, "artist"))
        runCurrent()
        assertFalse(session.isRunning)
        assertEquals(0, gallery.ensures); assertEquals(0, imports)
        assertTrue(session.state.value.error.orEmpty().contains("无法启动后台导入"))
        assertTrue(session.acknowledge(session.state.value.attempt))
        assertFalse(session.acknowledge(session.state.value.attempt))
    }

    @Test fun noPermissionDependencyAndConcurrentStartCannotDuplicateWorkOrCompletion() = runTest {
        val gate = CompletableDeferred<Unit>()
        var imports = 0
        val notices = mutableListOf<ImportCompletion>()
        val session = MediaImportSession(backgroundScope, Gallery(), { _, _, _ -> imports++; gate.await(); result() }, { _, _ -> result() },
            onImportCompleted = { notices.add(it) })
        assertTrue(session.start(listOf("synthetic"), null, null))
        assertFalse(session.start(listOf("other"), null, null)); assertFalse(session.retry("batch"))
        runCurrent(); assertEquals(1, imports)
        gate.complete(Unit); runCurrent()
        assertEquals(1, notices.size)
        assertEquals(result(), session.state.value.result)
        assertTrue(session.acknowledge(session.state.value.attempt))
        assertNull(session.state.value.result)
        assertEquals(1, notices.size)
    }

    @Test fun recoveryFailureClearsBusyAndDoesNotFabricateACompletion() = runTest {
        val notices = mutableListOf<ImportCompletion>()
        val session = MediaImportSession(backgroundScope, Gallery(), { _, _, _ -> error("must not import") }, { _, _ -> result() },
            awaitRecovery = { error("synthetic recovery failure") }, onImportCompleted = { notices.add(it) })
        session.start(listOf("synthetic"), null, null); runCurrent()
        assertFalse(session.isRunning)
        assertEquals("synthetic recovery failure", session.state.value.error)
        assertTrue(notices.isEmpty())
    }

    @Test fun serviceInterruptionReleasesLeaseAndCanBeRetried() = runTest {
        val controller = ImportWorkController({})
        val generation = controller.serviceCreated()
        var retries = 0
        val session = MediaImportSession(backgroundScope, Gallery(), { _, _, _ -> awaitCancellation() }, { _, _ -> retries++; result() }, controller)
        session.start(listOf("synthetic"), null, null)
        controller.serviceReady(generation); runCurrent()
        controller.serviceTimedOut(generation) {}; runCurrent()
        assertFalse(session.isRunning)
        assertTrue(session.state.value.error.orEmpty().contains("系统时限"))
        assertNull(session.state.value.result)
        assertTrue(session.acknowledge(session.state.value.attempt))
        assertTrue(session.retry("batch"))
        controller.serviceReady(controller.serviceCreated()); runCurrent()
        assertEquals(1, retries); assertEquals(result(), session.state.value.result)
    }

    @Test fun progressUsesCommittedCountAndCompletionDoesNotCloseAnotherTaskLease() = runTest {
        val controller = ImportWorkController({})
        val archive = controller.begin(ImportWorkProgress("正在准备压缩包", "读取目录"))
        controller.serviceReady(controller.serviceCreated())
        val gate = CompletableDeferred<Unit>()
        val session = MediaImportSession(backgroundScope, Gallery(), { _, _, progress ->
            progress(ImportProgress("batch", 1, 1, "synthetic.png", 8000, completedItems = 0))
            gate.await()
            progress(ImportProgress("batch", 1, 1, "synthetic.png", 8000, completedItems = 1))
            result()
        }, { _, _ -> result() }, controller)
        session.start(listOf("synthetic"), null, null); runCurrent()
        val progress = controller.activeSnapshot().last()
        assertEquals(0L, progress.completed); assertEquals(1L, progress.total)
        assertEquals(ImportResultTarget(ImportResultKind.Media, "batch"), progress.target)
        assertEquals(0, progress.barValue)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf("正在准备压缩包"), controller.activeSnapshot().map { it.title })
        archive.close()
    }

    @Test fun interruptionBeforeLazyJobStartsStillClearsBusy() = runTest {
        val controller = ImportWorkController({})
        val generation = controller.serviceCreated()
        val session = MediaImportSession(backgroundScope, Gallery(), { _, _, _ -> error("must not import") }, { _, _ -> result() }, controller)
        session.start(listOf("synthetic"), null, null)
        controller.serviceDestroyed(generation); runCurrent()
        assertFalse(session.isRunning)
        assertNotNull(session.state.value.error)
        assertTrue(controller.activeSnapshot().isEmpty())
    }

    @Test fun anUnacknowledgedOutcomeCannotBeOverwrittenByAnotherAttempt() = runTest {
        var imports = 0
        val session = MediaImportSession(backgroundScope, Gallery(), { _, _, _ -> imports++; result() }, { _, _ -> result("retry") })
        session.start(listOf("synthetic"), null, null); runCurrent()
        val finished = session.state.value
        assertFalse(session.start(listOf("new"), null, null)); assertFalse(session.retry("batch"))
        assertEquals(finished, session.state.value); assertEquals(1, imports)
        assertTrue(session.acknowledge(finished.attempt))
        assertTrue(session.retry("batch")); runCurrent()
        assertEquals("retry", session.state.value.result?.id)
    }

    @Test fun denseByteProgressPublishesOnlyAtThe250MillisecondBoundary() = runTest {
        var now = 0L
        lateinit var emit: (ImportProgress) -> Unit
        val gate = CompletableDeferred<Unit>()
        val controller = ImportWorkController({})
        val session = MediaImportSession(backgroundScope, Gallery(), { _, _, progress -> emit = progress; gate.await(); result() },
            { _, _ -> result() }, controller, nanoTime = { now })
        session.start(listOf("synthetic"), null, null)
        controller.serviceReady(controller.serviceCreated()); runCurrent()
        val first = ImportProgress("batch", 1, 1, "synthetic.png", 1024)
        emit(first)
        val published = session.state.value
        val work = controller.activeSnapshot().single()
        for (millis in 1L..249L) {
            now = millis * 1_000_000
            emit(first.copy(copiedBytes = (millis + 1) * 1024))
            assertSame(published, session.state.value)
            assertEquals(work, controller.activeSnapshot().single())
        }
        now = 249_999_999
        emit(first.copy(copiedBytes = 500_000))
        assertSame(published, session.state.value)
        now = 250_000_000
        val next = first.copy(copiedBytes = 512_000)
        emit(next)
        assertEquals(next, session.state.value.progress)
        assertEquals("synthetic.png · 500 KiB", controller.activeSnapshot().single().detail)
        now = 499_999_999
        emit(next.copy(copiedBytes = 600_000))
        assertEquals(next, session.state.value.progress)
        gate.complete(Unit); runCurrent()
    }

    @Test fun firstProgressAndEveryItemBoundaryPublishImmediatelyAndRestartTheByteWindow() = runTest {
        var now = 0L
        lateinit var emit: (ImportProgress) -> Unit
        val gate = CompletableDeferred<Unit>()
        val controller = ImportWorkController({})
        val session = MediaImportSession(backgroundScope, Gallery(), { _, _, progress -> emit = progress; gate.await(); result() },
            { _, _ -> result() }, controller, nanoTime = { now })
        session.start(listOf("synthetic"), null, null)
        controller.serviceReady(controller.serviceCreated()); runCurrent()
        var progress = ImportProgress("batch", 1, 3, "first.png", 1024)
        val boundaries = listOf<(ImportProgress) -> ImportProgress>(
            { it },
            { it.copy(batchId = "new-batch") },
            { it.copy(currentItem = 2) },
            { it.copy(sourceName = "second.png") },
            { it.copy(itemCount = 4) },
            { it.copy(completedItems = 1) },
        )
        for (boundary in boundaries) {
            now += 10_000_000
            progress = boundary(progress)
            emit(progress)
            assertEquals(progress, session.state.value.progress)
            val work = controller.activeSnapshot().single()
            assertEquals("${progress.sourceName} · ${progress.copiedBytes / 1024} KiB", work.detail)
            assertEquals(progress.completedItems.toLong(), work.completed)
            assertEquals(progress.itemCount.toLong(), work.total)
            assertEquals(ImportResultTarget(ImportResultKind.Media, progress.batchId), work.target)
            emit(progress.copy(copiedBytes = progress.copiedBytes + 1024))
            assertEquals(progress, session.state.value.progress)
            assertEquals(work, controller.activeSnapshot().single())
        }
        now += 249_999_999
        emit(progress.copy(copiedBytes = 4096))
        assertEquals(progress, session.state.value.progress)
        now++
        emit(progress.copy(copiedBytes = 8192))
        assertEquals(8192L, session.state.value.progress?.copiedBytes)
        gate.complete(Unit); runCurrent()
    }

    @Test fun completionWithinTheByteWindowIsImmediateAndLateProgressCannotReappear() = runTest {
        lateinit var emit: (ImportProgress) -> Unit
        val gate = CompletableDeferred<Unit>()
        val controller = ImportWorkController({})
        val session = MediaImportSession(backgroundScope, Gallery(), { _, _, progress -> emit = progress; gate.await(); result() },
            { _, _ -> result() }, controller, nanoTime = { 0L })
        session.start(listOf("synthetic"), null, null)
        controller.serviceReady(controller.serviceCreated()); runCurrent()
        val progress = ImportProgress("batch", 1, 1, "synthetic.png", 1024)
        emit(progress); emit(progress.copy(copiedBytes = 2048))
        gate.complete(Unit); runCurrent()
        val finished = session.state.value
        assertFalse(finished.running); assertNull(finished.progress); assertEquals(result(), finished.result)
        assertTrue(controller.activeSnapshot().isEmpty())
        emit(progress.copy(completedItems = 1))
        advanceTimeBy(1000); runCurrent()
        assertSame(finished, session.state.value)
        assertTrue(controller.activeSnapshot().isEmpty())
    }

    @Test fun failureWithinTheByteWindowClearsProgressAndIgnoresLateCallbacks() = runTest {
        lateinit var emit: (ImportProgress) -> Unit
        val gate = CompletableDeferred<Unit>()
        val controller = ImportWorkController({})
        val session = MediaImportSession(backgroundScope, Gallery(), { _, _, progress ->
            emit = progress; gate.await(); error("synthetic copy failure")
        }, { _, _ -> result() }, controller, nanoTime = { 0L })
        session.start(listOf("synthetic"), null, null)
        controller.serviceReady(controller.serviceCreated()); runCurrent()
        val progress = ImportProgress("batch", 1, 1, "synthetic.png", 1024)
        emit(progress); emit(progress.copy(copiedBytes = 2048))
        gate.complete(Unit); runCurrent()
        val finished = session.state.value
        assertFalse(finished.running); assertNull(finished.progress); assertEquals("synthetic copy failure", finished.error)
        assertTrue(controller.activeSnapshot().isEmpty())
        emit(progress.copy(completedItems = 1))
        assertSame(finished, session.state.value)
        assertTrue(controller.activeSnapshot().isEmpty())
    }

    @Test fun interruptedAttemptAndItsLateCallbackCannotPolluteRetryStateOrLease() = runTest {
        lateinit var oldEmit: (ImportProgress) -> Unit
        lateinit var retryEmit: (ImportProgress) -> Unit
        val retryGate = CompletableDeferred<Unit>()
        val controller = ImportWorkController({})
        val session = MediaImportSession(backgroundScope, Gallery(), { _, _, progress -> oldEmit = progress; awaitCancellation() },
            { _, progress -> retryEmit = progress; retryGate.await(); result("retry") }, controller, nanoTime = { 0L })
        session.start(listOf("synthetic"), null, null)
        val generation = controller.serviceCreated()
        controller.serviceReady(generation); runCurrent()
        val progress = ImportProgress("batch", 1, 1, "synthetic.png", 1024)
        oldEmit(progress)
        controller.serviceTimedOut(generation) {}; runCurrent()
        val interrupted = session.state.value
        assertFalse(interrupted.running); assertNull(interrupted.progress); assertNotNull(interrupted.error)
        oldEmit(progress.copy(completedItems = 1))
        assertSame(interrupted, session.state.value)
        assertTrue(controller.activeSnapshot().isEmpty())
        assertTrue(session.acknowledge(interrupted.attempt))
        assertTrue(session.retry("batch"))
        val preparing = session.state.value
        val preparingWork = controller.activeSnapshot().single()
        oldEmit(progress.copy(sourceName = "stale.png"))
        assertSame(preparing, session.state.value)
        assertEquals(preparingWork, controller.activeSnapshot().single())
        controller.serviceReady(controller.serviceCreated()); runCurrent()
        val retryProgress = progress.copy(sourceName = "retry.png", copiedBytes = 4096)
        retryEmit(retryProgress)
        assertEquals(retryProgress, session.state.value.progress)
        val retryState = session.state.value
        val retryWork = controller.activeSnapshot().single()
        oldEmit(progress.copy(sourceName = "stale.png", completedItems = 1))
        assertSame(retryState, session.state.value)
        assertEquals(retryWork, controller.activeSnapshot().single())
        retryGate.complete(Unit); runCurrent()
        assertEquals("retry", session.state.value.result?.id)
    }
}
