package dev.local.artistgallery.data.deleting

import dev.local.artistgallery.data.DELETE_BATCH_SIZE
import dev.local.artistgallery.data.MediaLibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class DeleteCopiesResult(
    val deletedIds: Set<String>,
    val unavailableIds: Set<String>,
    val failedIds: Set<String>,
)

suspend fun deleteMediaCopies(
    repository: MediaLibraryRepository,
    assetIds: List<String>,
    deleteOriginal: suspend (String) -> Boolean,
    onBatch: (successIds: Set<String>, unavailableIds: Set<String>) -> Unit,
): DeleteCopiesResult {
    val allDeleted = linkedSetOf<String>()
    val allUnavailable = linkedSetOf<String>()
    val allFailed = linkedSetOf<String>()
    for (ids in assetIds.distinct().chunked(DELETE_BATCH_SIZE)) {
        currentCoroutineContext().ensureActive()
        val assets = repository.markAssetsForDelete(ids).associateBy { it.id }
        val deleted = linkedSetOf<String>()
        val unavailable = ids.filterNot { it in assets }.toSet()
        val failed = linkedSetOf<String>()
        var cancellation: CancellationException? = null
        try {
            for (id in ids) {
                currentCoroutineContext().ensureActive()
                val asset = assets[id] ?: continue
                val removed = try { deleteOriginal(asset.storageKey) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                if (removed) deleted += id else failed += id
            }
        } catch (cancelled: CancellationException) {
            cancellation = cancelled
            throw cancelled
        } finally {
            try {
                // Commit and report known results together; untouched or uncertain files stay pending.
                withContext(NonCancellable) {
                    repository.finishAssetDeletes(deleted.toList(), failed.toList())
                    allDeleted += deleted
                    allUnavailable += unavailable
                    allFailed += failed
                    onBatch(deleted.toSet(), unavailable)
                }
            } catch (error: Exception) {
                val interrupted = cancellation ?: try {
                    currentCoroutineContext().ensureActive()
                    null
                } catch (cancelled: CancellationException) { cancelled }
                if (interrupted != null) {
                    if (interrupted !== error) interrupted.addSuppressed(error)
                    throw interrupted
                }
                throw error
            }
        }
        currentCoroutineContext().ensureActive()
    }
    return DeleteCopiesResult(allDeleted, allUnavailable, allFailed)
}
