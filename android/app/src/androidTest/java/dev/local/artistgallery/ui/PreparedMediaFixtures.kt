package dev.local.artistgallery.ui

import dev.local.artistgallery.data.ArtistImageLayout
import dev.local.artistgallery.model.MediaAssetSummary
import dev.local.artistgallery.model.MediaSort
import dev.local.artistgallery.model.buildArtistGridRows
import java.time.ZoneId

/** Complete screen inputs for synthetic UI tests; production prepares these on its worker. */
internal fun GalleryUiState.withPreparedMedia(items: List<MediaAssetSummary>): GalleryUiState {
    val sort = when (val current = route) {
        is GalleryRoute.Artist -> browseOptions.artistImageSorts[current.id] ?: MediaSort.Default
        is GalleryRoute.Collection -> browseOptions.collectionMediaSorts[current.id] ?: MediaSort.Default
        else -> MediaSort.Default
    }
    val layout = if (route is GalleryRoute.Artist) browseOptions.imageLayout else ArtistImageLayout.List
    val zone = ZoneId.systemDefault()
    val rows = if (route is GalleryRoute.Artist && layout != ArtistImageLayout.List)
        buildArtistGridRows(items, sort, layout.columns, zone) else emptyList()
    return copy(preparedMedia = PreparedMedia(items, sort, layout, zone, rows))
}
