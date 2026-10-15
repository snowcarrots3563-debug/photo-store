package dev.local.artistgallery.data.backup

import dev.local.artistgallery.data.ArtistEntity
import dev.local.artistgallery.data.ArtistImageLayout
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.data.CollectionEntity
import dev.local.artistgallery.data.CollectionMediaEntity
import org.junit.Assert.*
import org.junit.Test

class RestoreRulesTest {
    private fun id(value: Int) = "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
    private val artist = ArtistEntity(id(1), "画师", 13, 15, id(3))
    private val collection = CollectionEntity(id(2), artist.id, "作品", false, 21, 22)
    private val asset = BackupAsset(id(3), "原图.png", "IMAGE", 1, 2, 3, "a".repeat(64), 30)
    private val reference = CollectionMediaEntity(collection.id, asset.id, 4, "来源.png", "目录/来源.png")
    private val manifest = BackupManifest(createdAt = 50, artists = listOf(artist), collections = listOf(collection), assets = listOf(asset), references = listOf(reference), settings = BrowseOptions())
    private val staged = mapOf(asset.id to StagedRestoreAsset(id(13), id(14), id(15)))
    private val artistIds = mapOf(artist.id to id(11))
    private val collectionIds = mapOf(collection.id to id(12))

    @Test fun mergeRemapsIdsCoversAndSourceMetadataWithoutChangingOriginals() {
        val oldArtist = artist.copy(id = id(20), coverAssetId = null)
        val rows = RestoreRules.rows(manifest, staged, artistIds, collectionIds, listOf(oldArtist))
        assertEquals("画师（2）", rows.artists.single().name)
        assertEquals(id(11), rows.artists.single().id)
        assertEquals(id(13), rows.artists.single().coverAssetId)
        assertEquals(artist.createdAt, rows.artists.single().createdAt)
        assertEquals(artist.updatedAt, rows.artists.single().updatedAt)
        assertEquals(id(11), rows.collections.single().artistId)
        assertEquals(collection.createdAt, rows.collections.single().createdAt)
        assertEquals(id(15), rows.assets.single().storageKey)
        assertEquals(asset.sha256, rows.assets.single().sha256)
        assertEquals(asset.originalName, rows.assets.single().originalName)
        assertEquals(reference.copy(collectionId = id(12), assetId = id(13)), rows.references.single())
        assertEquals("画师", oldArtist.name)
        assertEquals(id(3), manifest.artists.single().coverAssetId)
    }

    @Test fun duplicateNamesAreNumberedWithoutOverwritingNaturalNumberedNames() {
        val anotherArtist = artist.copy(id = id(5))
        val duplicateCollection = collection.copy(id = id(6))
        val rows = RestoreRules.rows(
            manifest.copy(artists = listOf(artist, anotherArtist), collections = listOf(collection, duplicateCollection)), staged,
            artistIds + (anotherArtist.id to id(16)), collectionIds + (duplicateCollection.id to id(17)),
            listOf(artist.copy(id = id(20)), artist.copy(id = id(21), name = "画师（2）")),
        )
        assertEquals(listOf("画师（3）", "画师（4）"), rows.artists.map { it.name })
        assertEquals(listOf("作品", "作品（2）"), rows.collections.map { it.name })
    }

    @Test fun sharedReferencesUseOneRestoredFileAndEqualHashPagesRemainSeparateAssets() {
        val second = asset.copy(id = id(7), originalName = "第2页.png")
        val anotherCollection = collection.copy(id = id(8))
        val refs = listOf(reference, reference.copy(collectionId = anotherCollection.id), reference.copy(assetId = second.id, manualOrder = 5))
        val rows = RestoreRules.rows(
            manifest.copy(assets = listOf(asset, second), collections = listOf(collection, anotherCollection), references = refs),
            staged + (second.id to StagedRestoreAsset(id(18), id(19), id(20))), artistIds,
            collectionIds + (anotherCollection.id to id(21)), emptyList(),
        )
        assertEquals(2, rows.assets.size)
        assertEquals(3, rows.references.size)
        assertEquals(rows.references[0].assetId, rows.references[1].assetId)
        assertNotEquals(rows.references[0].assetId, rows.references[2].assetId)
        assertNotEquals(rows.assets[0].storageKey, rows.assets[1].storageKey)
        assertEquals(rows.assets[0].sha256, rows.assets[1].sha256)
    }

