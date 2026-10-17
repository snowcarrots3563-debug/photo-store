package dev.local.artistgallery.data.preview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PreviewDecodeGateTest {
    @Test fun bitmapAndAnimatedWorkShareTwoPermits() = runTest {
        val gate = PreviewDecodeGate(StandardTestDispatcher(testScheduler))
        val finish = CompletableDeferred<Unit>()
        var running = 0
        var peak = 0
        var started = 0
        val jobs = List(6) {
            launch {
                gate.decode(PreviewPriority.Current) {
                    started++; running++; peak = maxOf(peak, running)
                    finish.await()
                    running--
                }
            }
        }
        runCurrent()
        assertEquals(2, started)
        assertEquals(2, peak)
        finish.complete(Unit); runCurrent()
        jobs.forEach { it.join() }
        assertEquals(6, started)
        assertEquals(2, peak)
    }

    @Test fun queuedPrefetchesLeaveOneSlotForTheCurrentPage() = runTest {
        val gate = PreviewDecodeGate(StandardTestDispatcher(testScheduler))
        val finish = CompletableDeferred<Unit>()
        var prefetches = 0
        var current = false
        val jobs = List(8) {
            launch { gate.decode(PreviewPriority.Prefetch) { prefetches++; finish.await() } }
        }
        runCurrent()
        launch { gate.decode(PreviewPriority.Current) { current = true } }
        runCurrent()
        assertEquals(1, prefetches)
        assertTrue(current)
        finish.complete(Unit); runCurrent()
        jobs.forEach { it.join() }
        assertEquals(8, prefetches)
    }

    @Test fun cancelledWaiterNeverStartsNativeWork() = runTest {
        val gate = PreviewDecodeGate(StandardTestDispatcher(testScheduler))
        val finish = CompletableDeferred<Unit>()
        val occupied = List(2) { launch { gate.decode(PreviewPriority.Current) { finish.await() } } }
        runCurrent()
        var started = false
        val waiting = launch { gate.decode(PreviewPriority.Current) { started = true } }
        runCurrent(); waiting.cancelAndJoin()
        finish.complete(Unit); runCurrent()
        occupied.forEach { it.join() }
        assertFalse(started)
    }

    @Test fun cancellationAfterAdmissionButBeforeIoDoesNotRead() = runTest {
        val gate = PreviewDecodeGate(StandardTestDispatcher(testScheduler, name = "native"))
        var admitted = false
        var started = false
        val request = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.decode(PreviewPriority.Current, onStarted = { admitted = true }) { started = true }
        }
        assertTrue(admitted)
        request.cancel()
        runCurrent(); request.join()
        assertFalse(started)
        assertEquals("released", gate.decode(PreviewPriority.Current) { "released" })
    }

    @Test fun cancelledNativeWorkKeepsItsPermitUntilItReturns() = runTest {
        val gate = PreviewDecodeGate(StandardTestDispatcher(testScheduler))
        val firstFinish = CompletableDeferred<Unit>()
        val secondFinish = CompletableDeferred<Unit>()
        val first = launch {
            gate.decode(PreviewPriority.Current) { withContext(NonCancellable) { firstFinish.await() } }
        }
        val second = launch { gate.decode(PreviewPriority.Current) { secondFinish.await() } }
        runCurrent(); first.cancel()
        var thirdStarted = false
        val third = launch { gate.decode(PreviewPriority.Current) { thirdStarted = true } }
        runCurrent()
        assertFalse(thirdStarted)
        firstFinish.complete(Unit); runCurrent()
        assertTrue(thirdStarted)
        secondFinish.complete(Unit); runCurrent()
        first.join(); second.join(); third.join()
    }

    @Test fun failureReleasesBothPrefetchAndSharedPermits() = runTest {
        val gate = PreviewDecodeGate(StandardTestDispatcher(testScheduler))
        try {
            gate.decode(PreviewPriority.Prefetch) { error("synthetic decode failure") }
            fail("expected failure")
        } catch (expected: IllegalStateException) {
            assertEquals("synthetic decode failure", expected.message)
        }
        assertEquals("next", gate.decode(PreviewPriority.Prefetch) { "next" })
    }
}
