package dev.local.artistgallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.local.artistgallery.data.preview.PreviewDecoder
import dev.local.artistgallery.model.MediaAssetSummary
import dev.local.artistgallery.model.MediaType

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaListItem(
    item: MediaAssetSummary,
    previewDecoder: PreviewDecoder?,
    selected: Boolean = false,
    onSelect: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    focusOnLoad: Boolean = false,
    revealDetail: Boolean = true,
    thumbnail: (@Composable (Modifier) -> Unit)? = null,
) {
    val background by animateColorAsState(if (selected && onSelect != null) DColors.Soft else DColors.Panel, tween(160), label = "selectedMediaRow")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(background)
            .then(when {
                onSelect != null -> Modifier.combinedClickable(onClick = onSelect, onLongClick = onLongClick)
                onOpen != null -> Modifier.combinedClickable(onClick = onOpen, onLongClick = onLongClick)
                else -> Modifier
            })
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val thumbnailPx = with(LocalDensity.current) { 68.dp.roundToPx() }
        if (thumbnail != null) thumbnail(Modifier.size(68.dp)) else MediaThumbnail(item.storageKey, item.type, previewDecoder, Modifier.size(68.dp), focusOnLoad = focusOnLoad, revealDetail = revealDetail,
            initialSizePx = if (focusOnLoad) IntSize(thumbnailPx, thumbnailPx) else IntSize.Zero)
        Column(Modifier.weight(1f)) {
            Text(item.name, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 2)
            Text(mediaDescription(item), fontSize = 12.sp, lineHeight = 18.sp, color = DColors.Muted, modifier = Modifier.padding(top = 3.dp))
        }
        when {
            onSelect != null -> Checkbox(checked = selected, onCheckedChange = { onSelect() })
            onRemove != null -> DIconButton(DIcon.Trash, if (item.type == MediaType.VIDEO) "删除视频" else "删除图片", tint = DColors.Error, onClick = onRemove)
        }
    }
}

fun mediaDescription(item: MediaAssetSummary): String {
    val type = when (item.type) {
        MediaType.IMAGE -> "图片"
        MediaType.GIF -> "GIF"
        MediaType.VIDEO -> "视频"
    }
    val size = if (item.byteSize < 1024L * 1024) "${item.byteSize / 1024} KB" else "${item.byteSize / (1024L * 1024)} MB"
    return "$type · $size"
}
