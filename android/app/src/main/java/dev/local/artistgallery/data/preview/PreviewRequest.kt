package dev.local.artistgallery.data.preview

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Owned by one page on Main; pixel size changes keep the last successful result in its caller. */
internal class PreviewRequest<K, T>(
    private val scope: CoroutineScope,
    private val decode: suspend (K, PreviewPriority, () -> Unit) -> T?,
    private val onResult: (T) -> Unit,
) {
    private enum class Stage { Waiting, Running, Ready }
    private var hasKey = false
    private var key: K? = null
    private var priority = PreviewPriority.Prefetch
    private var stage = Stage.Waiting
    private var attempt = 0L
    private var job: Job? = null

    fun update(key: K, isCurrent: Boolean) {
        val promote = hasKey && this.key == key && stage == Stage.Waiting &&
            priority == PreviewPriority.Prefetch && isCurrent
        if (hasKey && this.key == key && !promote) return
        job?.cancel()
        this.key = key
        hasKey = true
        priority = if (isCurrent) PreviewPriority.Current else PreviewPriority.Prefetch
        stage = Stage.Waiting
        val currentAttempt = ++attempt
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val result = try {
                decode(key, priority) { if (attempt == currentAttempt) stage = Stage.Running }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            currentCoroutineContext().ensureActive()
            if (attempt == currentAttempt) {
                stage = Stage.Ready
                if (result != null) onResult(result)
            }
        }
    }

    fun cancel() {
        attempt++
        job?.cancel()
    }
}
