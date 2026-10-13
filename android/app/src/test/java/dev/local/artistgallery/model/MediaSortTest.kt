package dev.local.artistgallery.model

import org.junit.Assert.*
import org.junit.Test

class MediaSortTest {
    private fun media(id: String, name: String = "$id.png", size: Long = 1, added: Long = 1) =
        MediaAssetSummary(id, name, MediaType.IMAGE, size, 0, name, "key-$id", added)

    private fun ids(items: List<MediaAssetSummary>, sort: MediaSort) = sortArtistImages(items, sort).map { it.id }

    @Test fun defaultMatchesAddedAscendingAndCanBeRestoredAfterCustomSort() {
        val items = listOf(media("z", size = 1, added = 9), media("b", size = 5, added = 2), media("a", size = 3, added = 2))
        assertEquals(listOf("a", "b", "z"), ids(items, MediaSort.Default))
        assertEquals(ids(items, MediaSort.Default), ids(items, MediaSort.AddedAscending))
        assertEquals(listOf("a", "b", "z"), ids(sortArtistImages(items, MediaSort.SizeDescending), MediaSort.Default))
    }

    @Test fun sizeUsesLongComparisonInBothDirections() {
        val items = listOf(media("largest", size = Long.MAX_VALUE), media("smallest", size = 1), media("middle", size = Int.MAX_VALUE.toLong() + 1))
        assertEquals(listOf("smallest", "middle", "largest"), ids(items, MediaSort.SizeAscending))
        assertEquals(listOf("largest", "middle", "smallest"), ids(items, MediaSort.SizeDescending))
    }

    @Test fun addedTimeUsesLongComparisonInBothDirections() {
        val items = listOf(media("late", added = Long.MAX_VALUE), media("early", added = 0), media("middle", added = 1))
        assertEquals(listOf("early", "middle", "late"), ids(items, MediaSort.AddedAscending))
        assertEquals(listOf("late", "middle", "early"), ids(items, MediaSort.AddedDescending))
    }

    @Test fun nameUsesNaturalNumbersAndDescendingReversesOnlyNames() {
        val items = listOf(media("ten", "10.png"), media("two", "2.png"), media("one", "1.png"))
        assertEquals(listOf("one", "two", "ten"), ids(items, MediaSort.NameAscending))
        assertEquals(listOf("ten", "two", "one"), ids(items, MediaSort.NameDescending))
    }

    @Test fun allEqualPrimaryValuesKeepIdsAscendingInEverySort() {
        val items = listOf(media("z", "same.png"), media("b", "same.png"), media("a", "same.png"))
        MediaSort.entries.forEach { assertEquals(it.name, listOf("a", "b", "z"), ids(items, it)) }
    }

    @Test fun naturalNameIgnoresCaseButKeepsStableIdsForCaseOnlyDifference() {
        val items = listOf(media("z", "Page2.PNG"), media("a", "page2.png"), media("next", "PAGE10.png"))
        assertEquals(listOf("a", "z", "next"), ids(items, MediaSort.NameAscending))
        assertEquals(listOf("next", "a", "z"), ids(items, MediaSort.NameDescending))
    }

    @Test fun leadingZerosPreserveExistingComicComparisonFallback() {
        assertTrue(naturalCompare("page01.png", "page1.png") < 0)
        assertTrue(naturalCompare("page1.png", "page2.png") < 0)
        assertEquals(0, naturalCompare("page000.png", "PAGE000.PNG"))
    }

    @Test fun veryLongDigitsCompareWithoutNumericOverflow() {
        val first = "9".repeat(99)
        val next = "1" + "0".repeat(99)
        assertTrue(naturalCompare("page$first.png", "page$next.png") < 0)
        assertTrue(naturalCompare("page$next.png", "page$first.png") > 0)
    }

