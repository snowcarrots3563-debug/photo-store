package dev.local.artistgallery.data.importing

import dev.local.artistgallery.data.GalleryRepository
import dev.local.artistgallery.model.ImportBatchSummary
import dev.local.artistgallery.model.ImportProgress
import dev.local.artistgallery.model.SaveResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MediaImportSessionState(
    val running: Boolean = false,
    val attempt: Long = 0,
    val progress: ImportProgress? = null,
    val result: ImportBatchSummary? = null,
    val error: String? = null,
)

/** One application-owned ordinary-media attempt, observed without owning its lifetime. */
class MediaImportSession(
    private val scope: CoroutineScope,
    private val repository: GalleryRepository,
    private val importUris: suspend (List<String>, String?, (ImportProgress) -> Unit) -> ImportBatchSummary,
    private val retryFailed: suspend (String, (ImportProgress) -> Unit) -> ImportBatchSummary,
    private val foregroundController: ImportWorkController? = null,
    private val awaitRecovery: suspend () -> Unit = {},
    private val onImportCompleted: (ImportCompletion) -> Unit = {},
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(MediaImportSessionState())
    val state = mutableState.asStateFlow()
    val isRunning: Boolean get() = state.value.running

    fun start(uris: List<String>, targetCollectionId: String?, defaultArtistId: String?): Boolean {
        if (uris.isEmpty()) return false
        return run("正在导入媒体") { progress ->
            val target = if (defaultArtistId == null) targetCollectionId else when (val result = repository.ensureDefaultCollection(defaultArtistId)) {
                is SaveResult.Saved -> result.id
                SaveResult.NotFound -> throw IllegalStateException("画师不存在，请重新选择导入位置。")
                else -> throw IllegalStateException("无法准备默认图集，请稍后重试。")
            }
            importUris(uris, target, progress)
        }
    }

    fun retry(batchId: String): Boolean = run("正在重试媒体") { progress -> retryFailed(batchId, progress) }

    /** A finished outcome stays available until exactly one observer handles it. */
    fun acknowledge(attempt: Long): Boolean = synchronized(lock) {
        val current = state.value
        if (current.running || current.attempt != attempt || current.result == null && current.error == null) false
        else { mutableState.value = current.copy(result = null, error = null); true }
    }

    private fun run(title: String, block: suspend ((ImportProgress) -> Unit) -> ImportBatchSummary): Boolean {
        val work = synchronized(lock) {
            if (isRunning || state.value.result != null || state.value.error != null) return false
            val attempt = state.value.attempt + 1
            mutableState.value = MediaImportSessionState(running = true, attempt = attempt)
            val handle = try { foregroundController?.begin(ImportWorkProgress(title, "正在准备…")) }
            catch (error: RuntimeException) {
                finish(attempt, error = error.message ?: "无法启动后台导入，请返回应用后重试。")
                return true
            }
            scope.launch(start = CoroutineStart.LAZY) {
                var result: ImportBatchSummary? = null
                var failure: String? = null
                try {
                    handle?.awaitForeground()
                    awaitRecovery()
                    val completed = block { progress ->
                        synchronized(lock) {
                            if (state.value.attempt == attempt && isRunning) mutableState.value = state.value.copy(progress = progress)
                        }
                        handle?.update(ImportWorkProgress(title, "${progress.sourceName} · ${progress.copiedBytes / 1024} KiB",
                            completed = progress.completedItems.toLong(), total = progress.itemCount.toLong(),
                            target = progress.batchId.takeIf(String::isNotBlank)?.let { ImportResultTarget(ImportResultKind.Media, it) }))
                    }
                    result = completed
                    val completion = completed.toImportCompletion()
                    if (!completion.cancelled) try { onImportCompleted(completion) } catch (_: RuntimeException) { }
                } catch (cancelled: CancellationException) {
                    failure = interruptionMessage(cancelled)
                    throw cancelled
                } catch (error: Exception) {
                    failure = error.message ?: "导入失败，请重试。"
                } finally {
                    handle?.close()
                    finish(attempt, result, failure)
                }
            }.also { job ->
                handle?.attach(job)
                // A LAZY job can be interrupted before its body starts.
                job.invokeOnCompletion { cause ->
                    handle?.close()
                    if (cause != null) finish(attempt, error = interruptionMessage(cause))
                }
            }
        }
        work.start()
        return true
    }

    private fun finish(attempt: Long, result: ImportBatchSummary? = null, error: String? = null) = synchronized(lock) {
        if (state.value.attempt == attempt && isRunning) mutableState.value = state.value.copy(running = false, progress = null, result = result, error = error)
    }

    private fun interruptionMessage(error: Throwable): String =
        if (error is ImportWorkInterruptedException) error.message ?: "后台导入已中断，请继续或重试。"
        else "导入已中断，已保存的媒体保留；请继续或重试。"
}
