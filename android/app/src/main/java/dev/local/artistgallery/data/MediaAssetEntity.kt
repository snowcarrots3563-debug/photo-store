package dev.local.artistgallery.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "MediaAsset",
    indices = [Index(value = ["sha256", "byteSize"]), Index("state")],
)
data class MediaAssetEntity(
    @PrimaryKey val id: String,
    val originalName: String,
    val mediaType: String,
    val byteSize: Long,
    val width: Int?,
    val height: Int?,
    val sha256: String,
    val storageKey: String,
    val state: String,
    val createdAt: Long,
)

@Entity(tableName = "ImportBatch")
data class ImportBatchEntity(
    @PrimaryKey val id: String,
    val targetCollectionId: String?,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "ImportItem",
    indices = [Index("batchId"), Index("status")],
)
data class ImportItemEntity(
    @PrimaryKey val id: String,
    val batchId: String,
    val targetCollectionId: String?,
    val sourceUri: String,
    val hasPersistedPermission: Boolean,
    val sourceName: String,
    val mediaType: String,
    val stagingKey: String,
    val storageKey: String,
    val sha256: String?,
    val byteSize: Long?,
    val status: String,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val archiveGroupId: String? = null,
    val sourceRelativePath: String? = null,
)
