package dev.local.artistgallery.data.importing

import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ImportWorkControllerTest {
    private val preparing = ImportWorkProgress("正在导入", "准备中")

    @Test fun eachLeaseRequestsServiceAndOnlyLastCloseMakesItIdle() {
        var starts = 0
        val controller = ImportWorkController({ starts++ })
        val media = controller.begin(preparing)
        val archive = controller.begin(preparing.copy(title = "正在解压"))
        assertEquals(2, starts)
        assertEquals(2, controller.activeWork.value.size)
        media.close(); media.close()
        assertEquals(listOf("正在解压"), controller.activeSnapshot().map { it.title })
        archive.close()
        assertTrue(controller.activeWork.value.isEmpty())
    }

    @Test fun failedStartRemovesItsLeaseAndProvidesAnUnderstandableError() {
        val controller = ImportWorkController({ throw SecurityException("fixture") })
        val error = runCatching { controller.begin(preparing) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(requireNotNull(error?.message).contains("无法启动后台导入"))
        assertTrue(controller.activeSnapshot().isEmpty())
    }

    @Test fun aFailedConcurrentStartDoesNotRemoveExistingWork() {
        var starts = 0
        val controller = ImportWorkController({ if (++starts == 2) throw IllegalStateException("fixture") })
        val first = controller.begin(preparing)
        assertTrue(runCatching { controller.begin(preparing.copy(title = "其他任务")) }.isFailure)
        assertEquals(listOf(preparing), controller.activeSnapshot())
        first.close()
    }

    @Test fun workWaitsUntilForegroundPromotionAndUpdatesItsOwnLease() = runTest {
        val controller = ImportWorkController({})
        val first = controller.begin(preparing)
        val second = controller.begin(preparing)
        val ready = async { first.awaitForeground() }
        runCurrent()
        assertFalse(ready.isCompleted)
        val generation = controller.serviceCreated()
        assertFalse(ready.isCompleted)
        controller.serviceReady(generation)
        runCurrent()
        assertTrue(ready.isCompleted)
        first.update(preparing.copy(completed = 1, total = 2))
        assertEquals(listOf(1L, 0L), controller.activeSnapshot().map { it.completed })
        first.close(); second.close()
    }

    @Test fun readinessTimeoutInterruptsAndLateAttachCancelsImmediately() = runTest {
        val controller = ImportWorkController({}, foregroundTimeoutMillis = 100)
        val handle = controller.begin(preparing)
        val waiting = async { runCatching { handle.awaitForeground() }.exceptionOrNull() }
        runCurrent(); advanceTimeBy(101); runCurrent()
        assertTrue(waiting.await() is ImportWorkInterruptedException)
        val lateJob = Job()
        handle.attach(lateJob)
        assertTrue(lateJob.isCancelled)
        assertTrue(controller.activeSnapshot().isEmpty())
    }

    @Test fun unexpectedDestroyAndTimeoutCancelAttachedWorkAndFailPendingReadiness() = runTest {
        val controller = ImportWorkController({})
        val readyHandle = controller.begin(preparing)
        val generation = controller.serviceCreated()
        controller.serviceReady(generation)
        val attached = Job()
        readyHandle.attach(attached)
        controller.serviceDestroyed(generation)
        assertTrue(attached.isCancelled)
        assertTrue(controller.activeSnapshot().isEmpty())

        val pending = controller.begin(preparing)
        val nextGeneration = controller.serviceCreated()
        var stopped = false
        controller.serviceTimedOut(nextGeneration) { stopped = true }
        assertTrue(stopped)
        assertTrue(runCatching { pending.awaitForeground() }.exceptionOrNull() is ImportWorkInterruptedException)
        val late = Job()
        pending.attach(late)
        assertTrue(late.isCancelled)
    }

    @Test fun promotionFailureFailsEveryWaitingLease() = runTest {
        val controller = ImportWorkController({})
        val first = controller.begin(preparing)
        val second = controller.begin(preparing)
        val generation = controller.serviceCreated()
        controller.serviceFailed(generation, SecurityException("fixture")) {}
        assertTrue(runCatching { first.awaitForeground() }.exceptionOrNull() is ImportWorkInterruptedException)
        assertTrue(runCatching { second.awaitForeground() }.exceptionOrNull() is ImportWorkInterruptedException)
        assertTrue(controller.activeSnapshot().isEmpty())
    }

    @Test fun idleStopIsAtomicAndOldDestroyCannotInterruptAReplacementHost() = runTest {
        val controller = ImportWorkController({})
        val first = controller.begin(preparing)
        val oldGeneration = controller.serviceCreated()
        controller.serviceReady(oldGeneration)
        assertFalse(controller.stopIfIdle(oldGeneration) { fail("Still active") })
        first.close()
        var stops = 0
        assertTrue(controller.stopIfIdle(oldGeneration) { stops++ })
        val replacement = controller.begin(preparing)
        val replacementJob = Job()
        replacement.attach(replacementJob)
        val newGeneration = controller.serviceCreated()
        controller.serviceReady(newGeneration)
        controller.serviceDestroyed(oldGeneration)
        controller.serviceTimedOut(oldGeneration) { fail("Stale host") }
        assertFalse(controller.stopIfIdle(oldGeneration) { fail("Stale host") })
        replacement.awaitForeground()
        assertFalse(replacementJob.isCancelled)
        assertEquals(1, stops)
        replacement.close()
    }

    @Test fun closingAfterAnInterruptionCannotRemoveAnotherLease() {
        val controller = ImportWorkController({})
        val old = controller.begin(preparing)
        val generation = controller.serviceCreated()
        controller.serviceDestroyed(generation)
        val replacement = controller.begin(preparing)
        old.close(); old.update(preparing.copy(completed = 10))
        assertEquals(listOf(preparing), controller.activeSnapshot())
        replacement.close()
    }

    @Test fun anEmptyStartupDoesNotLetNewWorkBypassItsProtection() = runTest {
        val controller = ImportWorkController({})
        val generation = controller.serviceCreated()
        var protected = false
        controller.serviceReady(generation) { fail("No active work") }
        val handle = controller.begin(preparing)
        val waiting = async { handle.awaitForeground() }
        runCurrent()
        assertFalse(waiting.isCompleted)
        controller.serviceReady(generation) { protected = true }
        waiting.await()
        assertTrue(protected)
        handle.close()
    }

    @Test fun aPreviouslyReadyHandleCannotResumeAfterServiceInterruption() = runTest {
        val controller = ImportWorkController({})
        val handle = controller.begin(preparing)
        val generation = controller.serviceCreated()
        controller.serviceReady(generation)
        controller.serviceDestroyed(generation)
        assertTrue(runCatching { handle.awaitForeground() }.exceptionOrNull() is ImportWorkInterruptedException)
    }

    @Test fun normalCloseDoesNotCancelTheOwnersJobButRejectsLateAttachments() {
        val controller = ImportWorkController({})
        val handle = controller.begin(preparing)
        val owner = Job()
        handle.attach(owner)
        handle.close()
        assertFalse(owner.isCancelled)
        val late = Job()
        handle.attach(late)
        assertTrue(late.isCancelled)
        owner.cancel()
    }

    @Test fun staleReadinessCannotReleaseANewGenerationsWork() = runTest {
        val controller = ImportWorkController({})
        val oldGeneration = controller.serviceCreated()
        assertTrue(controller.stopIfIdle(oldGeneration) {})
        val handle = controller.begin(preparing)
        val generation = controller.serviceCreated()
        val waiting = async { handle.awaitForeground() }
        controller.serviceReady(oldGeneration)
        runCurrent()
        assertFalse(waiting.isCompleted)
        controller.serviceReady(generation)
        waiting.await()
        handle.close()
    }
}
