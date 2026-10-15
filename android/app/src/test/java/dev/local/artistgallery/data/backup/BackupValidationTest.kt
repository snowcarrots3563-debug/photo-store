package dev.local.artistgallery.data.backup

import dev.local.artistgallery.data.ArtistEntity
import dev.local.artistgallery.data.ArtistImageLayout
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.data.CollectionEntity
import dev.local.artistgallery.data.CollectionMediaEntity
import dev.local.artistgallery.model.MediaSort
import java.io.IOException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupValidationTest {
    private val artist = UUID.randomUUID().toString()
    private val collection = UUID.randomUUID().toString()
    private val image = UUID.randomUUID().toString()
    private fun manifest() = BackupManifest(
        createdAt = 1,
        assets = listOf(BackupAsset(image, "画作.png", "IMAGE", 12, 4, 4, "a".repeat(64), 1)),
        artists = listOf(ArtistEntity(artist, "画师", 1, 1, image)),
        collections = listOf(CollectionEntity(collection, artist, "图集", false, 1, 1)),
        references = listOf(CollectionMediaEntity(collection, image, 0, "画作.png", "目录/画作.png")),
        settings = BrowseOptions(artistOrder = listOf(artist), collectionOrders = mapOf(artist to listOf(collection)), artistLayouts = mapOf(artist to ArtistImageLayout.Large)),
    )
    private fun invalid(value: BackupManifest) { assertThrows(IOException::class.java) { BackupValidation.validate(value) } }
    @Test fun validSharedRelationshipsAndSeparateIdenticalPages() {
        val m = manifest(); val second = UUID.randomUUID().toString()
        BackupValidation.validate(m.copy(assets = m.assets + m.assets[0].copy(id = second)))
        assertEquals(12L, m.totalBytes)
    }
    @Test fun incompatibleVersionIsRejected() = invalid(manifest().copy(version = 2))
    @Test fun malformedIdsAreRejected() = invalid(manifest().copy(artists = listOf(ArtistEntity("../artist", "画师", 1, 1))))
    @Test fun duplicateAssetsAreRejected() { val m = manifest(); invalid(m.copy(assets = m.assets + m.assets)) }
    @Test fun danglingReferenceIsRejected() { val m = manifest(); invalid(m.copy(references = listOf(m.references[0].copy(assetId = UUID.randomUUID().toString())))) }
    @Test fun repeatedReferencesAreRejected() { val m = manifest(); invalid(m.copy(references = m.references + m.references)) }
    @Test fun coverMustBelongToArtistAndBeImage() { val m = manifest(); invalid(m.copy(references = emptyList())); invalid(m.copy(assets = listOf(m.assets[0].copy(mediaType = "VIDEO")))) }
    @Test fun nonMediaAndInvalidHashesAreRejected() { val m = manifest(); invalid(m.copy(assets = listOf(m.assets[0].copy(mediaType = "PDF")))); invalid(m.copy(assets = listOf(m.assets[0].copy(sha256 = "invalid")))) }
    @Test fun overflowIsRejectedBeforeAllocatingDisk() { val m = manifest(); invalid(m.copy(assets = m.assets.map { it.copy(byteSize = Long.MAX_VALUE) } + m.assets.map { it.copy(id = UUID.randomUUID().toString()) })) }
    @Test fun foreignSortAndLayoutKeysAreRejected() { val m = manifest(); invalid(m.copy(settings = m.settings.copy(artistOrder = listOf(UUID.randomUUID().toString())))); invalid(m.copy(settings = m.settings.copy(collectionOrders = mapOf(artist to listOf(UUID.randomUUID().toString()))))) }
    @Test fun emptyLibraryBackupIsValid() { BackupValidation.validate(BackupManifest(createdAt = 1, assets = emptyList(), artists = emptyList(), collections = emptyList(), references = emptyList(), settings = BrowseOptions())) }
    @Test fun imageSortMustBelongToExistingArtist() {
        val m = manifest()
        invalid(m.copy(settings = m.settings.copy(artistImageSorts = mapOf(UUID.randomUUID().toString() to MediaSort.NameAscending))))
    }
    @Test fun everyImageSortIsValidForItsArtistAndMissingSortKeepsLegacyBackupValid() {
        val m = manifest()
        BackupValidation.validate(m)
        MediaSort.entries.forEach { BackupValidation.validate(m.copy(settings = m.settings.copy(artistImageSorts = mapOf(artist to it)))) }
    }

    @Test fun collectionMediaSortMustBelongToExistingCollection() {
        val m = manifest()
        invalid(m.copy(settings = m.settings.copy(collectionMediaSorts = mapOf(UUID.randomUUID().toString() to MediaSort.NameAscending))))
    }

    @Test fun everyCollectionMediaSortAndLegacyMissingSortAreValid() {
        val m = manifest()
        BackupValidation.validate(m)
        MediaSort.entries.forEach { BackupValidation.validate(m.copy(settings = m.settings.copy(collectionMediaSorts = mapOf(collection to it)))) }
    }
    @Test fun multipleDefaultCollectionsForOneArtistAreRejected() {
        val m = manifest()
        val first = m.collections.single().copy(isDefault = true)
        val second = first.copy(id = UUID.randomUUID().toString(), name = "另一个")
        invalid(m.copy(collections = listOf(first, second)))
    }

    @Test fun renamedEmptyDefaultsForDifferentArtistsAreValid() {
        val m = manifest()
        val other = UUID.randomUUID().toString()
        BackupValidation.validate(m.copy(assets = emptyList(), references = emptyList(),
            artists = listOf(m.artists.single().copy(coverAssetId = null), ArtistEntity(other, "另一位", 1, 1)),
            collections = listOf(m.collections.single().copy(name = "常用", isDefault = true),
                CollectionEntity(UUID.randomUUID().toString(), other, "默认图集", false, 1, 1, isDefault = true))))
    }
}
