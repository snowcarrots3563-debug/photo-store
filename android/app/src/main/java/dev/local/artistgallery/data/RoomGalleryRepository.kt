package dev.local.artistgallery.data

import androidx.room.withTransaction
import dev.local.artistgallery.model.ArtistDetail
import dev.local.artistgallery.model.ArtistSummary
import dev.local.artistgallery.model.ArtistHeader
import dev.local.artistgallery.model.CollectionSummary
import dev.local.artistgallery.model.CoverRef
import dev.local.artistgallery.model.AddReferenceResult
import dev.local.artistgallery.model.ImportBatchSummary
import dev.local.artistgallery.model.ImportBatchHistorySummary
import dev.local.artistgallery.model.ImportItemStatus
import dev.local.artistgallery.model.ImportItemSummary
import dev.local.artistgallery.model.MediaAssetSummary
import dev.local.artistgallery.model.MediaType
import dev.local.artistgallery.model.ReferenceRemovalResult
import dev.local.artistgallery.model.SaveResult
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class RoomGalleryRepository(private val database: GalleryDatabase,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default) : GalleryRepository, MediaLibraryRepository {
    private val dao = database.galleryDao()

    override fun observeArtists(): Flow<List<ArtistSummary>> =
        dao.observeArtists().map { rows -> rows.map { it.summary() } }.flowOn(computationDispatcher)

    override fun observeArtist(id: String): Flow<ArtistDetail?> =
        combine(dao.observeArtist(id), dao.observeCollectionsForArtist(id)) { row, collections ->
            row?.let { ArtistDetail(it.summary(), collections.map { collection -> collection.summary() }) }
        }.flowOn(computationDispatcher)

    override fun observeCollection(id: String): Flow<CollectionSummary?> =
        dao.observeCollection(id).map { it?.summary() }.flowOn(computationDispatcher)

    override fun observeArtistHeader(id: String): Flow<ArtistHeader?> =
        dao.observeArtistHeader(id).distinctUntilChanged().flowOn(computationDispatcher)

    override suspend fun artistExists(id: String): Boolean = withContext(Dispatchers.IO) { dao.getArtist(id) != null }

    override suspend fun collectionExists(id: String): Boolean = withContext(Dispatchers.IO) { dao.getCollection(id) != null }

    override suspend fun createArtist(name: String): SaveResult = save {
        val normalized = NameRules.normalize(name)
        if (normalized.isEmpty()) return@save SaveResult.InvalidName
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.insertArtist(ArtistEntity(id, normalized, now, now))
        defaultCollection(id, now)
        SaveResult.Saved(id)
    }

    override suspend fun ensureDefaultCollection(artistId: String): SaveResult = save {
        if (dao.getArtist(artistId) == null) return@save SaveResult.NotFound
        SaveResult.Saved(defaultCollection(artistId, System.currentTimeMillis()).id)
    }

    private suspend fun defaultCollection(artistId: String, now: Long): CollectionEntity {
        val collections = dao.getCollections(artistId)
        collections.firstOrNull { it.isDefault }?.let { return it }
        val name = NameRules.nextDefaultName(collections.map { it.name })
        val collection = CollectionEntity(UUID.randomUUID().toString(), artistId, name, false, now, now, isDefault = true)
        dao.insertCollection(collection)
        return collection
    }

    override suspend fun renameArtist(id: String, name: String): SaveResult = save {
        val current = dao.getArtist(id) ?: return@save SaveResult.NotFound
        val normalized = NameRules.normalize(name)
        if (normalized.isEmpty()) return@save SaveResult.InvalidName
        dao.updateArtist(current.copy(name = normalized, updatedAt = System.currentTimeMillis()))
        SaveResult.Saved(id)
    }

    override suspend fun createCollection(artistId: String, name: String): SaveResult = save {
        if (dao.getArtist(artistId) == null) return@save SaveResult.NotFound
        val normalized = NameRules.normalize(name)
        val names = dao.getCollections(artistId).map { it.name }
        val temporary = normalized.isEmpty()
        val resolved = if (temporary) NameRules.nextTemporaryName(names) else normalized
        if (NameRules.isDuplicate(resolved, names)) return@save SaveResult.DuplicateCollectionName
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.insertCollection(CollectionEntity(id, artistId, resolved, temporary, now, now))
        SaveResult.Saved(id)
    }

    override suspend fun renameCollection(id: String, name: String): SaveResult = save {
        val current = dao.getCollection(id) ?: return@save SaveResult.NotFound
        val normalized = NameRules.normalize(name)
        val collections = dao.getCollections(current.artistId)
        val temporary = normalized.isEmpty()
        val resolved = if (temporary) NameRules.nextTemporaryName(collections.map { it.name }) else normalized
        if (NameRules.isDuplicate(resolved, collections.filter { it.id != id }.map { it.name })) {
            return@save SaveResult.DuplicateCollectionName
        }
        dao.updateCollection(current.copy(name = resolved, isTemporary = temporary, updatedAt = System.currentTimeMillis()))
        SaveResult.Saved(id)
    }

    override fun observeCollectionMedia(collectionId: String): Flow<List<MediaAssetSummary>> =
        dao.observeCollectionMedia(collectionId).map { rows -> rows.map { it.summary() } }.distinctUntilChanged().flowOn(computationDispatcher)

    override fun observeArtistImages(artistId: String): Flow<List<MediaAssetSummary>> =
        dao.observeArtistImages(artistId).map { rows -> rows.map { it.summary() } }.distinctUntilChanged().flowOn(computationDispatcher)

    override fun observeUnassignedMedia(): Flow<List<MediaAssetSummary>> =
        dao.observeUnassignedMedia().map { rows -> rows.map { it.summary() } }.distinctUntilChanged().flowOn(computationDispatcher)

    override suspend fun findAssetsByContent(sha256: String, byteSize: Long): List<MediaAssetEntity> =
        withContext(Dispatchers.IO) { dao.findAssetsByContent(sha256, byteSize) }

    override suspend fun getAsset(id: String): MediaAssetEntity? = withContext(Dispatchers.IO) { dao.getAsset(id) }

    override suspend fun getArtistAssets(artistId: String): List<MediaAssetEntity> =
        withContext(Dispatchers.IO) { dao.getArtistAssets(artistId) }

    override suspend fun deleteArtistIfEmpty(artistId: String): Boolean = withContext(Dispatchers.IO) {
        database.withTransaction {
            if (dao.getArtist(artistId) == null || dao.artistHasMedia(artistId)) return@withTransaction false
            dao.deleteArtistCollections(artistId)
            dao.deleteArtist(artistId) == 1
        }
    }

    override suspend fun getCollectionAssets(collectionId: String): List<MediaAssetEntity> =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                if (dao.getCollection(collectionId)?.isDefault == true) throw IOException("默认图集不能单独删除。")
                dao.getCollectionAssets(collectionId)
            }
        }

    override suspend fun deleteCollectionIfEmpty(collectionId: String): Boolean = withContext(Dispatchers.IO) {
        database.withTransaction {
            val collection = dao.getCollection(collectionId) ?: return@withTransaction false
            if (collection.isDefault || dao.collectionHasMedia(collectionId)) return@withTransaction false
            dao.deleteCollection(collectionId) == 1
        }
    }

    override suspend fun getAssetByStorageKey(storageKey: String): MediaAssetEntity? =
        withContext(Dispatchers.IO) { dao.getAssetByStorageKey(storageKey) }

    override suspend fun referenceCount(assetId: String): Int = withContext(Dispatchers.IO) { dao.referenceCount(assetId) }

    override suspend fun insertImport(batch: ImportBatchEntity, items: List<ImportItemEntity>) = withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.insertImportBatch(batch)
            dao.insertImportItems(items)
        }
    }

    override suspend fun updateImportItem(item: ImportItemEntity) = withContext(Dispatchers.IO) { dao.updateImportItem(item) }

    override suspend fun updateImportBatch(id: String, status: String) = withContext(Dispatchers.IO) {
        dao.updateImportBatch(id, status, System.currentTimeMillis())
    }

    override suspend fun importItemsToRecover(): List<ImportItemEntity> = withContext(Dispatchers.IO) { dao.importItemsToRecover() }

    override suspend fun importItems(batchId: String): List<ImportItemEntity> = withContext(Dispatchers.IO) { dao.importItems(batchId) }

    override suspend fun getImportItem(batchId: String, id: String): ImportItemEntity? =
        withContext(Dispatchers.IO) { dao.getImportItem(batchId, id) }

    override suspend fun latestImportBatches(): List<ImportBatchSummary> = withContext(Dispatchers.IO) {
        dao.latestImportBatches().map { batch -> batch.summary() }
    }

    override suspend fun getImportBatchResult(batchId: String): ImportBatchSummary? = withContext(Dispatchers.IO) {
        dao.getImportBatchResult(batchId)?.summary()
    }

    override fun observeLatestImportBatches(): Flow<List<ImportBatchHistorySummary>> =
        dao.observeLatestImportBatches().distinctUntilChanged()

    override fun observeImportBatchResult(batchId: String): Flow<ImportBatchSummary?> =
        dao.observeImportBatchResult(batchId).map { it?.summary() }.distinctUntilChanged().flowOn(computationDispatcher)

    private fun ImportBatchWithItems.summary() = ImportBatchSummary(
        batch.id,
        batch.status,
        items.map { item ->
            ImportItemSummary(item.id, item.sourceName, runCatching { ImportItemStatus.valueOf(item.status) }.getOrDefault(ImportItemStatus.FAILED), item.errorMessage,
                item.hasPersistedPermission || (item.sha256 != null && item.byteSize != null))
        },
    )

    override suspend fun commitNewAsset(asset: MediaAssetEntity, targetCollectionId: String?, sourceName: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            if (targetCollectionId != null && !dao.collectionExists(targetCollectionId)) throw IllegalStateException("目标作品集不存在")
            dao.insertAsset(asset)
            if (targetCollectionId != null) {
                dao.insertCollectionMedia(CollectionMediaEntity(targetCollectionId, asset.id, dao.nextManualOrder(targetCollectionId), sourceName, null))
            }
        }
    }

    override suspend fun commitReusedImport(item: ImportItemEntity, assetId: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val asset = dao.getAsset(assetId) ?: throw IOException("图库原图不存在。")
            if (asset.state != ASSET_READY) throw IOException("图库原图不可用。")
            val status = if (item.targetCollectionId == null) {
                if (dao.referenceCount(assetId) > 0) ImportItemStatus.SKIPPED else ImportItemStatus.REUSED
            } else {
                if (!dao.collectionExists(item.targetCollectionId)) throw IOException("目标作品集不存在。")
                if (dao.referenceExists(item.targetCollectionId, assetId)) ImportItemStatus.SKIPPED else {
                    dao.insertCollectionMedia(CollectionMediaEntity(item.targetCollectionId, assetId, dao.nextManualOrder(item.targetCollectionId), item.sourceName, null))
                    ImportItemStatus.REUSED
                }
            }
            dao.updateImportItem(item.copy(status = status.name, errorMessage = null, updatedAt = System.currentTimeMillis()))
        }
    }

    override suspend fun addReference(collectionId: String, assetId: String, sourceName: String?): AddReferenceResult = withContext(Dispatchers.IO) {
        database.withTransaction {
            val asset = dao.getAsset(assetId) ?: return@withTransaction AddReferenceResult.NOT_FOUND
            if (asset.state != ASSET_READY || !dao.collectionExists(collectionId)) return@withTransaction AddReferenceResult.NOT_READY
            if (dao.referenceExists(collectionId, assetId)) return@withTransaction AddReferenceResult.ALREADY_LINKED
            dao.insertCollectionMedia(CollectionMediaEntity(collectionId, assetId, dao.nextManualOrder(collectionId), sourceName ?: asset.originalName, null))
            AddReferenceResult.ADDED
        }
    }

    override suspend fun commitArchiveImport(item: ImportItemEntity, asset: MediaAssetEntity, reused: Boolean) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val latest = dao.getImportItem(item.batchId, item.id) ?: throw IOException("导入条目不存在。")
            if (latest.status in setOf("COMPLETE", "REUSED", "SKIPPED")) return@withTransaction
            val group = dao.archiveGroup(requireNotNull(latest.archiveGroupId)) ?: throw IOException("导入分组不存在。")
            if (group.batchId != latest.batchId) throw IOException("条目与导入分组不匹配。")
            if (dao.getArtist(group.artistId) == null) throw IOException("目标画师不存在。")
            val target = latest.targetCollectionId ?: group.id
            val collection = dao.getCollection(target)
            if (latest.targetCollectionId != null && collection == null) throw IOException("目标作品集不存在。")
            if (collection != null && collection.artistId != group.artistId) throw IOException("目标作品集不属于此画师。")
            if (collection == null) {
                // Resolve any name taken after preview, in the same transaction as the first reference.
                val names = dao.getCollections(group.artistId).map { it.name }.toSet()
                var name = group.name
                var suffix = 2
                while (name in names) name = "${group.name} (${suffix++})"
                val now = System.currentTimeMillis()
                dao.insertCollection(CollectionEntity(group.id, group.artistId, name, false, now, now))
            }
            if (reused) {
                if (dao.getAsset(asset.id)?.state != ASSET_READY) throw IOException("图库原图不可用。")
            } else if (dao.getAsset(asset.id) == null) dao.insertAsset(asset)
            val linked = dao.referenceExists(target, asset.id)
            if (!linked) dao.insertCollectionMedia(CollectionMediaEntity(target, asset.id, dao.nextManualOrder(target), latest.sourceName, latest.sourceRelativePath))
            dao.updateImportItem(latest.copy(targetCollectionId = target, status = if (linked) "SKIPPED" else if (reused) "REUSED" else "COMPLETE", errorMessage = if (linked) "同一作品集已有相同内容。" else null, updatedAt = System.currentTimeMillis()))
        }
    }

    override suspend fun removeReference(collectionId: String, assetId: String): ReferenceRemovalResult = withContext(Dispatchers.IO) {
        database.withTransaction {
            if (!dao.referenceExists(collectionId, assetId)) return@withTransaction ReferenceRemovalResult.NOT_FOUND
            if (dao.referenceCount(assetId) <= 1) return@withTransaction ReferenceRemovalResult.LAST_REFERENCE
            dao.deleteReference(collectionId, assetId)
            ReferenceRemovalResult.REMOVED_SHARED
        }
    }

    override suspend fun removeLastReferenceAndKeep(collectionId: String, assetId: String): Boolean = withContext(Dispatchers.IO) {
        database.withTransaction {
            if (!dao.referenceExists(collectionId, assetId) || dao.referenceCount(assetId) != 1) return@withTransaction false
            dao.deleteReference(collectionId, assetId) == 1
        }
    }

    override suspend fun markLastReferenceForDelete(collectionId: String, assetId: String): MediaAssetEntity? = withContext(Dispatchers.IO) {
        database.withTransaction {
            if (!dao.referenceExists(collectionId, assetId) || dao.referenceCount(assetId) != 1) return@withTransaction null
            if (dao.markAssetPendingDelete(assetId) != 1) return@withTransaction null
            dao.getAsset(assetId)
        }
    }

    override suspend fun markAssetForDelete(assetId: String): MediaAssetEntity? = withContext(Dispatchers.IO) {
        database.withTransaction {
            if (dao.markAssetPendingDelete(assetId) != 1) return@withTransaction null
            dao.getAsset(assetId)
        }
    }

    override suspend fun markAssetsForDelete(assetIds: List<String>): List<MediaAssetEntity> = withContext(Dispatchers.IO) {
        val ids = assetIds.distinct()
        require(ids.size <= DELETE_BATCH_SIZE)
        if (ids.isEmpty()) return@withContext emptyList()
        database.withTransaction {
            dao.markAssetsPendingDelete(ids)
            dao.pendingAssetsForDelete(ids)
        }
    }

    override suspend fun setArtistCover(artistId: String, assetId: String): Boolean = withContext(Dispatchers.IO) {
        dao.setArtistCover(artistId, assetId, System.currentTimeMillis()) == 1
    }

    override suspend fun completeAssetDelete(assetId: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.clearArtistCover(assetId)
            dao.deleteCollectionsEmptiedByAssets(listOf(assetId))
            dao.deleteAllReferences(assetId)
            dao.deleteAsset(assetId)
        }
        Unit
    }

    override suspend fun restoreAssetAfterDeleteFailure(assetId: String) = withContext(Dispatchers.IO) {
        dao.restoreAsset(assetId)
        Unit
    }

    override suspend fun finishAssetDeletes(deletedIds: List<String>, failedIds: List<String>) = withContext(Dispatchers.IO) {
        val deleted = deletedIds.distinct()
        val failed = failedIds.distinct()
        require(deleted.size + failed.size <= DELETE_BATCH_SIZE && deleted.intersect(failed.toSet()).isEmpty())
        if (deleted.isNotEmpty() || failed.isNotEmpty()) database.withTransaction {
            if (deleted.isNotEmpty()) {
                dao.clearArtistCovers(deleted)
                dao.deleteCollectionsEmptiedByAssets(deleted)
                dao.deleteAllAssetReferences(deleted)
                dao.deleteAssets(deleted)
            }
            if (failed.isNotEmpty()) dao.restoreAssets(failed)
        }
        Unit
    }

    override suspend fun pendingDeleteAssets(): List<MediaAssetEntity> = withContext(Dispatchers.IO) { dao.pendingDeleteAssets() }

    private suspend fun save(operation: suspend () -> SaveResult): SaveResult = withContext(Dispatchers.IO) {
        try {
            database.withTransaction { operation() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SaveResult.StorageFailure
        }
    }

    private fun ArtistSummaryRow.summary() = ArtistSummary(
        artist.id, artist.name, collectionCount, imageCount, coverStorageKey?.let(CoverRef::Asset) ?: CoverRef.None, videoCount,
    )

    private fun CollectionWithMediaCounts.summary() = CollectionSummary(
        collection.id, collection.artistId, collection.name, collection.isTemporary, imageCount, videoCount,
        coverStorageKey?.let(CoverRef::Asset) ?: CoverRef.None, collection.isDefault,
    )

    private fun MediaAssetRow.summary() = MediaAssetSummary(
        asset.id, sourceName.ifBlank { asset.originalName }, MediaType.valueOf(asset.mediaType), asset.byteSize, manualOrder, sourceName, asset.storageKey, asset.createdAt,
    )

    private companion object { const val ASSET_READY = "READY" }
}
