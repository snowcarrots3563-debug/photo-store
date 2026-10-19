package dev.local.artistgallery.data

import dev.local.artistgallery.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

interface GalleryRepository {
    fun observeArtists(): Flow<List<ArtistSummary>>
    fun observeArtist(id: String): Flow<ArtistDetail?>
    fun observeCollection(id: String): Flow<CollectionSummary?>
    suspend fun artistExists(id: String): Boolean = observeArtist(id).first() != null
    suspend fun collectionExists(id: String): Boolean = observeCollection(id).first() != null
    suspend fun createArtist(name: String): SaveResult
    suspend fun ensureDefaultCollection(artistId: String): SaveResult = SaveResult.StorageFailure
    suspend fun renameArtist(id: String, name: String): SaveResult
    suspend fun createCollection(artistId: String, name: String): SaveResult
    suspend fun renameCollection(id: String, name: String): SaveResult
}
