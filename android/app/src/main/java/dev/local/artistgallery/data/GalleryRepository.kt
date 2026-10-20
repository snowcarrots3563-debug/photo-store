package dev.local.artistgallery.data

import dev.local.artistgallery.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

interface GalleryRepository {
    fun observeArtists(): Flow<List<ArtistSummary>>
    fun observeArtist(id: String): Flow<ArtistDetail?>
    fun observeArtistHeader(id: String): Flow<ArtistHeader?> = observeArtist(id)
        .map { it?.artist?.let { artist -> ArtistHeader(artist.id, artist.name) } }.distinctUntilChanged()
    fun observeCollection(id: String): Flow<CollectionSummary?>
    suspend fun artistExists(id: String): Boolean = observeArtist(id).first() != null
    suspend fun collectionExists(id: String): Boolean = observeCollection(id).first() != null
    suspend fun createArtist(name: String): SaveResult
    suspend fun ensureDefaultCollection(artistId: String): SaveResult = SaveResult.StorageFailure
    suspend fun renameArtist(id: String, name: String): SaveResult
    suspend fun createCollection(artistId: String, name: String): SaveResult
    suspend fun renameCollection(id: String, name: String): SaveResult
}
