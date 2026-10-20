package dev.local.artistgallery.model

enum class SampleCoverKey { Scenery1, Scenery2, Scenery3 }
sealed interface CoverRef {
    data object None : CoverRef
    data class Sample(val key: SampleCoverKey) : CoverRef
    data class Asset(val storageKey: String) : CoverRef
}
data class ArtistSummary(val id: String, val name: String, val collectionCount: Int, val imageCount: Int, val cover: CoverRef = CoverRef.None, val videoCount: Int = 0)
data class ArtistHeader(val id: String, val name: String)
data class ArtistDetail(val artist: ArtistSummary, val collections: List<CollectionSummary>)
data class CollectionSummary(val id: String, val artistId: String, val name: String, val isTemporary: Boolean, val imageCount: Int, val videoCount: Int, val cover: CoverRef = CoverRef.None, val isDefault: Boolean = false)
sealed interface SaveResult {
    data class Saved(val id: String) : SaveResult
    data object InvalidName : SaveResult
    data object DuplicateCollectionName : SaveResult
    data object NotFound : SaveResult
    data object StorageFailure : SaveResult
}
