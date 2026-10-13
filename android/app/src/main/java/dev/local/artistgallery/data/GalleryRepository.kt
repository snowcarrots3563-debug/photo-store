package dev.local.artistgallery.data

import dev.local.artistgallery.model.*
import kotlinx.coroutines.flow.Flow

interface GalleryRepository {
    fun observeArtists(): Flow<List<ArtistSummary>>
    fun observeArtist(id: String): Flow<ArtistDetail?>
    fun observeCollection(id: String): Flow<CollectionSummary?>
    suspend fun createArtist(name: String): SaveResult
    suspend fun renameArtist(id: String, name: String): SaveResult
    suspend fun createCollection(artistId: String, name: String): SaveResult
    suspend fun renameCollection(id: String, name: String): SaveResult
}
