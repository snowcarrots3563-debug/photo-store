package dev.local.artistgallery.data.importing

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import dev.local.artistgallery.data.ImportBatchEntity
import dev.local.artistgallery.data.ImportItemEntity
import dev.local.artistgallery.data.MediaAssetEntity
import dev.local.artistgallery.data.MediaLibraryRepository
import dev.local.artistgallery.model.ImportBatchSummary
import dev.local.artistgallery.model.ImportCandidate
import dev.local.artistgallery.model.ImportItemStatus
import dev.local.artistgallery.model.ImportItemSummary
import dev.local.artistgallery.model.ImportProgress
import dev.local.artistgallery.model.MediaType
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.OutputStream

class MediaImportCoordinator(
    context: Context,
    private val repository: MediaLibraryRepository,
    private val files: MediaFileStore,
) {
    private val resolver: ContentResolver = context.contentResolver
    private val commitMutex = Mutex()

    suspend fun importArchiveItem(initial: ImportItemEntity, hashWhileWriting: () -> Boolean = { false }, writer: (OutputStream) -> Unit) = withContext(Dispatchers.IO) {
        when (initial.status) {
            "STAGED" -> finishStaged(initial)
            "FINALIZED" -> withContext(NonCancellable) { finishFinalized(initial) }
            else -> {
                if (initial.sha256 != null && initial.byteSize != null && files.hasOriginal(initial.storageKey)) {
                    withContext(NonCancellable) { finishFinalized(initial) }
                    return@withContext
                }
                val copying = initial.copy(status = "COPYING", errorMessage = null, updatedAt = System.currentTimeMillis())
                repository.updateImportItem(copying)
                val staged = files.writeToStaging(copying.stagingKey, hashWhileWriting = hashWhileWriting(), writer = writer)
                if (skipSmallFile(copying, staged.byteSize)) return@withContext
                try { files.validateStagedMedia(copying.stagingKey, copying.mediaType) }
                catch (error: IOException) { throw dev.local.artistgallery.data.archive.ArchiveReadException(dev.local.artistgallery.data.archive.ArchiveError.CORRUPT, "媒体文件无法识别或已经损坏。", error) }
                // Persist and commit after verified extraction even if cancel arrives at this boundary.
                withContext(NonCancellable) {
                    val item = copying.copy(status = "STAGED", sha256 = staged.sha256, byteSize = staged.byteSize, updatedAt = System.currentTimeMillis())
                    repository.updateImportItem(item)
                    finishStaged(item, staged)
                }
            }
        }
    }

    suspend fun deleteOriginal(storageKey: String): Boolean = files.deleteOriginal(storageKey)

    suspend fun previewUris(uris: List<Uri>): List<ImportCandidate> = withContext(Dispatchers.IO) {
        uris.map { candidate(it) }
    }

    suspend fun importUris(
        uris: List<Uri>,
        targetCollectionId: String?,
        onProgress: (ImportProgress) -> Unit,
    ): ImportBatchSummary = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) throw IllegalArgumentException("没有选择媒体文件")
        val now = System.currentTimeMillis()
        val batchId = UUID.randomUUID().toString()
        val candidates = uris.map { candidate(it) }
        val items = candidates.map { candidate ->
            val uri = Uri.parse(candidate.uri)
            val persisted = candidate.error == null && candidate.skipReason == null && takePersistablePermission(uri)
            ImportItemEntity(
                id = UUID.randomUUID().toString(),
                batchId = batchId,
                targetCollectionId = targetCollectionId,
                sourceUri = uri.toString(),
                hasPersistedPermission = persisted,
                sourceName = candidate.name,
                mediaType = candidate.type?.name ?: "UNKNOWN",
                stagingKey = UUID.randomUUID().toString(),
                storageKey = UUID.randomUUID().toString(),
                sha256 = null,
                byteSize = null,
                status = when {
                    candidate.skipReason != null -> ImportItemStatus.SKIPPED.name
                    candidate.error != null -> ImportItemStatus.FAILED.name
                    else -> ImportItemStatus.QUEUED.name
                },
                errorMessage = candidate.error ?: candidate.skipReason,
                createdAt = now,
                updatedAt = now,
            )
        }
        try {
            try {
                repository.insertImport(ImportBatchEntity(batchId, targetCollectionId, "COPYING", now, now), items)
            } catch (error: Exception) {
                if (error !is CancellationException) items.forEach(::releasePermission)
                throw error
            }
            onProgress(ImportProgress(batchId, 1, items.size, items.first().sourceName, 0))
            items.forEachIndexed { index, item ->
                var copiedBytes = 0L
                if (item.status == ImportItemStatus.QUEUED.name) {
                    processItem(item) { bytes ->
                        copiedBytes = bytes
                        onProgress(ImportProgress(batchId, index + 1, items.size, item.sourceName, bytes, completedItems = index))
                    }
                }
                onProgress(ImportProgress(batchId, index + 1, items.size, item.sourceName, copiedBytes, completedItems = index + 1))
            }
        } catch (cancelled: CancellationException) {
            interruptBatch(batchId)
            throw cancelled
        }
        updateBatchStatus(batchId)
        summary(batchId)
    }

    suspend fun retryFailed(batchId: String, onProgress: (ImportProgress) -> Unit): ImportBatchSummary = withContext(Dispatchers.IO) {
        val allItems = repository.importItems(batchId)
        if (allItems.isEmpty()) throw IllegalArgumentException("导入批次不存在")
        val failed = allItems.filter {
            it.status in RETRYABLE_STATUSES && (it.hasPersistedPermission || (it.sha256 != null && it.byteSize != null))
        }
        try {
            onProgress(ImportProgress(batchId, if (failed.isEmpty()) 0 else 1, failed.size, failed.firstOrNull()?.sourceName ?: "正在准备重试…", 0))
            failed.forEachIndexed { index, old ->
                var copiedBytes = 0L
                try {
                    when {
                        old.status == ImportItemStatus.STAGED.name -> finishStaged(old)
                        old.status == ImportItemStatus.FINALIZED.name -> finishFinalized(old)
                        old.status == ImportItemStatus.FAILED.name && files.hasOriginal(old.storageKey) && old.sha256 != null && old.byteSize != null ->
                            finishFinalized(old.copy(status = ImportItemStatus.FINALIZED.name))
                        !old.hasPersistedPermission -> repository.updateImportItem(old.copy(errorMessage = "来源授权已失效，请重新选择该文件后导入。", updatedAt = System.currentTimeMillis()))
                        else -> {
                            val item = old.copy(status = ImportItemStatus.QUEUED.name, errorMessage = null, updatedAt = System.currentTimeMillis())
                            repository.updateImportItem(item)
                            processItem(item) { bytes ->
                                copiedBytes = bytes
                                onProgress(ImportProgress(batchId, index + 1, failed.size, item.sourceName, bytes, completedItems = index))
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val latest = repository.importItems(batchId).firstOrNull { it.id == old.id } ?: old
                    if (latest.status !in SUCCESS_STATUSES && latest.status != ImportItemStatus.SKIPPED.name) {
                        repository.updateImportItem(latest.copy(status = ImportItemStatus.FAILED.name, errorMessage = error.message ?: "重试失败。", updatedAt = System.currentTimeMillis()))
                    }
                }
                onProgress(ImportProgress(batchId, index + 1, failed.size, old.sourceName, copiedBytes, completedItems = index + 1))
            }
        } catch (cancelled: CancellationException) {
            interruptBatch(batchId)
            throw cancelled
        }
        updateBatchStatus(batchId)
        summary(batchId)
    }

    private suspend fun interruptBatch(batchId: String) = withContext(NonCancellable) {
        repository.importItems(batchId).filter { it.status in setOf("QUEUED", "COPYING") }.forEach { item ->
            files.deleteStaging(item.stagingKey)
            repository.updateImportItem(item.copy(status = "FAILED", errorMessage = "导入曾被中断，可重试；若来源授权已失效，请重新选择文件。", updatedAt = System.currentTimeMillis()))
        }
        repository.updateImportBatch(batchId, "INTERRUPTED")
    }

    suspend fun recoverPending() = withContext(Dispatchers.IO) {
        repository.pendingDeleteAssets().forEach { asset ->
            if (files.deleteOriginal(asset.storageKey)) repository.completeAssetDelete(asset.id)
            else repository.restoreAssetAfterDeleteFailure(asset.id)
        }
        repository.importItemsToRecover().forEach { item ->
            if (item.archiveGroupId != null && item.status in setOf("QUEUED", "COPYING")) {
                files.deleteStaging(item.stagingKey)
                repository.updateImportItem(item.copy(status = "FAILED", errorMessage = "压缩导入曾被中断，请从压缩导入记录继续。", updatedAt = System.currentTimeMillis()))
                return@forEach
            }
            try {
                when (item.status) {
                    ImportItemStatus.STAGED.name -> finishStaged(item)
                    ImportItemStatus.FINALIZED.name -> finishFinalized(item)
                    else -> {
                        files.deleteStaging(item.stagingKey)
                        repository.updateImportItem(item.copy(status = ImportItemStatus.FAILED.name, errorMessage = "导入曾被中断，可重试；若来源授权已失效，请重新选择文件。", updatedAt = System.currentTimeMillis()))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val latest = repository.importItems(item.batchId).firstOrNull { it.id == item.id } ?: item
                if (latest.status != ImportItemStatus.FINALIZED.name) files.deleteStaging(item.stagingKey)
                if (latest.status != ImportItemStatus.COMPLETE.name && latest.status != ImportItemStatus.REUSED.name) {
                    repository.updateImportItem(latest.copy(status = ImportItemStatus.FAILED.name, errorMessage = error.message ?: "恢复导入失败。", updatedAt = System.currentTimeMillis()))
                }
            }
            updateBatchStatus(item.batchId)
        }
    }

    private suspend fun processItem(initial: ImportItemEntity, onProgress: (Long) -> Unit) {
        var item = initial.copy(status = ImportItemStatus.COPYING.name, errorMessage = null, updatedAt = System.currentTimeMillis())
        repository.updateImportItem(item)
        try {
            val expectedBytes = sourceLength(Uri.parse(item.sourceUri))
            if (expectedBytes > files.availableBytes()) throw IOException("可用空间不足，未导入此文件。")
            val staged = files.copyToStaging(Uri.parse(item.sourceUri), item.stagingKey, expectedBytes, onProgress)
            item = item.copy(status = ImportItemStatus.STAGED.name, sha256 = staged.sha256, byteSize = staged.byteSize, updatedAt = System.currentTimeMillis())
            repository.updateImportItem(item)
            finishStaged(item)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val latest = repository.importItems(item.batchId).firstOrNull { it.id == item.id } ?: item
            if (latest.status != ImportItemStatus.FINALIZED.name) files.deleteStaging(item.stagingKey)
            if (latest.status != ImportItemStatus.FINALIZED.name && latest.status != ImportItemStatus.COMPLETE.name && latest.status != ImportItemStatus.REUSED.name) {
                repository.updateImportItem(latest.copy(status = ImportItemStatus.FAILED.name, errorMessage = error.message ?: "读取或保存失败。", updatedAt = System.currentTimeMillis()))
            }
        }
    }

    private suspend fun finishStaged(item: ImportItemEntity, verifiedStaged: StagedFile? = null) =
        commitMutex.withLock { finishStagedLocked(item, verifiedStaged) }

    private suspend fun finishStagedLocked(item: ImportItemEntity, verifiedStaged: StagedFile?) {
        if (!files.hasStaging(item.stagingKey) && files.hasOriginal(item.storageKey)) {
            val promoted = files.inspectOriginal(item.storageKey)
            if (promoted.sha256 != item.sha256 || promoted.byteSize != item.byteSize) throw IOException("已提交原图核验失败。")
            val finalized = item.copy(status = ImportItemStatus.FINALIZED.name, updatedAt = System.currentTimeMillis())
            repository.updateImportItem(finalized)
            finishFinalized(finalized)
            return
        }
        // Only this just-completed, synced write can reuse its hash result; retries inspect disk again.
        val staged = verifiedStaged ?: files.inspectStaging(item.stagingKey)
        if (staged.sha256 != item.sha256 || staged.byteSize != item.byteSize) throw IOException("暂存文件核验失败。")
        if (skipSmallFile(item, staged.byteSize)) return
        val duplicates = repository.findAssetsByContent(staged.sha256, staged.byteSize)
        for (existing in duplicates) {
            if (!files.matchesOriginal(item.stagingKey, existing.storageKey)) continue
            if (item.archiveGroupId != null) repository.commitArchiveImport(item, existing, true)
            else repository.commitReusedImport(item, existing.id)
            files.deleteStaging(item.stagingKey)
            releasePermission(item)
            return
        }
        files.promote(item.stagingKey, item.storageKey)
        val finalized = item.copy(status = ImportItemStatus.FINALIZED.name, updatedAt = System.currentTimeMillis())
        repository.updateImportItem(finalized)
        finishFinalized(finalized)
    }

    private suspend fun finishFinalized(item: ImportItemEntity) {
        val expectedHash = item.sha256 ?: throw IOException("导入记录缺少文件校验值。")
        val expectedSize = item.byteSize ?: throw IOException("导入记录缺少文件大小。")
        val current = files.inspectOriginal(item.storageKey)
        if (current.sha256 != expectedHash || current.byteSize != expectedSize) throw IOException("图库文件核验失败。")
        val existing = repository.getAsset(item.id)
        if (item.archiveGroupId != null) {
            repository.commitArchiveImport(item, existing ?: MediaAssetEntity(item.id, item.sourceName, item.mediaType, expectedSize, null, null, expectedHash, item.storageKey, "READY", item.createdAt), false)
            return
        }
        if (existing == null) {
            repository.commitNewAsset(
                MediaAssetEntity(item.id, item.sourceName, item.mediaType, expectedSize, null, null, expectedHash, item.storageKey, "READY", item.createdAt),
                item.targetCollectionId,
                item.sourceName,
            )
        } else if (item.targetCollectionId != null) {
            repository.addReference(item.targetCollectionId, existing.id, item.sourceName)
        }
        repository.updateImportItem(item.copy(status = ImportItemStatus.COMPLETE.name, errorMessage = null, updatedAt = System.currentTimeMillis()))
        releasePermission(item)
    }

    private suspend fun describe(uri: Uri): Triple<String, String, MediaType> {
        val mime = resolver.getType(uri)?.lowercase().orEmpty()
        val name = queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank) ?: "未命名媒体"
        val type = when {
            mime == "image/gif" || name.endsWith(".gif", ignoreCase = true) -> MediaType.GIF
            mime.startsWith("image/") || name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS -> MediaType.IMAGE
            mime.startsWith("video/") || name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS -> MediaType.VIDEO
            else -> throw IOException("不支持的媒体类型：$name")
        }
        return Triple(name, mime, type)
    }

    private suspend fun candidate(uri: Uri): ImportCandidate = try {
        val (name, _, type) = describe(uri)
        ImportCandidate(uri.toString(), name, type, skipReason = MediaImportPolicy.skipReason(sourceLength(uri)))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank) ?: "无法读取的媒体"
        ImportCandidate(uri.toString(), name, null, error.message ?: "无法读取所选媒体。")
    }

    private suspend fun skipSmallFile(item: ImportItemEntity, byteSize: Long): Boolean {
        val reason = MediaImportPolicy.skipReason(byteSize) ?: return false
        files.deleteStaging(item.stagingKey)
        repository.updateImportItem(item.copy(status = ImportItemStatus.SKIPPED.name, byteSize = byteSize, errorMessage = reason, updatedAt = System.currentTimeMillis()))
        releasePermission(item)
        return true
    }

    private suspend fun queryDisplayName(uri: Uri): String? = withContext(Dispatchers.IO) {
        var cursor: Cursor? = null
        try {
            cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor?.moveToFirst() == true) cursor.getString(0) else null
        } finally {
            cursor?.close()
        }
    }

    private suspend fun sourceLength(uri: Uri): Long = withContext(Dispatchers.IO) {
        try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (_: FileNotFoundException) {
            throw IOException("所选文件已不可用，请重新选择。")
        }
    }

    private fun takePersistablePermission(uri: Uri): Boolean = try {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun releasePermission(item: ImportItemEntity) {
        if (!item.hasPersistedPermission) return
        try {
            resolver.releasePersistableUriPermission(Uri.parse(item.sourceUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // The provider may already have revoked this grant.
        } catch (_: IllegalArgumentException) {
            // Some providers do not expose persistable grants.
        }
    }

    private suspend fun updateBatchStatus(batchId: String) {
        val items = repository.importItems(batchId)
        val terminal = items.all { it.status in TERMINAL_STATUSES }
        val hasFailure = items.any { it.status == ImportItemStatus.FAILED.name }
        val hasSkipped = items.any { it.status == ImportItemStatus.SKIPPED.name }
        val status = when {
            !terminal -> "INTERRUPTED"
            hasFailure && items.any { it.status in SUCCESS_STATUSES } -> "PARTIAL"
            hasFailure -> "FAILED"
            hasSkipped -> "PARTIAL"
            else -> "COMPLETED"
        }
        repository.updateImportBatch(batchId, status)
    }

    private suspend fun summary(batchId: String): ImportBatchSummary {
        val status = repository.latestImportBatches().firstOrNull { it.id == batchId }?.status ?: "FAILED"
        val items = repository.importItems(batchId).map {
            ImportItemSummary(it.id, it.sourceName, runCatching { ImportItemStatus.valueOf(it.status) }.getOrDefault(ImportItemStatus.FAILED), it.errorMessage,
                it.hasPersistedPermission || (it.sha256 != null && it.byteSize != null))
        }
        return ImportBatchSummary(batchId, status, items)
    }

    private companion object {
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp", "heic", "heif", "avif")
        val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp")
        val SUCCESS_STATUSES = setOf(ImportItemStatus.COMPLETE.name, ImportItemStatus.REUSED.name)
        val TERMINAL_STATUSES = SUCCESS_STATUSES + setOf(ImportItemStatus.SKIPPED.name, ImportItemStatus.FAILED.name, ImportItemStatus.CANCELLED.name)
        val RETRYABLE_STATUSES = setOf(ImportItemStatus.FAILED.name, ImportItemStatus.STAGED.name, ImportItemStatus.FINALIZED.name)
    }
}
