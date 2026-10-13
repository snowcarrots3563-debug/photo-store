package dev.local.artistgallery.data.backup

import dev.local.artistgallery.data.ArtistEntity
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.data.CollectionEntity
import dev.local.artistgallery.model.MediaSort
import dev.local.artistgallery.model.MediaAssetSummary
import dev.local.artistgallery.model.MediaType
import dev.local.artistgallery.model.sortArtistImages
import dev.local.artistgallery.model.sortCollectionMedia
import org.junit.Assert.*
import org.junit.Test

class RestoreImageSortRulesTest {
    private val source = ArtistEntity("source", "画师", 1, 1)
    private val existing = ArtistEntity("existing", "已有", 2, 2)
    private val restored = source.copy(id = "restored")
    private fun manifest(sorts: Map<String, MediaSort>) = BackupManifest(createdAt = 1, assets = emptyList(),
        artists = listOf(source), collections = emptyList(), references = emptyList(), settings = BrowseOptions(artistImageSorts = sorts))

    @Test fun restoreRemapsArtistIdAndPreservesCurrentSortAndGlobalSettings() {
        val current = BrowseOptions(cardEffects = true, playGifs = false, swipeDelete = false, artistImageSorts = mapOf(existing.id to MediaSort.SizeAscending))
        val result = RestoreRules.preferences(current, manifest(mapOf(source.id to MediaSort.NameDescending)),
            mapOf(source.id to restored.id), emptyMap(), listOf(existing, restored), emptyList())
        assertEquals(mapOf(existing.id to MediaSort.SizeAscending, restored.id to MediaSort.NameDescending), result.artistImageSorts)
        assertTrue(result.cardEffects); assertFalse(result.playGifs); assertFalse(result.swipeDelete)
        assertFalse(source.id in result.artistImageSorts)
    }

    @Test fun legacyBackupCreatesExplicitDefaultForRestoredArtist() {
        val result = RestoreRules.preferences(BrowseOptions(), manifest(emptyMap()), mapOf(source.id to restored.id),
            emptyMap(), listOf(restored), emptyList())
        assertEquals(mapOf(restored.id to MediaSort.Default), result.artistImageSorts)
        val media = listOf(MediaAssetSummary("old", "2.png", MediaType.IMAGE, 1, 0, "2.png", "old", 1),
            MediaAssetSummary("new", "10.png", MediaType.IMAGE, 1, 1, "10.png", "new", 2))
        assertEquals(listOf("new", "old"), sortArtistImages(media, result.artistImageSorts.getValue(restored.id)).map { it.id })
    }

    @Test fun removedRestoredArtistDoesNotReintroduceItsSort() {
        val current = BrowseOptions(artistImageSorts = mapOf(existing.id to MediaSort.AddedDescending))
        val result = RestoreRules.preferences(current, manifest(mapOf(source.id to MediaSort.NameAscending)),
            mapOf(source.id to restored.id), emptyMap(), listOf(existing), emptyList())
        assertEquals(current.artistImageSorts, result.artistImageSorts)
    }

    @Test fun repeatedPreferenceFinalizationIsIdempotent() {
        val m = manifest(mapOf(source.id to MediaSort.SizeDescending))
        val first = RestoreRules.preferences(BrowseOptions(), m, mapOf(source.id to restored.id), emptyMap(), listOf(restored), emptyList())
        val second = RestoreRules.preferences(first, m, mapOf(source.id to restored.id), emptyMap(), listOf(restored), emptyList())
        assertEquals(first, second)
    }

    private val sourceCollection = CollectionEntity("source-collection", source.id, "作品集", false, 1, 1)
    private val existingCollection = sourceCollection.copy(id = "existing-collection", artistId = existing.id)
    private val restoredCollection = sourceCollection.copy(id = "restored-collection", artistId = restored.id)
    private fun collectionManifest(sorts: Map<String, MediaSort>) = manifest(emptyMap()).copy(
        collections = listOf(sourceCollection), settings = BrowseOptions(collectionMediaSorts = sorts))

