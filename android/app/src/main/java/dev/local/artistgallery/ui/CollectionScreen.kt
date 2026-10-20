package dev.local.artistgallery.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import dev.local.artistgallery.data.preview.PreviewDecoder
import dev.local.artistgallery.model.MediaSort
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectionScreen(state: GalleryUiState, padding: Dp, previewDecoder: PreviewDecoder?, onEvent: (GalleryEvent) -> Unit) {
    val collection = state.collection
    val prepared = state.preparedMedia
    val loadState = if (state.loadState != LoadState.Ready) state.loadState else state.mediaLoadState
    if (loadState != LoadState.Ready || collection == null || prepared == null) {
        Column(Modifier.fillMaxSize().padding(horizontal = padding, vertical = 20.dp)) {
            DDetailHeader(collection?.name.orEmpty(), { onEvent(GalleryEvent.OpenDialog(GalleryDialog.CollectionMenu)) }, { onEvent(GalleryEvent.NavigateBack) })
            dLoadState(loadState) { onEvent(GalleryEvent.RetryLoad) }
        }
        return
    }
    val sort = prepared.sort
    val scrollKey = "collection:${collection.id}" + if (sort == MediaSort.Default) "" else ":${sort.name}"
    key(scrollKey) {
        val position = state.scrollPositions[scrollKey] ?: GalleryScrollPosition()
        val listState = rememberLazyListState(position.index, position.offset)
        val scrollScope = rememberCoroutineScope()
        var scrollTopJob by remember { mutableStateOf<Job?>(null) }
        LaunchedEffect(state.overlay) { if (state.overlay != GalleryOverlay.None) scrollTopJob?.cancel() }
        val scrollToTop: () -> Unit = {
            scrollTopJob?.cancel()
            if (state.overlay == GalleryOverlay.None && listState.canScrollBackward) {
                scrollTopJob = scrollScope.launch { listState.animateScrollToItem(0) }
            }
        }
        val currentOnEvent by rememberUpdatedState(onEvent)
        val rememberPosition = { currentOnEvent(GalleryEvent.RememberScrollPosition(scrollKey, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)) }
        DisposableEffect(listState, scrollKey) { onDispose { rememberPosition() } }
        val header: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth().background(DColors.Background)) {
                DDetailHeader(if (state.mediaSelectionMode) "已选择 ${state.selectedMediaIds.size} 项" else collection.name,
                    { onEvent(GalleryEvent.OpenDialog(GalleryDialog.CollectionMenu)) },
                    { rememberPosition(); onEvent(if (state.mediaSelectionMode) GalleryEvent.ExitMediaSelection else GalleryEvent.NavigateBack) },
                    onDoubleTap = scrollToTop,
                    trailing = if (state.mediaSelectionMode) {{ MediaSelectionDeleteButton(state.selectedMediaIds.size, state.isSaving) { onEvent(GalleryEvent.OpenDeleteSelectedMedia) } }} else null)
            }
        }
        LazyColumn(Modifier.fillMaxSize().testTag("collectionMediaList"), state = listState, contentPadding = PaddingValues(start = padding, end = padding, top = 20.dp, bottom = 126.dp)) {
            stickyHeader { Box(Modifier.testTag(if (state.mediaSelectionMode) "collectionSelectionHeader" else "collectionHeader")) { header() } }
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${state.artistHeader?.name.orEmpty()} · ${collection.imageCount} 张图片" + if (collection.videoCount > 0) " · ${collection.videoCount} 个视频" else "",
                        color = DColors.Muted, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
                    DIconButton(DIcon.SortArrows, "图集排序", !state.isSaving && !state.mediaSelectionMode,
                        tint = if (sort == MediaSort.Default) DColors.Muted else DColors.Action) {
                        rememberPosition(); onEvent(GalleryEvent.OpenDialog(GalleryDialog.CollectionMediaSort))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    DButton("漫画阅读", icon = DIcon.Comic, soft = true, enabled = state.mediaItems.any { it.type != dev.local.artistgallery.model.MediaType.VIDEO }) { rememberPosition(); onEvent(GalleryEvent.OpenComic) }
                    DButton("导入媒体", icon = DIcon.Import) { onEvent(GalleryEvent.OpenImportTarget(collection.artistId, collection.id)) }
                }
            }
            if (state.mediaItems.isEmpty()) item { DEmpty("给这次收藏留个位置", "导入图片后，会完整显示在这里。", "导入") { onEvent(GalleryEvent.OpenImportTarget(collection.artistId, collection.id)) } }
            else itemsIndexed(state.mediaItems, key = { _, media -> media.id }) { index, media ->
                if (index > 0) Spacer(Modifier.height(8.dp))
                MediaListItem(media, previewDecoder, selected = media.id in state.selectedMediaIds,
                    onSelect = if (state.mediaSelectionMode) {{ onEvent(GalleryEvent.ToggleMediaSelection(media.id)) }} else null,
                    onRemove = if (state.mediaSelectionMode) null else {{ onEvent(GalleryEvent.OpenDeleteMedia(media.id)) }},
                    onOpen = { rememberPosition(); onEvent(GalleryEvent.OpenMedia(media.id)) },
                    onLongClick = if (state.mediaSelectionMode) null else {{ onEvent(GalleryEvent.StartMediaSelection(media.id)) }})
            }
        }
    }
}
