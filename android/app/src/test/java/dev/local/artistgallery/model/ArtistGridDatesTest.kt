package dev.local.artistgallery.model

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ArtistGridDatesTest {
    private val utc = ZoneId.of("UTC")
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val firstDay = Instant.parse("2026-10-12T12:00:00Z").toEpochMilli()
    private val nextDay = Instant.parse("2026-10-13T12:00:00Z").toEpochMilli()
    private val timeSorts = listOf(MediaSort.Default, MediaSort.AddedAscending, MediaSort.AddedDescending)
    private val otherSorts = MediaSort.entries.filterNot { it in timeSorts }
    private fun media(id: String, time: Long = firstDay) =
        MediaAssetSummary(id, "$id.png", MediaType.IMAGE, 6000, 0, "$id.png", "key-$id", time)
    private fun rows(items: List<MediaAssetSummary>, sort: MediaSort = MediaSort.Default, columns: Int = 3, zone: ZoneId = utc) =
        buildArtistGridRows(items, sort, columns, zone)
    private fun ids(rows: List<ArtistGridRow>) = rows.flatMap { it.tiles }.map { it.media.id }

    @Test fun emptyInputHasNoDateHeadingOrRowsInAnyMode() {
        MediaSort.entries.forEach { sort -> listOf(3, 5).forEach { columns -> assertTrue(rows(emptyList(), sort, columns).isEmpty()) } }
    }

    @Test fun sameDayUsesOneHeadingAndEachThumbnailColumnCount() {
        val items = List(11) { media("image$it", firstDay + it) }
        listOf(3, 5).forEach { columns ->
            val result = rows(items, columns = columns)
            assertEquals((items.size + columns - 1) / columns, result.size)
            assertEquals(listOf("2026年10月12日"), result.mapNotNull { it.dateTitle })
            assertTrue(result.all { it.tiles.size <= columns })
            assertTrue(result.flatMap { it.tiles }.all { it.addedDateText == null })
            assertEquals(items.map { it.id }, ids(result))
        }
    }

    @Test fun nextDateBeginsANewRowInsteadOfFillingThePreviousDatesGap() {
        val items = listOf(media("old-b"), media("old-a"), media("new", nextDay))
        listOf(3, 5).forEach { columns ->
            val result = rows(items, columns = columns)
            assertEquals(listOf(2, 1), result.map { it.tiles.size })
            assertEquals(listOf("2026年10月12日", "2026年10月13日"), result.mapNotNull { it.dateTitle })
            assertEquals(items.map { it.id }, ids(result))
        }
    }

    @Test fun everyTimeModeKeepsTheProvidedWithinDayAndDateRunOrder() {
        val ascending = listOf(media("z"), media("a"), media("later", nextDay))
        val descending = listOf(media("later", nextDay), media("z"), media("a"))
        timeSorts.forEach { sort ->
            val items = if (sort == MediaSort.AddedDescending) descending else ascending
            val result = rows(items, sort)
            assertEquals(items.map { it.id }, ids(result))
            assertEquals(if (sort == MediaSort.AddedDescending) listOf("2026年10月13日", "2026年10月12日")
                else listOf("2026年10月12日", "2026年10月13日"), result.mapNotNull { it.dateTitle })
        }
    }

    @Test fun aRepeatedNonMonotonicDateCreatesANewRunWithoutRegrouping() {
        val items = listOf(media("old"), media("new", nextDay), media("old-again"))
        timeSorts.forEach { sort ->
            val result = rows(items, sort)
            assertEquals(items.map { it.id }, ids(result))
            assertEquals(listOf("2026年10月12日", "2026年10月13日", "2026年10月12日"), result.mapNotNull { it.dateTitle })
        }
    }

    @Test fun sizeAndNameModesKeepInterleavedDatesAndShowCaptionsForEachImage() {
        val items = listOf(media("b", nextDay), media("a"), media("d", nextDay), media("c"))
        otherSorts.forEach { sort ->
            val result = rows(items, sort)
            assertEquals(listOf(3, 1), result.map { it.tiles.size })
            assertTrue(result.all { it.dateTitle == null })
            assertEquals(items.map { it.id }, ids(result))
            assertEquals(listOf("2026.10.13", "2026.10.12", "2026.10.13", "2026.10.12"), result.flatMap { it.tiles }.map { it.addedDateText })
        }
    }

    @Test fun theSameInstantsUseTheExplicitLocalZoneForGroupingAndCaptions() {
        val boundary = Instant.parse("2026-10-12T16:00:00Z").toEpochMilli()
        val items = listOf(media("before", boundary - 1), media("after", boundary))
        assertEquals(listOf("2026年10月12日"), rows(items, zone = utc).mapNotNull { it.dateTitle })
        assertEquals(listOf("2026年10月12日", "2026年10月13日"), rows(items, zone = shanghai).mapNotNull { it.dateTitle })
        assertEquals(listOf("2026.10.12", "2026.10.12"), rows(items, MediaSort.NameAscending, zone = utc).flatMap { it.tiles }.map { it.addedDateText })
        assertEquals(listOf("2026.10.12", "2026.10.13"), rows(items, MediaSort.NameAscending, zone = shanghai).flatMap { it.tiles }.map { it.addedDateText })
    }

    @Test fun midnightImmediatelyStartsTheNextLocalDay() {
        val midnight = Instant.parse("2026-10-12T00:00:00Z").toEpochMilli()
        val result = rows(listOf(media("before", midnight - 1), media("midnight", midnight), media("after", midnight + 1)))
        assertEquals(listOf("2026年10月11日", "2026年10月12日"), result.mapNotNull { it.dateTitle })
        assertEquals(listOf(1, 2), result.map { it.tiles.size })
    }

    @Test fun titlesUseTheCalendarYearAcrossNewYear() {
        val items = listOf(media("december", Instant.parse("2025-12-31T12:00:00Z").toEpochMilli()),
            media("january", Instant.parse("2026-01-01T12:00:00Z").toEpochMilli()))
        assertEquals(listOf("2025年12月31日", "2026年1月1日"), rows(items).mapNotNull { it.dateTitle })
    }

    @Test fun missingTimesRemainUnknownAndDoNotJoinARealEpochDate() {
        val items = listOf(media("zero", 0), media("negative", -1), media("positive", 1))
        val grouped = rows(items)
        assertEquals(listOf("日期未知", "1970年1月1日"), grouped.mapNotNull { it.dateTitle })
        assertEquals(listOf(2, 1), grouped.map { it.tiles.size })
        assertEquals(listOf("日期未知", "日期未知", "1970.01.01"), rows(items, MediaSort.SizeDescending).flatMap { it.tiles }.map { it.addedDateText })
        assertEquals(items.map { it.id }, ids(grouped))
    }

    @Test fun captionsKeepTheFullYearAndZeroPaddedMonthAndDay() {
        val result = rows(listOf(media("winter", Instant.parse("2026-01-02T12:00:00Z").toEpochMilli())), MediaSort.NameDescending)
        assertEquals("2026.01.02", result.single().tiles.single().addedDateText)
    }

    @Test fun manyImagesRemainBoundedUniqueAndNeverMutateTheInput() {
        val items = List(1000) { media("image$it", firstDay + (it % 7) * 86_400_000L) }
        val original = items.toList()
        MediaSort.entries.forEach { sort -> listOf(3, 5).forEach { columns ->
            val result = rows(items, sort, columns)
            assertEquals(original.map { it.id }, ids(result))
            assertTrue(result.all { it.tiles.size in 1..columns })
            val keys = result.map { it.tiles.first().media.id }
            assertEquals(keys.size, keys.toSet().size)
        } }
        assertEquals(original, items)
    }
}
