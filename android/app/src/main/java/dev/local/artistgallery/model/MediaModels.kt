package dev.local.artistgallery.model

enum class MediaType { IMAGE, GIF, VIDEO }

data class MediaAssetSummary(
    val id: String,
    val name: String,
    val type: MediaType,
    val byteSize: Long,
    val manualOrder: Int,
    val sourceName: String,
    val storageKey: String,
    val createdAt: Long = 0L,
)

data class ImportCandidate(val uri: String, val name: String, val type: MediaType?, val error: String? = null, val skipReason: String? = null)

data class ImportProgress(
    val batchId: String,
    val currentItem: Int,
    val itemCount: Int,
    val sourceName: String,
    val copiedBytes: Long,
)

data class NewMediaAsset(
    val id: String,
    val originalName: String,
    val type: MediaType,
    val byteSize: Long,
    val sha256: String,
    val storageKey: String,
    val createdAt: Long,
)

enum class ReferenceRemovalResult { REMOVED_SHARED, LAST_REFERENCE, NOT_FOUND }

enum class AddReferenceResult { ADDED, ALREADY_LINKED, NOT_FOUND, NOT_READY }

enum class ImportItemStatus { QUEUED, COPYING, STAGED, FINALIZED, COMPLETE, REUSED, SKIPPED, FAILED, CANCELLED }

data class ImportItemSummary(
    val id: String,
    val sourceName: String,
    val status: ImportItemStatus,
    val error: String?,
    val canRetry: Boolean,
)

data class ImportBatchSummary(
    val id: String,
    val status: String,
    val items: List<ImportItemSummary>,
)