    @Test fun duplicateMaximumLengthNameRemainsValidForFutureBackups() {
        val longName = "画".repeat(4096)
        val source = manifest.copy(artists = listOf(artist.copy(name = longName)))
        val rows = RestoreRules.rows(source, staged, artistIds, collectionIds, listOf(artist.copy(id = id(20), name = longName)))
        assertEquals(4096, rows.artists.single().name.length)
        assertTrue(rows.artists.single().name.endsWith("（2）"))
        BackupValidation.validate(source.copy(artists = rows.artists, collections = rows.collections,
            assets = source.assets.map { it.copy(id = staged.getValue(it.id).assetId) }, references = rows.references))
    }

    @Test fun preferencesPreserveCurrentGlobalsAndOrderThenAppendBackupOrder() {
        val secondArtist = artist.copy(id = id(5), name = "第二位", coverAssetId = null)
        val settings = BrowseOptions(cardEffects = true, playGifs = false, swipeDelete = false,
            artistOrder = listOf(secondArtist.id, artist.id), collectionOrders = mapOf(artist.id to listOf(collection.id)),
            artistLayouts = mapOf(artist.id to ArtistImageLayout.Medium))
        val backup = manifest.copy(artists = listOf(artist, secondArtist), settings = settings)
        val mapping = artistIds + (secondArtist.id to id(16))
        val oldA = artist.copy(id = id(30), coverAssetId = null, createdAt = 1)
        val oldB = oldA.copy(id = id(31), createdAt = 2)
        val current = BrowseOptions(cardEffects = false, playGifs = true, swipeDelete = true,
            artistOrder = listOf(oldB.id, oldA.id), storageNoticeDismissed = true,
            collectionOrders = mapOf(oldA.id to listOf(id(35))), artistLayouts = mapOf(oldA.id to ArtistImageLayout.Large))
        val rows = RestoreRules.rows(backup, staged, mapping, collectionIds, listOf(oldA, oldB))
        val restored = RestoreRules.preferences(current, backup, mapping, collectionIds, listOf(oldA, oldB) + rows.artists, rows.collections)
        assertEquals(listOf(oldB.id, oldA.id, id(16), id(11)), restored.artistOrder)
        assertFalse(restored.cardEffects)
        assertTrue(restored.playGifs)
        assertTrue(restored.swipeDelete)
        assertTrue(restored.storageNoticeDismissed)
        assertEquals(current.imageLayout, restored.imageLayout)
        assertEquals(listOf(id(35)), restored.collectionOrders[oldA.id])
        assertEquals(listOf(id(12)), restored.collectionOrders[id(11)])
        assertEquals(ArtistImageLayout.Medium, restored.artistLayouts[id(11)])
        assertEquals(ArtistImageLayout.List, restored.artistLayouts[id(16)])
        assertEquals(ArtistImageLayout.Large, restored.artistLayouts[oldA.id])
        assertEquals(restored, RestoreRules.preferences(restored, backup, mapping, collectionIds, listOf(oldA, oldB) + rows.artists, rows.collections))
    }

