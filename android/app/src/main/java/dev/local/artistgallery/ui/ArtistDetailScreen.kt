package dev.local.artistgallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.IntOffset
import dev.local.artistgallery.model.MediaAssetSummary
import dev.local.artistgallery.model.MediaSort
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import dev.local.artistgallery.data.ArtistImageLayout
import dev.local.artistgallery.data.preview.PreviewDecoder
import dev.local.artistgallery.model.SampleCoverKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun ArtistDetailScreen(state: GalleryUiState, padding: Dp, fixtureCover: (@Composable (SampleCoverKey) -> Unit)?, previewDecoder: PreviewDecoder?, onEvent: (GalleryEvent) -> Unit) {
    val detail = state.artistDetail
    val prepared = state.preparedMedia
    val loadState = if (state.loadState != LoadState.Ready) state.loadState else state.mediaLoadState
    // An empty loading list would clamp a restored item index to zero.
    if (loadState != LoadState.Ready || detail == null || prepared == null) {
        Column(Modifier.fillMaxSize().padding(horizontal = padding, vertical = 20.dp)) {
            DDetailHeader(detail?.artist?.name.orEmpty(), { onEvent(GalleryEvent.OpenDialog(GalleryDialog.ArtistMenu)) }, { onEvent(GalleryEvent.NavigateBack) })
            dLoadState(loadState) { onEvent(GalleryEvent.RetryLoad) }
        }
        return
    }
    val imageSort = prepared.sort
    val scrollKey = "artist:${detail.artist.id}:${state.artistTab}:${prepared.layout.name}" +
        if (state.artistTab == ArtistTab.Images) ":${imageSort.name}" else ""
    key(scrollKey) {
        val position = state.scrollPositions[scrollKey] ?: GalleryScrollPosition()
        val listState = rememberLazyListState(position.index, position.offset)
        var collapsedTabs by rememberSaveable(scrollKey) {
            mutableFloatStateOf(if (position.index > 0 || position.offset > 0) Float.MAX_VALUE else 0f)
        }
        var tabsHeight by remember { mutableFloatStateOf(0f) }
        val scrollScope = rememberCoroutineScope()
        var scrollTopJob by remember { mutableStateOf<Job?>(null) }
        LaunchedEffect(state.overlay) { if (state.overlay != GalleryOverlay.None) scrollTopJob?.cancel() }
        val scrollToTop: () -> Unit = {
            scrollTopJob?.cancel()
            if (state.overlay == GalleryOverlay.None && (listState.canScrollBackward || collapsedTabs > 0f)) {
                scrollTopJob = scrollScope.launch {
                    if (listState.canScrollBackward) listState.animateScrollToItem(0)
                    if (collapsedTabs > 0f) animate(collapsedTabs, 0f, animationSpec = tween(250)) { value, _ -> collapsedTabs = value }
                }
            }
        }
        val tabScrollConnection = remember(listState) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && available.y != 0f) scrollTopJob?.cancel()
                    if (available.y >= 0f || tabsHeight == 0f) return Offset.Zero
                    val before = collapsedTabs
                    collapsedTabs = (before - available.y).coerceIn(0f, tabsHeight)
                    return Offset(0f, before - collapsedTabs)
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (available.y <= 0f || listState.canScrollBackward) return Offset.Zero
                    val before = collapsedTabs
                    collapsedTabs = (before - available.y).coerceAtLeast(0f)
                    return Offset(0f, before - collapsedTabs)
                }
            }
        }
        val currentOnEvent by rememberUpdatedState(onEvent)
        val rememberPosition = { currentOnEvent(GalleryEvent.RememberScrollPosition(scrollKey, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)) }
        DisposableEffect(listState, scrollKey) { onDispose { rememberPosition() } }
        val artist = detail.artist
        val mediaLabel = if (artist.videoCount > 0) "全部媒体" else "全部图片"
        val selectingCollections = state.entitySelectionKind == EntitySelectionKind.Collections
        val collectionIds = remember(detail.collections) { detail.collections.map { it.id } }
        val reorder = rememberReorderRows(collectionIds, state.artistTab == ArtistTab.Collections && selectingCollections && !state.isSaving, listState)
        val collectionsById = remember(detail.collections) { detail.collections.associateBy { it.id } }
        val commitOrder = { ids: List<String> -> onEvent(GalleryEvent.ReorderCollections(artist.id, ids)) }
        val columns = prepared.layout.columns
        val gridRows = prepared.gridRows
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(start = padding, end = padding, top = 20.dp)) {
                Column(Modifier.fillMaxWidth().testTag("artistHeader")) {
                    if (state.mediaSelectionMode) {
                        DDetailHeader("已选择 ${state.selectedMediaIds.size} 项", {}, { onEvent(GalleryEvent.ExitMediaSelection) }, onDoubleTap = scrollToTop, trailing = {
                            MediaSelectionDeleteButton(state.selectedMediaIds.size, state.isSaving) { onEvent(GalleryEvent.OpenDeleteSelectedMedia) }
                        })
                    } else if (selectingCollections) {
                        DDetailHeader("已选择 ${state.selectedEntityIds.size} 项", {}, { onEvent(GalleryEvent.ExitEntitySelection) }, onDoubleTap = scrollToTop, trailing = {
                            DIconButton(DIcon.Trash, "删除所选作品集", state.selectedEntityIds.isNotEmpty() && !state.isSaving, tint = DColors.Error) { onEvent(GalleryEvent.OpenDeleteSelectedEntities) }
                        })
                    } else DDetailHeader(artist.name, { onEvent(GalleryEvent.OpenDialog(GalleryDialog.ArtistMenu)) }, { rememberPosition(); onEvent(GalleryEvent.NavigateBack) }, onDoubleTap = scrollToTop)
                }
                Text("${artist.collectionCount} 个作品集 · ${artist.imageCount} 张图片" + if (artist.videoCount > 0) " · ${artist.videoCount} 个视频" else "", fontSize = 13.sp, lineHeight = 20.sp, color = DColors.Muted, modifier = Modifier.padding(vertical = 16.dp).testTag("artistSummary"))
                Box(Modifier.fillMaxWidth().testTag("artistTabs").clipToBounds().layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    tabsHeight = placeable.height.toFloat()
                    collapsedTabs = collapsedTabs.coerceIn(0f, tabsHeight)
                    val hiddenHeight = collapsedTabs.roundToInt()
                    layout(placeable.width, placeable.height - hiddenHeight) { placeable.placeRelative(0, -hiddenHeight) }
                }) {
                    Row(Modifier.padding(top = 8.dp, bottom = 24.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFFE9EBEF)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                        ArtistTab.entries.forEach { tab ->
                            val selected = state.artistTab == tab
                            Box(Modifier.weight(1f).heightIn(min = 48.dp)
                                .semantics { this.selected = selected }.clickable(enabled = !state.mediaSelectionMode && !selectingCollections && !state.isSaving, role = Role.Tab) { rememberPosition(); onEvent(GalleryEvent.SwitchArtistTab(tab)) }, contentAlignment = Alignment.Center) {
                                Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(20.dp)).background(if (selected) Color.White else Color.Transparent).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    Text(if (tab == ArtistTab.Images) mediaLabel else "作品集", fontSize = 15.sp, color = if (selected) DColors.Action else DColors.Ink)
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("artistSection"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    if (state.artistTab == ArtistTab.Images) {
                        Text("$mediaLabel · ${artist.imageCount + artist.videoCount}", fontSize = 13.sp, lineHeight = 20.sp, color = DColors.Muted, modifier = Modifier.weight(1f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DIconButton(DIcon.SortArrows, if (artist.videoCount > 0) "媒体排序" else "图片排序", !state.isSaving && !state.mediaSelectionMode,
                                tint = if (imageSort == MediaSort.Default) DColors.Ink else DColors.Action) {
                                rememberPosition(); onEvent(GalleryEvent.OpenDialog(GalleryDialog.ArtistImageSort))
                            }
                            DButton("切换视图", soft = true, enabled = !state.isSaving && !state.mediaSelectionMode) { rememberPosition(); onEvent(GalleryEvent.CycleImageLayout) }
                        }
                    } else {
                        Text("${artist.collectionCount} 个作品集", color = DColors.Muted, fontSize = 13.sp, lineHeight = 20.sp)
                        DButton("新建作品集", textAction = true, enabled = !selectingCollections && !state.isSaving) { onEvent(GalleryEvent.OpenDialog(GalleryDialog.CreateCollection(artist.id))) }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { reorder.viewport = it.boundsInRoot() }.nestedScroll(tabScrollConnection).testTag("artistMediaList"), state = listState, contentPadding = PaddingValues(start = padding, end = padding, bottom = 126.dp)) {
                if (state.artistTab == ArtistTab.Images) {
                    if (state.mediaItems.isEmpty()) item {
                        val defaultName = detail.collections.firstOrNull { it.isDefault }?.name ?: "默认图集"
                        DEmpty("还没有图片", "导入到$defaultName，也可以选择其他作品集。", "导入") { onEvent(GalleryEvent.OpenDialog(GalleryDialog.AddMenu)) }
                    }
                    else if (prepared.layout == ArtistImageLayout.List) itemsIndexed(state.mediaItems, key = { _, media -> media.id }) { index, media ->
                        if (index > 0) Spacer(Modifier.height(8.dp))
                        if (state.mediaSelectionMode) {
                            Box(Modifier.testTag("artistSelectedRow:${media.id}")) {
                                MediaListItem(media, previewDecoder, selected = media.id in state.selectedMediaIds, onSelect = { onEvent(GalleryEvent.ToggleMediaSelection(media.id)) })
                            }
                        } else if (!state.browseOptions.swipeDelete) {
                            MediaListItem(media, previewDecoder,
                                onOpen = { rememberPosition(); onEvent(GalleryEvent.OpenMedia(media.id)) },
                                onLongClick = { onEvent(GalleryEvent.StartMediaSelection(media.id)) })
                        } else SwipeMediaItem(media, previewDecoder, { onEvent(GalleryEvent.OpenDeleteMedia(media.id)) }, { rememberPosition(); onEvent(GalleryEvent.OpenMedia(media.id)) }, { onEvent(GalleryEvent.StartMediaSelection(media.id)) })
                    }
                    else itemsIndexed(gridRows, key = { _, row -> row.tiles.first().media.id }) { index, row ->
                        Column {
                            if (row.dateTitle != null) {
                                Text(row.dateTitle, fontSize = 14.sp, lineHeight = 20.sp, color = DColors.Ink,
                                    modifier = Modifier.padding(top = if (index == 0) 4.dp else 16.dp, bottom = 10.dp).testTag("artistDateHeader:${row.tiles.first().media.id}"))
                            } else if (index > 0) Spacer(Modifier.height(3.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                row.tiles.forEach { tile ->
                                    val media = tile.media
                                    val selected = media.id in state.selectedMediaIds
                                    Column(Modifier.weight(1f)) {
                                        Box(Modifier.fillMaxWidth().aspectRatio(1f).testTag("artistTile:${media.id}")
                                            .semantics {
                                                contentDescription = media.name
                                                if (state.mediaSelectionMode) {
                                                    this.selected = selected
                                                    toggleableState = if (selected) ToggleableState.On else ToggleableState.Off
                                                }
                                                else customActions = listOf(CustomAccessibilityAction("删除") { onEvent(GalleryEvent.OpenDeleteMedia(media.id)); true })
                                            }.combinedClickable(role = if (state.mediaSelectionMode) Role.Checkbox else Role.Button,
                                                onLongClick = if (state.mediaSelectionMode) null else ({ onEvent(GalleryEvent.StartMediaSelection(media.id)) }),
                                                onClick = {
                                                    if (state.mediaSelectionMode) onEvent(GalleryEvent.ToggleMediaSelection(media.id))
                                                    else { rememberPosition(); onEvent(GalleryEvent.OpenMedia(media.id)) }
                                                })) {
                                            MediaThumbnail(media.storageKey, media.type, previewDecoder, Modifier.fillMaxSize(), cornerRadius = 0.dp)
                                            if (state.mediaSelectionMode) {
                                                Box(Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(6.dp)).background(DColors.Panel)) {
                                                    Checkbox(checked = selected, onCheckedChange = null)
                                                }
                                            }
                                        }
                                        if (tile.addedDateText != null) Text(tile.addedDateText, fontSize = 11.sp, lineHeight = 16.sp,
                                            color = DColors.Muted, textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("artistDate:${media.id}"))
                                    }
                                }
                                repeat(columns - row.tiles.size) { Spacer(Modifier.weight(1f).aspectRatio(1f)) }
                            }
                        }
                    }
                } else if (detail.collections.isEmpty()) item {
                    DEmpty("还没有作品集", "名称可以稍后再想。", "先创建一个") { onEvent(GalleryEvent.OpenDialog(GalleryDialog.CreateCollection(artist.id))) }
                } else itemsIndexed(reorder.order, key = { _, id -> "collection:$id" }) { index, id ->
                    val c = collectionsById[id] ?: return@itemsIndexed
                    val isSelected = c.id in state.selectedEntityIds
                    ReorderRow(reorder, c.id, Modifier.testTag("collectionRow:${c.id}").semantics {
                        if (selectingCollections && !c.isDefault) {
                            selected = isSelected
                            toggleableState = if (isSelected) ToggleableState.On else ToggleableState.Off
                        }
                    }) {
                        DGroupRow(index == 0, index == reorder.order.lastIndex) {
                            if (index > 0) DDivider()
                            DRow(c.name, "${c.imageCount} 张图片" + if (c.videoCount > 0) " · ${c.videoCount} 个视频" else "", cover = c.cover, fixtureCover = fixtureCover, previewDecoder = previewDecoder,
                                onLongClick = if (c.isDefault || selectingCollections || state.isSaving) null else ({ onEvent(GalleryEvent.StartEntitySelection(EntitySelectionKind.Collections, c.id)) }),
                                trailing = if (selectingCollections) ({
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(!c.isDefault && c.id in state.selectedEntityIds, onCheckedChange = null, enabled = !c.isDefault && !state.isSaving,
                                            modifier = Modifier.testTag("collectionCheckbox:${c.id}").semantics { if (c.isDefault) disabled() })
                                        ReorderHandle(reorder, c.id, c.name, !state.isSaving, commitOrder)
                                    }
                                }) else null) {
                                if (!state.isSaving) {
                                    if (selectingCollections && !c.isDefault) onEvent(GalleryEvent.ToggleEntitySelection(c.id))
                                    else { rememberPosition(); onEvent(GalleryEvent.OpenCollection(c.id, c.artistId)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeMediaItem(item: MediaAssetSummary, decoder: PreviewDecoder?, onDelete: () -> Unit, onOpen: () -> Unit, onLongClick: () -> Unit) {
    val width = with(LocalDensity.current) { 80.dp.toPx() }
    var revealed by rememberSaveable(item.id) { mutableStateOf(false) }
    var offset by remember(item.id, width) { mutableFloatStateOf(if (revealed) -width else 0f) }
    val scope = rememberCoroutineScope()
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val settle: (Boolean) -> Unit = { reveal ->
        settleJob?.cancel()
        revealed = reveal
        settleJob = scope.launch {
            animate(offset, if (reveal) -width else 0f, animationSpec = tween(180)) { value, _ -> offset = value }
        }
    }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).testTag("artistSwipe:${item.id}")
        .pointerInput(item.id, width) {
            detectHorizontalDragGestures(
                onDragStart = { settleJob?.cancel() },
                onDragEnd = { settle(offset < -width / 2) },
                onDragCancel = { settle(revealed) },
            ) { change, delta ->
                change.consume()
                offset = (offset + delta).coerceIn(-width, 0f)
            }
        }.semantics {
            customActions = listOf(CustomAccessibilityAction("删除") { onDelete(); true })
        }) {
        Box(Modifier.matchParentSize().background(DColors.Error), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.width(80.dp).fillMaxHeight().testTag("artistSwipeDelete:${item.id}").clickable { settle(false); onDelete() }, contentAlignment = Alignment.Center) {
                Text("删除", color = Color.White, fontSize = 15.sp)
            }
        }
        Box(Modifier.offset { IntOffset(offset.roundToInt(), 0) }.testTag("artistSwipeContent:${item.id}")) {
            MediaListItem(item, decoder, onOpen = { if (offset < 0f) settle(false) else onOpen() }, onLongClick = { settle(false); onLongClick() })
        }
    }
}
