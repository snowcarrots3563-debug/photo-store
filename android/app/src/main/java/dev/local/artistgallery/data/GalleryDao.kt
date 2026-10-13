package dev.local.artistgallery.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class ArtistWithCollections(
    @Embedded val artist: ArtistEntity,
    @Relation(parentColumn = "id", entityColumn = "artistId")
    val collections: List<CollectionEntity>,
    val imageCount: Int,
    val coverStorageKey: String?,
)

data class CollectionWithMediaCounts(
    @Embedded val collection: CollectionEntity,
    val imageCount: Int,
    val videoCount: Int,
    val coverStorageKey: String?,
)

data class MediaAssetRow(
    @Embedded val asset: MediaAssetEntity,
    val manualOrder: Int,
    val sourceName: String,
)

data class ImportBatchWithItems(
    @Embedded val batch: ImportBatchEntity,
    @Relation(parentColumn = "id", entityColumn = "batchId")
    val items: List<ImportItemEntity>,
)

@Dao
interface GalleryDao {
    @Query("SELECT * FROM Artist ORDER BY createdAt ASC, id ASC")
    suspend fun allArtists(): List<ArtistEntity>

    @Query("SELECT * FROM WorkCollection ORDER BY createdAt ASC, id ASC")
    suspend fun allCollections(): List<CollectionEntity>

    @Query("SELECT * FROM MediaAsset ORDER BY createdAt ASC, id ASC")
    suspend fun allAssets(): List<MediaAssetEntity>

    @Query("SELECT * FROM CollectionMedia ORDER BY collectionId ASC, manualOrder ASC, assetId ASC")
    suspend fun allReferences(): List<CollectionMediaEntity>

    @Insert
    suspend fun insertArchiveTask(task: dev.local.artistgallery.data.archive.ArchiveTaskEntity)

    @Insert
    suspend fun insertArchiveGroups(groups: List<dev.local.artistgallery.data.archive.ArchiveGroupEntity>)

    @Query("SELECT * FROM ArchiveTask ORDER BY createdAt DESC")
    suspend fun archiveTasks(): List<dev.local.artistgallery.data.archive.ArchiveTaskEntity>

    @Query("SELECT * FROM ArchiveGroup WHERE id = :id")
    suspend fun archiveGroup(id: String): dev.local.artistgallery.data.archive.ArchiveGroupEntity?

    @Query("SELECT * FROM ArchiveGroup WHERE batchId = :batchId")
    suspend fun archiveGroups(batchId: String): List<dev.local.artistgallery.data.archive.ArchiveGroupEntity>

    @Query("UPDATE ArchiveTask SET selectedNames = :names WHERE id = :id")
    suspend fun updateArchiveVolumes(id: String, names: String)
    @Transaction
    @Query("""
        SELECT Artist.*,
            (SELECT COUNT(DISTINCT cm.assetId) FROM WorkCollection wc
                JOIN CollectionMedia cm ON cm.collectionId = wc.id
                JOIN MediaAsset ma ON ma.id = cm.assetId
                WHERE wc.artistId = Artist.id AND ma.mediaType IN ('IMAGE', 'GIF') AND ma.state = 'READY') AS imageCount,
            COALESCE((SELECT ma.storageKey FROM WorkCollection wc
                JOIN CollectionMedia cm ON cm.collectionId = wc.id
                JOIN MediaAsset ma ON ma.id = cm.assetId
                WHERE wc.artistId = Artist.id AND ma.id = Artist.coverAssetId
                    AND ma.mediaType IN ('IMAGE', 'GIF') AND ma.state = 'READY' LIMIT 1),
            (SELECT ma.storageKey FROM WorkCollection wc
                JOIN CollectionMedia cm ON cm.collectionId = wc.id
                JOIN MediaAsset ma ON ma.id = cm.assetId
                WHERE wc.artistId = Artist.id AND ma.mediaType IN ('IMAGE', 'GIF') AND ma.state = 'READY'
                ORDER BY wc.createdAt ASC, cm.manualOrder ASC, cm.assetId ASC LIMIT 1)) AS coverStorageKey
        FROM Artist ORDER BY createdAt ASC, id ASC
    """)
    fun observeArtists(): Flow<List<ArtistWithCollections>>

