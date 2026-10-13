package dev.local.artistgallery.data.exporting

import androidx.room.withTransaction
import dev.local.artistgallery.data.BrowsePreferences
import dev.local.artistgallery.data.GalleryDatabase
import dev.local.artistgallery.data.backup.BackupAsset
import dev.local.artistgallery.data.backup.BackupManifest
import dev.local.artistgallery.data.backup.BackupValidation

class GallerySnapshotReader(private val database: GalleryDatabase, private val preferences: BrowsePreferences) {
    suspend fun read(): GallerySnapshot = database.withTransaction {
        val dao = database.galleryDao()
        val assets = dao.allAssets().filter { it.state == "READY" }
        val assetIds = assets.map { it.id }.toSet()
        val artists = dao.allArtists().map { it.copy(coverAssetId = it.coverAssetId?.takeIf { id -> id in assetIds }) }
        val artistIds = artists.map { it.id }.toSet()
        val collections = dao.allCollections()
        val collectionIds = collections.map { it.id }.toSet()
        val preferences = preferences.options.value
        val manifest = BackupManifest(
            createdAt = System.currentTimeMillis(),
            assets = assets.map { BackupAsset(it.id, it.originalName, it.mediaType, it.byteSize, it.width, it.height, it.sha256, it.createdAt) },
            artists = artists, collections = collections,
            references = dao.allReferences().filter { it.assetId in assetIds },
            settings = preferences.copy(
                artistOrder = preferences.artistOrder.filter { it in artistIds },
                collectionOrders = preferences.collectionOrders.filterKeys { it in artistIds }.mapValues { (id, order) ->
                    val ids = collections.filter { it.artistId == id }.map { it.id }.toSet()
                    order.filter { it in collectionIds && it in ids }
                },
                artistLayouts = preferences.artistLayouts.filterKeys { it in artistIds },
                artistImageSorts = preferences.artistImageSorts.filterKeys { it in artistIds },
                collectionMediaSorts = preferences.collectionMediaSorts.filterKeys { it in collectionIds },
            ),
        )
        BackupValidation.validate(manifest)
        GallerySnapshot(manifest, assets.associate { it.id to it.storageKey })
    }
}
