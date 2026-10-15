package dev.local.artistgallery.data.importing

import dev.local.artistgallery.data.GalleryRepository
import dev.local.artistgallery.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

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
}