    @Transaction
    @Query("""
        SELECT Artist.*,
            (SELECT COUNT(DISTINCT cm.assetId) FROM WorkCollection wc
                JOIN CollectionMedia cm ON cm.collectionId = wc.id
                JOIN MediaAsset ma ON ma.id = cm.assetId
                WHERE wc.artistId = Artist.id AND ma.mediaType IN ('IMAGE', 'GIF') AND ma.state = 'READY') AS imageCount,
            COALESCE((SELECT ma.storageKey FROM WorkCollection wc
                JOIN CollectionMedia cm ON cm.collectionId = wc.id
                JOIN MediaAsset ma ON ma.id = cm.assetId
                WHERE wc.artistId = Artist.id AND ma.id = Artist.coverAssetId
                    AND ma.mediaType IN ('IMAGE', 'GIF') AND ma.state = 'READY' LIMIT 1),
            (SELECT ma.storageKey FROM WorkCollection wc
                JOIN CollectionMedia cm ON cm.collectionId = wc.id
                JOIN MediaAsset ma ON ma.id = cm.assetId
                WHERE wc.artistId = Artist.id AND ma.mediaType IN ('IMAGE', 'GIF') AND ma.state = 'READY'
                ORDER BY wc.createdAt ASC, cm.manualOrder ASC, cm.assetId ASC LIMIT 1)) AS coverStorageKey
        FROM Artist WHERE id = :id
    """)
    fun observeArtist(id: String): Flow<ArtistWithCollections?>

    @Query("""
        SELECT WorkCollection.*,
            (SELECT COUNT(*) FROM CollectionMedia cm JOIN MediaAsset ma ON ma.id = cm.assetId
                WHERE cm.collectionId = WorkCollection.id AND ma.mediaType IN ('IMAGE', 'GIF') AND ma.state = 'READY') AS imageCount,
            (SELECT COUNT(*) FROM CollectionMedia cm JOIN MediaAsset ma ON ma.id = cm.assetId
                WHERE cm.collectionId = WorkCollection.id AND ma.mediaType = 'VIDEO' AND ma.state = 'READY') AS videoCount,
            (SELECT ma.storageKey FROM CollectionMedia cm JOIN MediaAsset ma ON ma.id = cm.assetId
                WHERE cm.collectionId = WorkCollection.id AND ma.mediaType IN ('IMAGE', 'GIF') AND ma.state = 'READY'
                ORDER BY cm.manualOrder ASC, cm.assetId ASC LIMIT 1) AS coverStorageKey
        FROM WorkCollection WHERE id = :id
    """)
    fun observeCollection(id: String): Flow<CollectionWithMediaCounts?>

    @Query("""
        SELECT WorkCollection.*,
            (SELECT COUNT(*) FROM CollectionMedia cm JOIN MediaAsset ma ON ma.id = cm.assetId
                WHERE cm.collectionId = WorkCollection.id AND ma.mediaType IN ('IMAGE', 'GIF') AND ma.state = 'READY') AS imageCount,
            (SELECT COUNT(*) FROM CollectionMedia cm JOIN MediaAsset ma ON ma.id = cm.assetId
                WHERE cm.collectionId = WorkCollection.id AND ma.mediaType = 'VIDEO' AND ma.state = 'READY') AS videoCount,
            (SELECT ma.storageKey FROM CollectionMedia cm JOIN MediaAsset ma ON ma.id = cm.assetId
                WHERE cm.collectionId = WorkCollection.id AND ma.mediaType IN ('IMAGE', 'GIF') AND ma.state = 'READY'
                ORDER BY cm.manualOrder ASC, cm.assetId ASC LIMIT 1) AS coverStorageKey
        FROM WorkCollection WHERE artistId = :artistId ORDER BY createdAt ASC, id ASC
    """)
    fun observeCollectionsForArtist(artistId: String): Flow<List<CollectionWithMediaCounts>>

    @Query("SELECT * FROM Artist WHERE id = :id")
    suspend fun getArtist(id: String): ArtistEntity?

    @Query("""UPDATE Artist SET coverAssetId = :assetId, updatedAt = :updatedAt WHERE id = :artistId
        AND EXISTS(SELECT 1 FROM WorkCollection wc JOIN CollectionMedia cm ON cm.collectionId = wc.id
            JOIN MediaAsset ma ON ma.id = cm.assetId WHERE wc.artistId = :artistId
            AND ma.id = :assetId AND ma.state = 'READY' AND ma.mediaType IN ('IMAGE', 'GIF'))""")
    suspend fun setArtistCover(artistId: String, assetId: String, updatedAt: Long): Int

    @Query("UPDATE Artist SET coverAssetId = NULL WHERE coverAssetId = :assetId")
    suspend fun clearArtistCover(assetId: String)

    @Query("UPDATE Artist SET coverAssetId = NULL WHERE coverAssetId IN (:assetIds)")
    suspend fun clearArtistCovers(assetIds: List<String>)