    @Test fun restoreRemapsCollectionIdAndPreservesCurrentSortArtistChoicesAndGlobalSettings() {
        val current = BrowseOptions(cardEffects = true, playGifs = false, swipeDelete = false,
            artistImageSorts = mapOf(existing.id to MediaSort.AddedDescending),
            collectionMediaSorts = mapOf(existingCollection.id to MediaSort.SizeAscending))
        val result = RestoreRules.preferences(current, collectionManifest(mapOf(sourceCollection.id to MediaSort.NameDescending)),
            mapOf(source.id to restored.id), mapOf(sourceCollection.id to restoredCollection.id),
            listOf(existing, restored), listOf(existingCollection, restoredCollection))
        assertEquals(mapOf(existingCollection.id to MediaSort.SizeAscending, restoredCollection.id to MediaSort.NameDescending), result.collectionMediaSorts)
        assertEquals(MediaSort.AddedDescending, result.artistImageSorts[existing.id])
        assertTrue(result.cardEffects); assertFalse(result.playGifs); assertFalse(result.swipeDelete)
        assertFalse(sourceCollection.id in result.collectionMediaSorts)
    }

    @Test fun legacyBackupCreatesExplicitNaturalNameDefaultForRestoredCollection() {
        val result = RestoreRules.preferences(BrowseOptions(), collectionManifest(emptyMap()), mapOf(source.id to restored.id),
            mapOf(sourceCollection.id to restoredCollection.id), listOf(restored), listOf(restoredCollection))
        assertEquals(mapOf(restoredCollection.id to MediaSort.Default), result.collectionMediaSorts)
        val media = listOf(MediaAssetSummary("ten", "10.png", MediaType.IMAGE, 1, 0, "10.png", "ten", 1),
            MediaAssetSummary("two", "2.png", MediaType.IMAGE, 1, 1, "2.png", "two", 2))
        assertEquals(listOf("two", "ten"), sortCollectionMedia(media, result.collectionMediaSorts.getValue(restoredCollection.id)).map { it.id })
        assertEquals(listOf(0, 1), media.map { it.manualOrder })
    }

    @Test fun removedRestoredCollectionDoesNotReintroduceItsSort() {
        val current = BrowseOptions(collectionMediaSorts = mapOf(existingCollection.id to MediaSort.AddedDescending))
        val result = RestoreRules.preferences(current, collectionManifest(mapOf(sourceCollection.id to MediaSort.NameAscending)),
            mapOf(source.id to restored.id), mapOf(sourceCollection.id to restoredCollection.id),
            listOf(existing, restored), listOf(existingCollection))
        assertEquals(current.collectionMediaSorts, result.collectionMediaSorts)
    }

    @Test fun restoredCollectionsKeepIndependentChoicesUnderTheirNewIds() {
        val secondSource = sourceCollection.copy(id = "second-source")
        val secondRestored = secondSource.copy(id = "second-restored", artistId = restored.id)
        val m = collectionManifest(mapOf(sourceCollection.id to MediaSort.SizeDescending, secondSource.id to MediaSort.AddedAscending))
            .copy(collections = listOf(sourceCollection, secondSource))
        val result = RestoreRules.preferences(BrowseOptions(), m, mapOf(source.id to restored.id),
            mapOf(sourceCollection.id to restoredCollection.id, secondSource.id to secondRestored.id),
            listOf(restored), listOf(restoredCollection, secondRestored))
        assertEquals(mapOf(restoredCollection.id to MediaSort.SizeDescending, secondRestored.id to MediaSort.AddedAscending), result.collectionMediaSorts)
    }

    @Test fun repeatedCollectionPreferenceFinalizationIsIdempotent() {
        val m = collectionManifest(mapOf(sourceCollection.id to MediaSort.NameDescending))
        val artistIds = mapOf(source.id to restored.id)
        val collectionIds = mapOf(sourceCollection.id to restoredCollection.id)
        val first = RestoreRules.preferences(BrowseOptions(), m, artistIds, collectionIds, listOf(restored), listOf(restoredCollection))
        val second = RestoreRules.preferences(first, m, artistIds, collectionIds, listOf(restored), listOf(restoredCollection))
        assertEquals(first, second)
    }
}
