package dev.local.artistgallery.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "Artist")
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val coverAssetId: String? = null,
)