    @Query("SELECT * FROM WorkCollection WHERE id = :id")
    suspend fun getCollection(id: String): CollectionEntity?

    @Query("SELECT * FROM WorkCollection WHERE artistId = :artistId ORDER BY createdAt ASC, id ASC")
    suspend fun getCollections(artistId: String): List<CollectionEntity>

    @Insert
    suspend fun insertArtist(artist: ArtistEntity)

    @Update
    suspend fun updateArtist(artist: ArtistEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM WorkCollection wc JOIN CollectionMedia cm ON cm.collectionId = wc.id WHERE wc.artistId = :artistId)")
    suspend fun artistHasMedia(artistId: String): Boolean

    @Query("DELETE FROM WorkCollection WHERE artistId = :artistId")
    suspend fun deleteArtistCollections(artistId: String)

    @Query("DELETE FROM Artist WHERE id = :artistId")
    suspend fun deleteArtist(artistId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM CollectionMedia WHERE collectionId = :collectionId)")
    suspend fun collectionHasMedia(collectionId: String): Boolean

    @Query("DELETE FROM WorkCollection WHERE id = :collectionId")
    suspend fun deleteCollection(collectionId: String): Int

    @Insert
    suspend fun insertCollection(collection: CollectionEntity)

    @Update
    suspend fun updateCollection(collection: CollectionEntity)

    @Query("SELECT * FROM MediaAsset WHERE id = :id")
    suspend fun getAsset(id: String): MediaAssetEntity?

    @Query("""
        SELECT DISTINCT ma.* FROM WorkCollection wc JOIN CollectionMedia cm ON cm.collectionId = wc.id
        JOIN MediaAsset ma ON ma.id = cm.assetId WHERE wc.artistId = :artistId
        ORDER BY ma.createdAt ASC, ma.id ASC
    """)
    suspend fun getArtistAssets(artistId: String): List<MediaAssetEntity>

    @Query("""
        SELECT ma.* FROM CollectionMedia cm JOIN MediaAsset ma ON ma.id = cm.assetId
        WHERE cm.collectionId = :collectionId
        ORDER BY ma.createdAt ASC, ma.id ASC
    """)
    suspend fun getCollectionAssets(collectionId: String): List<MediaAssetEntity>

    @Query("SELECT * FROM MediaAsset WHERE storageKey = :storageKey LIMIT 1")
    suspend fun getAssetByStorageKey(storageKey: String): MediaAssetEntity?

    @Query("SELECT * FROM MediaAsset WHERE sha256 = :sha256 AND byteSize = :byteSize AND state = 'READY'")
    suspend fun findAssetsByContent(sha256: String, byteSize: Long): List<MediaAssetEntity>

    @Query("""
        SELECT ma.*, cm.manualOrder AS manualOrder, cm.sourceName AS sourceName
        FROM CollectionMedia cm JOIN MediaAsset ma ON ma.id = cm.assetId
        WHERE cm.collectionId = :collectionId AND ma.state = 'READY'
        ORDER BY cm.manualOrder ASC, cm.assetId ASC
    """)
    fun observeCollectionMedia(collectionId: String): Flow<List<MediaAssetRow>>

    @Query("""
        SELECT ma.*, MIN(cm.manualOrder) AS manualOrder, MIN(cm.sourceName) AS sourceName
        FROM WorkCollection wc JOIN CollectionMedia cm ON cm.collectionId = wc.id
        JOIN MediaAsset ma ON ma.id = cm.assetId
        WHERE wc.artistId = :artistId AND ma.mediaType IN ('IMAGE', 'GIF') AND ma.state = 'READY'
        GROUP BY ma.id ORDER BY ma.createdAt ASC, ma.id ASC
    """)
    fun observeArtistImages(artistId: String): Flow<List<MediaAssetRow>>

    @Query("""
        SELECT ma.*, 0 AS manualOrder, ma.originalName AS sourceName
        FROM MediaAsset ma LEFT JOIN CollectionMedia cm ON cm.assetId = ma.id
        WHERE ma.state = 'READY' AND cm.assetId IS NULL
        ORDER BY ma.createdAt ASC, ma.id ASC
    """)
    fun observeUnassignedMedia(): Flow<List<MediaAssetRow>>

    @Insert
    suspend fun insertAsset(asset: MediaAssetEntity)

    @Update
    suspend fun updateAsset(asset: MediaAssetEntity)

