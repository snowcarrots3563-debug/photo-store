package dev.local.artistgallery.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "CollectionMedia",
    primaryKeys = ["collectionId", "assetId"],
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("collectionId"), Index("assetId")],
)
data class CollectionMediaEntity(
    val collectionId: String,
    val assetId: String,
    val manualOrder: Int,
    val sourceName: String,
    val sourceRelativePath: String?,
)
