package dev.local.artistgallery.data.preview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PreviewRequestTest {
    @Test fun waitingPrefetchIsPromotedExactlyOnce() = runTest {
        val calls = mutableListOf<PreviewPriority>()
        val waiting = CompletableDeferred<Unit>()
        val results = mutableListOf<String>()
        val request = PreviewRequest<Int, String>(backgroundScope, { _, priority, started ->
            calls += priority
            if (priority == PreviewPriority.Prefetch) waiting.await()
            started(); "pixels"
        }, results::add)
        request.update(1, isCurrent = false); runCurrent()
        request.update(1, isCurrent = true)
        request.update(1, isCurrent = true); runCurrent()
        waiting.complete(Unit); runCurrent()
        assertEquals(listOf(PreviewPriority.Prefetch, PreviewPriority.Current), calls)
        assertEquals(listOf("pixels"), results)
    }

    @Test fun runningPrefetchIsReusedWhenItBecomesCurrent() = runTest {
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val results = mutableListOf<String>()
        val request = PreviewRequest<Int, String>(backgroundScope, { _, _, started ->
            calls++; started(); finish.await(); "same drawable"
        }, results::add)
        request.update(1, isCurrent = false); runCurrent()
        request.update(1, isCurrent = true); runCurrent()
        assertEquals(1, calls)
        finish.complete(Unit); runCurrent()
        request.update(1, isCurrent = false)
        request.update(1, isCurrent = true); runCurrent()
        assertEquals(1, calls)
        assertEquals(listOf("same drawable"), results)
    }

    @Test fun sizeUpgradeRetainsPriorPixelsAndRejectsLateSupersededPixels() = runTest {
        val medium = CompletableDeferred<Unit>()
        val large = CompletableDeferred<Unit>()
        var displayed: String? = null
        val request = PreviewRequest<Int, String>(backgroundScope, { size, _, started ->
            started()
            when (size) {
                2 -> withContext(NonCancellable) { medium.await() }
                3 -> large.await()
            }
            "size$size"
        }, { displayed = it })
        request.update(1, true); runCurrent()
        assertEquals("size1", displayed)
        request.update(2, true); runCurrent()
        assertEquals("size1", displayed)
        request.update(3, true); runCurrent()
        medium.complete(Unit); runCurrent()
        assertEquals("size1", displayed)
        large.complete(Unit); runCurrent()
        assertEquals("size3", displayed)
    }

    @Test fun disposedRequestCannotPublishALateNativeResult() = runTest {
        val finish = CompletableDeferred<Unit>()
        val results = mutableListOf<String>()
        val request = PreviewRequest<Int, String>(backgroundScope, { _, _, started ->
            started(); withContext(NonCancellable) { finish.await() }; "closed"
        }, results::add)
        request.update(1, true); runCurrent()
        request.cancel()
        finish.complete(Unit); runCurrent()
        assertTrue(results.isEmpty())
    }

    @Test fun failureDoesNotRestartOnVisibilityChangesAndKeepsPreviousPixels() = runTest {
        var calls = 0
        var displayed: String? = null
        val request = PreviewRequest<Int, String>(backgroundScope, { size, _, started ->
            calls++; started()
            if (size == 2) error("synthetic failure")
            "old"
        }, { displayed = it })
        request.update(1, true); runCurrent()
        request.update(2, false); runCurrent()
        request.update(2, true); runCurrent()
        assertEquals(2, calls)
        assertEquals("old", displayed)
    }
}