    @Insert
    suspend fun insertCollectionMedia(reference: CollectionMediaEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM WorkCollection WHERE id = :id)")
    suspend fun collectionExists(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM CollectionMedia WHERE collectionId = :collectionId AND assetId = :assetId)")
    suspend fun referenceExists(collectionId: String, assetId: String): Boolean

    @Query("SELECT COALESCE(MAX(manualOrder), -1) + 1 FROM CollectionMedia WHERE collectionId = :collectionId")
    suspend fun nextManualOrder(collectionId: String): Int

    @Query("SELECT COUNT(*) FROM CollectionMedia WHERE assetId = :assetId")
    suspend fun referenceCount(assetId: String): Int

    @Query("DELETE FROM CollectionMedia WHERE collectionId = :collectionId AND assetId = :assetId")
    suspend fun deleteReference(collectionId: String, assetId: String): Int

    @Query("DELETE FROM CollectionMedia WHERE assetId = :assetId")
    suspend fun deleteAllReferences(assetId: String)

    @Query("DELETE FROM CollectionMedia WHERE assetId IN (:assetIds)")
    suspend fun deleteAllAssetReferences(assetIds: List<String>)

    @Query("DELETE FROM MediaAsset WHERE id = :assetId")
    suspend fun deleteAsset(assetId: String): Int

    @Query("DELETE FROM MediaAsset WHERE id IN (:assetIds)")
    suspend fun deleteAssets(assetIds: List<String>): Int

    @Query("UPDATE MediaAsset SET state = 'PENDING_DELETE' WHERE id = :assetId AND state = 'READY'")
    suspend fun markAssetPendingDelete(assetId: String): Int

    @Query("UPDATE MediaAsset SET state = 'PENDING_DELETE' WHERE id IN (:assetIds) AND state = 'READY'")
    suspend fun markAssetsPendingDelete(assetIds: List<String>): Int

    @Query("SELECT * FROM MediaAsset WHERE id IN (:assetIds) AND state = 'PENDING_DELETE'")
    suspend fun pendingAssetsForDelete(assetIds: List<String>): List<MediaAssetEntity>

    @Query("UPDATE MediaAsset SET state = 'READY' WHERE id = :assetId AND state = 'PENDING_DELETE'")
    suspend fun restoreAsset(assetId: String): Int

    @Query("UPDATE MediaAsset SET state = 'READY' WHERE id IN (:assetIds) AND state = 'PENDING_DELETE'")
    suspend fun restoreAssets(assetIds: List<String>): Int

    @Insert
    suspend fun insertImportBatch(batch: ImportBatchEntity)

    @Insert
    suspend fun insertImportItems(items: List<ImportItemEntity>)

    @Update
    suspend fun updateImportItem(item: ImportItemEntity)

    @Query("UPDATE ImportBatch SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateImportBatch(id: String, status: String, updatedAt: Long)

    @Query("SELECT * FROM ImportItem WHERE status IN ('QUEUED', 'COPYING', 'STAGED', 'FINALIZED') ORDER BY createdAt ASC")
    suspend fun importItemsToRecover(): List<ImportItemEntity>

    @Query("SELECT * FROM ImportItem WHERE batchId = :batchId ORDER BY createdAt ASC")
    suspend fun importItems(batchId: String): List<ImportItemEntity>

    @Query("SELECT * FROM ImportItem WHERE batchId = :batchId AND id = :id")
    suspend fun getImportItem(batchId: String, id: String): ImportItemEntity?

    @Transaction
    @Query("SELECT * FROM ImportBatch WHERE id = :batchId AND NOT EXISTS (SELECT 1 FROM ArchiveTask WHERE ArchiveTask.id = ImportBatch.id)")
    suspend fun getImportBatchResult(batchId: String): ImportBatchWithItems?

    @Transaction
    @Query("SELECT * FROM ImportBatch WHERE NOT EXISTS (SELECT 1 FROM ArchiveTask WHERE ArchiveTask.id = ImportBatch.id) ORDER BY createdAt DESC LIMIT 10")
    suspend fun latestImportBatches(): List<ImportBatchWithItems>

    @Transaction
    @Query("SELECT * FROM ImportBatch WHERE NOT EXISTS (SELECT 1 FROM ArchiveTask WHERE ArchiveTask.id = ImportBatch.id) ORDER BY createdAt DESC LIMIT 10")
    fun observeLatestImportBatches(): Flow<List<ImportBatchWithItems>>

    @Query("SELECT * FROM MediaAsset WHERE state = 'PENDING_DELETE'")
    suspend fun pendingDeleteAssets(): List<MediaAssetEntity>
}