    @Test fun namesCompareEveryNumericSegmentAndRemainingCharacters() {
        assertTrue(naturalCompare("2-page9.png", "2-page10.png") < 0)
        assertTrue(naturalCompare("a", "a1") < 0)
        assertTrue(naturalCompare("2-page10.jpg", "2-page10.png") < 0)
    }

    @Test fun emptyAndSingleListsRemainValidAndSourceListIsUnchanged() {
        MediaSort.entries.forEach { assertTrue(sortArtistImages(emptyList(), it).isEmpty()) }
        val items = listOf(media("b"), media("a"))
        assertEquals(listOf(items.first()), sortArtistImages(listOf(items.first()), MediaSort.NameDescending))
        sortArtistImages(items, MediaSort.NameAscending)
        assertEquals(listOf("b", "a"), items.map { it.id })
    }

    @Test fun legacySummaryConstructionDefaultsAddedTimeToZero() {
        assertEquals(0L, MediaAssetSummary("id", "name", MediaType.IMAGE, 1, 0, "name", "key").createdAt)
    }

    @Test fun collectionDefaultUsesManualOrderThenIdInsteadOfAddedTime() {
        val items = listOf(media("z", added = 1).copy(manualOrder = 7), media("b", added = 9).copy(manualOrder = 2),
            media("a", added = 8).copy(manualOrder = 2))
        assertEquals(listOf("a", "b", "z"), sortCollectionMedia(items, MediaSort.Default).map { it.id })
        assertEquals(listOf("z", "a", "b"), sortCollectionMedia(items, MediaSort.AddedAscending).map { it.id })
    }

    @Test fun collectionCustomSortsUseDisplayNameSizeAndAddedTimeIncludingVideos() {
        val items = listOf(media("ten", "10.png", size = 30, added = 10).copy(manualOrder = 0),
            media("one", "1.png", size = 20, added = 30).copy(manualOrder = 1, type = MediaType.GIF),
            media("two", "2.mp4", size = 10, added = 20).copy(manualOrder = 2, type = MediaType.VIDEO))
        val expected = mapOf(MediaSort.SizeAscending to listOf("two", "one", "ten"),
            MediaSort.SizeDescending to listOf("ten", "one", "two"),
            MediaSort.AddedAscending to listOf("ten", "two", "one"),
            MediaSort.AddedDescending to listOf("one", "two", "ten"),
            MediaSort.NameAscending to listOf("one", "two", "ten"),
            MediaSort.NameDescending to listOf("ten", "two", "one"))
        expected.forEach { (sort, ids) -> assertEquals(sort.name, ids, sortCollectionMedia(items, sort).map { it.id }) }
    }

    @Test fun collectionAllEqualPrimaryValuesKeepIdsAscendingInEveryDirection() {
        val items = listOf(media("z", "same.png"), media("b", "same.png"), media("a", "same.png"))
        MediaSort.entries.forEach { assertEquals(it.name, listOf("a", "b", "z"), sortCollectionMedia(items, it).map { it.id }) }
    }

    @Test fun collectionCanReturnToManualOrderAfterEveryCustomSortWithoutChangingSource() {
        val items = listOf(media("last", "1.png", size = 1, added = 1).copy(manualOrder = 9),
            media("first", "10.png", size = 9, added = 9).copy(manualOrder = 0))
        MediaSort.entries.forEach { sort ->
            assertEquals(listOf("first", "last"), sortCollectionMedia(sortCollectionMedia(items, sort), MediaSort.Default).map { it.id })
        }
        assertEquals(listOf("last", "first"), items.map { it.id })
        assertEquals(listOf(9, 0), items.map { it.manualOrder })
    }

    @Test fun collectionEmptyAndSingleListsRemainValidInEverySort() {
        MediaSort.entries.forEach { sort ->
            assertTrue(sortCollectionMedia(emptyList(), sort).isEmpty())
            val item = media("single").copy(manualOrder = Int.MAX_VALUE)
            assertEquals(listOf(item), sortCollectionMedia(listOf(item), sort))
        }
    }
}
