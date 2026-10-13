package dev.local.artistgallery.data.archive

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "ArchiveTask")
data class ArchiveTaskEntity(
    @PrimaryKey val id: String,
    val artistId: String,
    val format: String,
    val mainName: String,
    val selectedNames: String,
    val createdAt: Long,
)

@Entity(tableName = "ArchiveGroup", indices = [Index("batchId")])
data class ArchiveGroupEntity(
    @PrimaryKey val id: String,
    val batchId: String,
    val artistId: String,
    val name: String,
    val relativeDirectory: String,
)