    @Test fun equalCollectionCreationTimesUseSourceIdsBeforeReverseIdRemapping() {
        val second = collection.copy(id = id(8), name = "第二本")
        val source = manifest.copy(collections = listOf(second, collection))
        // Fresh UUID order is deliberately the reverse of the source UUID order.
        val mapping = mapOf(collection.id to id(99), second.id to id(10))
        val rows = RestoreRules.rows(source, staged, artistIds, mapping, emptyList())
        val options = RestoreRules.preferences(BrowseOptions(), source, artistIds, mapping, rows.artists, rows.collections)
        assertEquals(listOf(id(99), id(10)), options.collectionOrders[id(11)])
        assertNotEquals(rows.collections.sortedWith(compareBy({ it.createdAt }, { it.id })).map { it.id }, options.collectionOrders[id(11)])
    }

    @Test fun partialExplicitCollectionOrderIncludesAllRemainingCollectionsInSourceFallbackOrder() {
        val earlier = collection.copy(id = id(8), name = "较早", createdAt = 20)
        val selected = collection.copy(id = id(9), name = "置顶")
        val source = manifest.copy(collections = listOf(collection, selected, earlier),
            settings = BrowseOptions(collectionOrders = mapOf(artist.id to listOf(selected.id))))
        val mapping = collectionIds + mapOf(earlier.id to id(38), selected.id to id(39))
        val rows = RestoreRules.rows(source, staged, artistIds, mapping, emptyList())
        val options = RestoreRules.preferences(BrowseOptions(), source, artistIds, mapping, rows.artists, rows.collections)
        assertEquals(listOf(id(39), id(38), id(12)), options.collectionOrders[id(11)])
        assertEquals(rows.collections.map { it.id }.toSet(), options.collectionOrders[id(11)]?.toSet())
    }

    @Test fun missingArtistLayoutInheritsSourceGlobalLayoutWithoutReplacingTargetGlobalLayout() {
        val source = manifest.copy(settings = BrowseOptions(imageLayout = ArtistImageLayout.Large))
        val rows = RestoreRules.rows(source, staged, artistIds, collectionIds, emptyList())
        val current = BrowseOptions(imageLayout = ArtistImageLayout.Medium)
        val options = RestoreRules.preferences(current, source, artistIds, collectionIds, rows.artists, rows.collections)
        assertEquals(ArtistImageLayout.Large, options.artistLayouts[id(11)])
        assertEquals(ArtistImageLayout.Medium, options.imageLayout)
        assertEquals(listOf(id(12)), options.collectionOrders[id(11)])
    }

    @Test fun assetIdRemappingPreservesLexicalOrderForShuffledSourceAndNewIds() {
        val sourceIds = listOf(id(7), id(3), id(9), id(1))
        val candidates = listOf(id(99), id(15), id(70), id(11))
        val mapping = RestoreRules.assetIds(sourceIds, candidates)
        assertEquals(candidates.sorted(), sourceIds.sorted().map { mapping.getValue(it) })
        assertEquals(sourceIds.toSet(), mapping.keys)
        assertEquals(candidates.toSet(), mapping.values.toSet())
        assertTrue(mapping.all { (oldId, newId) -> oldId != newId })
    }
    @Test fun renamedEmptyDefaultKeepsIdentityAcrossIdRemappingWithoutCreatingAnother() {
        val source = manifest.copy(assets = emptyList(), references = emptyList(),
            artists = listOf(artist.copy(coverAssetId = null)),
            collections = listOf(collection.copy(name = "常用收藏", isDefault = true)))
        val rows = RestoreRules.rows(source, emptyMap(), artistIds, collectionIds, emptyList())
        assertEquals(1, rows.artists.size)
        assertEquals(1, rows.collections.size)
        assertTrue(rows.collections.single().isDefault)
        assertEquals("常用收藏", rows.collections.single().name)
        assertEquals(id(12), rows.collections.single().id)
        assertEquals(id(11), rows.collections.single().artistId)
        assertTrue(rows.assets.isEmpty())
        assertTrue(rows.references.isEmpty())
        val oldRows = RestoreRules.rows(source.copy(collections = listOf(collection)), emptyMap(), artistIds, collectionIds, emptyList())
        assertFalse(oldRows.collections.single().isDefault)
    }
}
