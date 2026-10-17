package dev.local.artistgallery.data.preview

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class PreviewPriority { Current, Prefetch }

internal class PreviewDecodeGate(private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val decodes = Semaphore(2)
    private val prefetches = Semaphore(1)

    suspend fun <T> decode(priority: PreviewPriority, onStarted: () -> Unit = {}, read: suspend () -> T): T =
        if (priority == PreviewPriority.Prefetch) prefetches.withPermit { admitted(onStarted, read) }
        else admitted(onStarted, read)

    private suspend fun <T> admitted(onStarted: () -> Unit, read: suspend () -> T): T = decodes.withPermit {
        currentCoroutineContext().ensureActive()
        // Run on the requesting context before dispatching native work, so promotion cannot restart an admitted request.
        onStarted()
        withContext(ioDispatcher) {
            currentCoroutineContext().ensureActive()
            read()
        }
    }
}
