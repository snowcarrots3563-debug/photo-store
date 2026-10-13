package dev.local.artistgallery.data

import dev.local.artistgallery.model.AddReferenceResult
import dev.local.artistgallery.model.ImportBatchSummary
import dev.local.artistgallery.model.MediaAssetSummary
import dev.local.artistgallery.model.ReferenceRemovalResult
import kotlinx.coroutines.flow.Flow

const val DELETE_BATCH_SIZE = 256

interface MediaLibraryRepository {
    fun observeCollectionMedia(collectionId: String): Flow<List<MediaAssetSummary>>
    fun observeArtistImages(artistId: String): Flow<List<MediaAssetSummary>>
    fun observeUnassignedMedia(): Flow<List<MediaAssetSummary>>
    suspend fun findAssetsByContent(sha256: String, byteSize: Long): List<MediaAssetEntity>
    suspend fun getAsset(id: String): MediaAssetEntity?
    suspend fun getArtistAssets(artistId: String): List<MediaAssetEntity>
    suspend fun deleteArtistIfEmpty(artistId: String): Boolean
    suspend fun getCollectionAssets(collectionId: String): List<MediaAssetEntity> { error("作品集删除仓储未接入") }
    suspend fun deleteCollectionIfEmpty(collectionId: String): Boolean { error("作品集删除仓储未接入") }
    suspend fun getAssetByStorageKey(storageKey: String): MediaAssetEntity?
    suspend fun referenceCount(assetId: String): Int
    suspend fun insertImport(batch: ImportBatchEntity, items: List<ImportItemEntity>)
    suspend fun updateImportItem(item: ImportItemEntity)
    suspend fun updateImportBatch(id: String, status: String)
    suspend fun importItemsToRecover(): List<ImportItemEntity>
    suspend fun importItems(batchId: String): List<ImportItemEntity>
    suspend fun getImportItem(batchId: String, id: String): ImportItemEntity?
    suspend fun latestImportBatches(): List<ImportBatchSummary>
    suspend fun getImportBatchResult(batchId: String): ImportBatchSummary? = latestImportBatches().firstOrNull { it.id == batchId }
    fun observeLatestImportBatches(): Flow<List<ImportBatchSummary>>
    suspend fun commitNewAsset(asset: MediaAssetEntity, targetCollectionId: String?, sourceName: String)
    suspend fun commitReusedImport(item: ImportItemEntity, assetId: String)
    suspend fun commitArchiveImport(item: ImportItemEntity, asset: MediaAssetEntity, reused: Boolean) { error("压缩导入仓储未接入") }
    suspend fun addReference(collectionId: String, assetId: String, sourceName: String? = null): AddReferenceResult
    suspend fun removeReference(collectionId: String, assetId: String): ReferenceRemovalResult
    suspend fun removeLastReferenceAndKeep(collectionId: String, assetId: String): Boolean
    suspend fun markLastReferenceForDelete(collectionId: String, assetId: String): MediaAssetEntity?
    suspend fun markAssetForDelete(assetId: String): MediaAssetEntity?
    suspend fun markAssetsForDelete(assetIds: List<String>): List<MediaAssetEntity> {
        val ids = assetIds.distinct()
        require(ids.size <= DELETE_BATCH_SIZE)
        // Compatibility for repositories that only implement the single-asset contract.
        return ids.mapNotNull { id ->
            val existing = getAsset(id)
            if (existing?.state == "PENDING_DELETE") existing else markAssetForDelete(id)
        }
    }
    suspend fun setArtistCover(artistId: String, assetId: String): Boolean
    suspend fun completeAssetDelete(assetId: String)
    suspend fun restoreAssetAfterDeleteFailure(assetId: String)
    suspend fun finishAssetDeletes(deletedIds: List<String>, failedIds: List<String>) {
        val deleted = deletedIds.distinct()
        val failed = failedIds.distinct()
        require(deleted.size + failed.size <= DELETE_BATCH_SIZE && deleted.intersect(failed.toSet()).isEmpty())
        deleted.forEach { completeAssetDelete(it) }
        failed.forEach { restoreAssetAfterDeleteFailure(it) }
    }
    suspend fun pendingDeleteAssets(): List<MediaAssetEntity>
}
