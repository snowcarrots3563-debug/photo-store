package dev.local.artistgallery.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.local.artistgallery.data.ArtistImageLayout
import dev.local.artistgallery.model.MediaSort
import dev.local.artistgallery.model.MediaAssetSummary
import dev.local.artistgallery.model.MediaType
import dev.local.artistgallery.model.CollectionSummary
import dev.local.artistgallery.model.CoverRef

@Composable
internal fun ArtistLoadingTile(phase: State<Float>, modifier: Modifier) {
    Canvas(modifier) {
        val x = size.width * phase.value
        drawRect(Brush.linearGradient(listOf(Color(0xFFE8EAEE), Color(0xFFF5F6F8), Color(0xFFE8EAEE)),
            start = Offset(x, 0f), end = Offset(x + size.width * .6f, size.height)))
    }
}

@Composable
internal fun ArtistLoadingRow(phase: State<Float>, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(22.dp))) {
        drawRect(DColors.Panel)
        val x = size.width * phase.value
        val brush = Brush.linearGradient(listOf(Color(0xFFE8EAEE), Color(0xFFF5F6F8), Color(0xFFE8EAEE)),
            start = Offset(x, 0f), end = Offset(x + size.width * .45f, size.height))
        val top = (size.height - 68.dp.toPx()) / 2f
        drawRoundRect(brush, Offset(14.dp.toPx(), top), Size(68.dp.toPx(), 68.dp.toPx()), CornerRadius(18.dp.toPx()))
        val left = 94.dp.toPx()
        val width = (size.width - left - 24.dp.toPx()).coerceAtLeast(0f)
        drawRoundRect(brush, Offset(left, top + 13.dp.toPx()), Size(width * .72f, 16.dp.toPx()), CornerRadius(5.dp.toPx()))
        drawRoundRect(brush, Offset(left, top + 42.dp.toPx()), Size(width * .5f, 12.dp.toPx()), CornerRadius(4.dp.toPx()))
    }
}

/** This decorative list has its own state; it can never clamp the real saved media position. */
@Composable
internal fun ArtistLoadingSkeleton(layout: ArtistImageLayout, tab: ArtistTab, prepared: PreparedMedia?,
                                   sort: MediaSort, position: GalleryScrollPosition, padding: Dp, phase: State<Float>,
                                   collections: List<CollectionSummary>?, collectionCount: Int?, mediaSelection: Boolean, collectionSelection: Boolean) {
    val rows = prepared?.gridRows.orEmpty()
    val list = layout == ArtistImageLayout.List || tab == ArtistTab.Collections
    val dateSections = sort == MediaSort.Default || sort == MediaSort.AddedAscending || sort == MediaSort.AddedDescending
    val skeletonState = rememberLazyListState(position.index, position.offset)
    LazyColumn(Modifier.fillMaxSize().testTag("artistDetailLoading").semantics {
        contentDescription = "正在读取画师媒体"
        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
    }, state = skeletonState, userScrollEnabled = false,
        contentPadding = PaddingValues(start = padding, end = padding, bottom = 126.dp)) {
        if (tab == ArtistTab.Collections) {
            val count = (collections?.size ?: collectionCount ?: (position.index + 10)).coerceAtLeast(1)
            items(count) { index ->
                Box(Modifier.fillMaxWidth().testTag("artistLoadingCollection:$index")) {
                DGroupRow(index == 0, index == count - 1) {
                    if (index > 0) DDivider()
                    Box(Modifier.clearAndSetSemantics {}) {
                        val collection = collections?.getOrNull(index)
                        DRow(collection?.name ?: "\u00A0", collection?.let { "${it.imageCount} 张图片" + if (it.videoCount > 0) " · ${it.videoCount} 个视频" else "" } ?: "\u00A0",
                            cover = collection?.cover ?: CoverRef.None, trailing = if (collectionSelection) ({
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Checkbox(false, onCheckedChange = null)
                                    Box(Modifier.size(48.dp))
                                }
                            }) else null) {}
                        HomeLoadingSkeletonRow(phase, Modifier.matchParentSize())
                    }
                }
                }
            }
        } else if (list) {
            items(prepared?.items?.size?.coerceAtLeast(1) ?: (position.index + 10)) { index ->
                if (index > 0) Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().testTag("artistLoadingRow:$index")) {
                    Box(Modifier.clearAndSetSemantics {}) {
                        val media = prepared?.items?.getOrNull(index) ?: MediaAssetSummary("", "\u00A0", MediaType.IMAGE, 0, 0, "", "")
                        MediaListItem(media, null, onSelect = if (mediaSelection) ({}) else null)
                        ArtistLoadingRow(phase, Modifier.matchParentSize())
                    }
                }
            }
        } else if (rows.isNotEmpty()) {
            itemsIndexed(rows, key = { _, row -> row.tiles.first().media.id }) { index, row ->
                Column {
                    if (row.dateTitle != null) Text(row.dateTitle, fontSize = 14.sp, lineHeight = 20.sp, color = DColors.Ink,
                        modifier = Modifier.padding(top = if (index == 0) 4.dp else 16.dp, bottom = 10.dp))
                    else if (index > 0) Spacer(Modifier.height(3.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        row.tiles.forEachIndexed { column, tile ->
                            Column(Modifier.weight(1f)) {
                                ArtistLoadingTile(phase, Modifier.fillMaxWidth().aspectRatio(1f).testTag("artistLoadingTile:$index:$column"))
                                if (tile.addedDateText != null) Text(tile.addedDateText, fontSize = 11.sp, lineHeight = 16.sp,
                                    color = DColors.Muted, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                            }
                        }
                        repeat(layout.columns - row.tiles.size) { Spacer(Modifier.weight(1f).aspectRatio(1f)) }
                    }
                }
            }
        } else {
            items(position.index + 14) { index ->
                Column {
                    if (index == 0 && dateSections) Box(Modifier.padding(top = 4.dp, bottom = 10.dp).clearAndSetSemantics {}) {
                        Text("\u00A0", fontSize = 14.sp, lineHeight = 20.sp)
                        Box(Modifier.align(androidx.compose.ui.Alignment.CenterStart).width(112.dp).height(14.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFE8EAEE)))
                    } else if (index > 0) Spacer(Modifier.height(3.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        repeat(layout.columns) { column ->
                            Column(Modifier.weight(1f)) {
                                ArtistLoadingTile(phase, Modifier.fillMaxWidth().aspectRatio(1f).testTag("artistLoadingTile:$index:$column"))
                                if (!dateSections) Text("\u00A0", fontSize = 11.sp, lineHeight = 16.sp,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clearAndSetSemantics {})
                            }
                        }
                    }
                }
            }
        }
    }
}
