package dev.local.artistgallery.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "WorkCollection",
    foreignKeys = [ForeignKey(
        entity = ArtistEntity::class,
        parentColumns = ["id"],
        childColumns = ["artistId"],
        onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index("artistId")],
)
data class CollectionEntity(
    @PrimaryKey val id: String,
    val artistId: String,
    val name: String,
    val isTemporary: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)
