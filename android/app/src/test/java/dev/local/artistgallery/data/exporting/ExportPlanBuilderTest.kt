package dev.local.artistgallery.data.exporting

import dev.local.artistgallery.data.ArtistEntity
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.data.CollectionEntity
import dev.local.artistgallery.data.CollectionMediaEntity
import dev.local.artistgallery.data.backup.BackupAsset
import dev.local.artistgallery.data.backup.BackupManifest
import java.util.Locale
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ExportPlanBuilderTest {
    private val artist = ArtistEntity(id(), "画师", 1, 1)
    private val collection = CollectionEntity(id(), artist.id, "图集", false, 1, 1)
    private val first = asset("原图.jpg")
    private val second = asset("动画.gif", "GIF")
    private fun id() = UUID.randomUUID().toString()
    private fun asset(name: String, type: String = "IMAGE") = BackupAsset(id(), name, type, 20, null, null, "a".repeat(64), 1)
    private fun reference(group: CollectionEntity, item: BackupAsset, order: Int, name: String = item.originalName) =
        CollectionMediaEntity(group.id, item.id, order, name, "深层目录/$name")
    private fun snapshot(
        artists: List<ArtistEntity> = listOf(artist),
        collections: List<CollectionEntity> = listOf(collection),
        assets: List<BackupAsset> = listOf(first, second),
        references: List<CollectionMediaEntity> = listOf(reference(collection, first, 0)),
        settings: BrowseOptions = BrowseOptions(),
    ) = GallerySnapshot(BackupManifest(createdAt = 1, assets = assets, artists = artists, collections = collections, references = references, settings = settings), assets.associate { it.id to "${it.id}.bin" })

    @Test fun directSelectionPreservesSourceNameAndContainsOnlySelectedAssets() {
        val plan = ExportPlanBuilder.build(snapshot(), ExportScope.Assets(listOf(second.id, second.id)))
        assertEquals(listOf(ExportEntry("动画.gif", second.id)), plan.entries)
        assertEquals(listOf(second), plan.assets)
        assertEquals(setOf(second.id), plan.storageKeys.keys)
        assertEquals(1, plan.fileCount)
        assertEquals(20L, plan.totalBytes)
        assertNull(plan.manifest)
    }

    @Test fun allExplicitScopesRejectUnknownOrEmptySelections() {
        val source = snapshot()
        listOf(
            ExportScope.Assets(emptyList()), ExportScope.Assets(listOf(first.id, id())),
            ExportScope.Collections(emptyList()), ExportScope.Collections(listOf(id())),
            ExportScope.Artists(emptyList()), ExportScope.Artists(listOf(id())),
        ).forEach { scope ->
            try { ExportPlanBuilder.build(source, scope); fail("Selection must fail: $scope") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun sharedOriginalIsRepeatedOncePerCollectionButBackedUpOnce() {
        val other = collection.copy(id = id(), name = "另一图集")
        val source = snapshot(collections = listOf(collection, other), references = listOf(reference(collection, first, 0), reference(other, first, 0)))
        val grouped = ExportPlanBuilder.build(source, ExportScope.Artists(listOf(artist.id)))
        assertEquals(listOf(first.id, first.id), grouped.entries.mapNotNull { it.assetId })
        assertEquals(40L, grouped.totalBytes)
        assertEquals(listOf(first), grouped.assets)
        val backup = ExportPlanBuilder.backup(source)
        assertEquals(listOf(first.id, second.id), backup.entries.mapNotNull { it.assetId })
        assertEquals(listOf(first.entryPath, second.entryPath), backup.entries.filter { it.assetId != null }.map { it.path })
        assertSame(source.manifest, backup.manifest)
    }

    @Test fun selectedCollectionsRetainArtistDirectoryAndExcludeOtherGroups() {
        val other = collection.copy(id = id(), name = "另一图集")
        val source = snapshot(collections = listOf(collection, other), references = listOf(reference(collection, first, 0), reference(other, second, 0)))
        val plan = ExportPlanBuilder.build(source, ExportScope.Collections(listOf(other.id)))
        assertEquals(listOf("画师/", "画师/另一图集/", "画师/另一图集/动画.gif"), plan.entries.map { it.path })
        assertEquals(listOf(second), plan.assets)
    }

    @Test fun manualImageOrderAndSavedArtistCollectionOrderArePreserved() {
        val otherArtist = artist.copy(id = id(), name = "先显示")
        val otherCollection = collection.copy(id = id(), name = "先显示图集")
        val source = snapshot(
            artists = listOf(artist, otherArtist), collections = listOf(collection, otherCollection),
            references = listOf(reference(collection, first, 5), reference(collection, second, 1)),
            settings = BrowseOptions(artistOrder = listOf(otherArtist.id, artist.id), collectionOrders = mapOf(artist.id to listOf(otherCollection.id, collection.id))),
        )
        val plan = ExportPlanBuilder.build(source, ExportScope.Library)
        assertEquals(listOf("先显示/", "画师/", "画师/先显示图集/", "画师/图集/", "画师/图集/动画.gif", "画师/图集/原图.jpg"), plan.entries.map { it.path })
    }

    @Test fun libraryPreservesEmptyGroupsAndAddsOnlyUnassignedOriginals() {
        val emptyArtist = artist.copy(id = id(), name = "空画师")
        val emptyCollection = collection.copy(id = id(), name = "空图集")
        val source = snapshot(artists = listOf(artist, emptyArtist), collections = listOf(collection, emptyCollection))
        val plan = ExportPlanBuilder.build(source, ExportScope.Library)
        assertTrue(plan.entries.contains(ExportEntry("空画师/")))
        assertTrue(plan.entries.contains(ExportEntry("画师/空图集/")))
        assertTrue(plan.entries.contains(ExportEntry("未归组媒体/动画.gif", second.id)))
        assertEquals(2, plan.fileCount)
        assertEquals(listOf(ExportEntry("空画师/")), ExportPlanBuilder.build(source, ExportScope.Artists(listOf(emptyArtist.id))).entries)
    }

    @Test fun unsafeNamesDeviceNamesAndCaseCollisionsAreSanitizedWithoutOverwrite() {
        val otherArtist = artist.copy(id = id(), name = "a_b")
        val source = snapshot(
            artists = listOf(artist.copy(name = "A/B"), otherArtist),
            collections = listOf(collection.copy(name = ".."), collection.copy(id = id(), artistId = otherArtist.id, name = "CON")),
            references = listOf(reference(collection, first, 0, "../A?.jpg"), reference(collection, second, 1, "..\\a*.jpg")),
        )
        val plan = ExportPlanBuilder.build(source, ExportScope.Library)
        val paths = plan.entries.map { it.path }
        assertEquals(paths.size, paths.map { it.lowercase(Locale.ROOT).removeSuffix("/") }.toSet().size)
        assertTrue(paths.contains("a_b (2)/"))
        assertTrue(paths.contains("a_b (2)/_CON/"))
        assertEquals(listOf("A_B/未命名/.._A_.jpg", "A_B/未命名/.._a_ (2).jpg"), plan.entries.filter { it.assetId != null }.map { it.path })
        assertTrue(paths.all { '\\' !in it && it.split('/').none { part -> part == ".." } })
    }

    @Test fun longUnicodeNamesKeepExtensionsAndStayWithinPortableSegmentLimit() {
        val one = first.copy(originalName = "图😀".repeat(100) + ".jpeg")
        val two = second.copy(originalName = one.originalName)
        val plan = ExportPlanBuilder.build(snapshot(assets = listOf(one, two), references = emptyList()), ExportScope.Assets(listOf(one.id, two.id)))
        assertTrue(plan.entries.all { it.path.endsWith(".jpeg") && it.path.toByteArray(Charsets.UTF_8).size <= 255 })
        assertTrue(plan.entries[1].path.endsWith(" (2).jpeg"))
        plan.entries.forEach { assertEquals(it.path, it.path.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8)) }
    }

    @Test fun normalizedUnicodeNamesDoNotCollideAndOriginalKeysMustExist() {
        val one = first.copy(originalName = "é.png")
        val two = second.copy(originalName = "e\u0301.png")
        val source = snapshot(assets = listOf(one, two), references = emptyList())
        val plan = ExportPlanBuilder.build(source, ExportScope.Assets(listOf(one.id, two.id)))
        assertEquals(listOf("é.png", "é (2).png"), plan.entries.map { it.path })
        try { ExportPlanBuilder.build(source.copy(storageKeys = emptyMap()), ExportScope.Assets(listOf(one.id))); fail("Missing original key must fail") }
        catch (_: IllegalArgumentException) { }
    }
}
