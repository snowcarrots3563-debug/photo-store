package dev.local.artistgallery.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.local.artistgallery.data.preview.PreviewDecoder

@Composable
fun UnassignedMediaScreen(state: GalleryUiState, padding: Dp, previewDecoder: PreviewDecoder?, onEvent: (GalleryEvent) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(start = padding, end = padding, top = 20.dp, bottom = 126.dp)) {
        item {
            DDetailHeader("未归组媒体", { onEvent(GalleryEvent.OpenImportTarget(toUnassigned = true)) }, { onEvent(GalleryEvent.NavigateBack) })
            dLoadState(state.loadState) { onEvent(GalleryEvent.RetryLoad) }
        }
        if (state.loadState == LoadState.Ready) {
            item {
                Text("${state.mediaItems.size} 项尚未加入作品集", color = DColors.Muted, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 16.dp))
                if (state.mediaItems.isEmpty()) {
                    DEmpty("这里还没有媒体", "导入的图片、GIF 和视频可以先放在这里。", "从相册导入", onClick = { onEvent(GalleryEvent.OpenImportTarget(toUnassigned = true)) })
                } else {
                    val allSelected = state.mediaItems.all { it.id in state.selectedMediaIds }
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        DButton(if (allSelected) "取消全选" else "全选", textAction = true) {
                            state.mediaItems.forEach { item ->
                                if (allSelected == (item.id in state.selectedMediaIds)) onEvent(GalleryEvent.ToggleMediaSelection(item.id))
                            }
                        }
                        DButton("加入作品集", primary = true, enabled = state.selectedMediaIds.isNotEmpty()) { onEvent(GalleryEvent.OpenAddMediaTarget) }
                    }
                }
            }
            itemsIndexed(state.mediaItems, key = { _, media -> media.id }) { index, media ->
                if (index > 0) Spacer(Modifier.height(8.dp))
                MediaListItem(media, previewDecoder, selected = media.id in state.selectedMediaIds, onSelect = { onEvent(GalleryEvent.ToggleMediaSelection(media.id)) }, onOpen = { onEvent(GalleryEvent.OpenMedia(media.id)) })
            }
        }
    }
}
