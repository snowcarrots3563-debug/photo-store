package dev.local.artistgallery.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ArtistGridTile(val media: MediaAssetSummary, val addedDateText: String? = null)
data class ArtistGridRow(val tiles: List<ArtistGridTile>, val dateTitle: String? = null)

private val gridDateTitle = DateTimeFormatter.ofPattern("uuuu年M月d日", Locale.ROOT)
private val gridDateCaption = DateTimeFormatter.ofPattern("uuuu.MM.dd", Locale.ROOT)

fun buildArtistGridRows(
    items: List<MediaAssetSummary>,
    sort: MediaSort,
    columns: Int,
    zoneId: ZoneId,
): List<ArtistGridRow> {
    val dateSections = sort == MediaSort.Default || sort == MediaSort.AddedAscending || sort == MediaSort.AddedDescending
    val rows = mutableListOf<ArtistGridRow>()
    var start = 0
    while (start < items.size) {
        val date = addedDate(items[start], zoneId)
        var end = if (dateSections) start + 1 else items.size
        if (dateSections) while (end < items.size && addedDate(items[end], zoneId) == date) end++
        items.subList(start, end).chunked(columns).forEachIndexed { index, row ->
            rows += ArtistGridRow(
                tiles = row.map { media ->
                    ArtistGridTile(media, if (dateSections) null else addedDate(media, zoneId)?.format(gridDateCaption) ?: "日期未知")
                },
                dateTitle = if (dateSections && index == 0) date?.format(gridDateTitle) ?: "日期未知" else null,
            )
        }
        start = end
    }
    return rows
}

private fun addedDate(media: MediaAssetSummary, zoneId: ZoneId): LocalDate? =
    if (media.createdAt > 0L) Instant.ofEpochMilli(media.createdAt).atZone(zoneId).toLocalDate() else null
