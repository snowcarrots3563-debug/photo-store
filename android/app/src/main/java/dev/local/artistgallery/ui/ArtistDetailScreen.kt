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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.local.artistgallery.model.MediaAssetSummary
import dev.local.artistgallery.model.MediaSort
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import dev.local.artistgallery.model.MediaType
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun ArtistDetailScreen(state: GalleryUiState, padding: Dp, fixtureCover: (@Composable (SampleCoverKey) -> Unit)?, previewDecoder: PreviewDecoder?, onEvent: (GalleryEvent) -> Unit) {
    ArtistDetailScreen(state, padding, fixtureCover, previewDecoder, state.artistTab, onEvent)
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun ArtistDetailScreen(state: GalleryUiState, padding: Dp, fixtureCover: (@Composable (SampleCoverKey) -> Unit)?, previewDecoder: PreviewDecoder?, tabTransitionFrom: ArtistTab, onEvent: (GalleryEvent) -> Unit) {
    val artistId = (state.route as? GalleryRoute.Artist)?.id ?: state.artistDetail?.artist?.id.orEmpty()
    val detail = state.artistDetail?.takeIf { it.artist.id == artistId }
    val artist = detail?.artist ?: state.artists.firstOrNull { it.id == artistId }
    val prepared = state.preparedMedia
    val loadState = if (state.loadState != LoadState.Ready) state.loadState else state.mediaLoadState
    val dataReady = loadState == LoadState.Ready && detail != null && prepared != null
    val imageLayout = prepared?.layout ?: state.browseOptions.imageLayout
    val imageSort = prepared?.sort ?: state.browseOptions.artistImageSorts[artistId] ?: MediaSort.Default
    val tabPill = remember(artistId) { Animatable(if (tabTransitionFrom == ArtistTab.Images) 0f else 1f) }
    LaunchedEffect(state.artistTab) { tabPill.animateTo(if (state.artistTab == ArtistTab.Images) 0f else 1f, tween(280)) }
    var layoutAnchor by remember(artistId, state.artistTab) { mutableStateOf<Pair<ArtistImageLayout, String>?>(null) }
    var layoutTabs by remember(artistId, state.artistTab) { mutableStateOf<Float?>(null) }
    val layoutMotion = remember(artistId, state.artistTab) { ArtistLayoutMotionState() }
    LaunchedEffect(imageSort) { layoutMotion.cancel() }
    LaunchedEffect(imageSort, state.overlay, state.dialog, state.mediaSelectionMode, loadState) {
        if (state.overlay != GalleryOverlay.None || state.dialog != GalleryDialog.None || state.mediaSelectionMode || loadState == LoadState.Failed) layoutMotion.cancel()
    }
    val anchoredPosition = remember(prepared, layoutAnchor) {
        val anchor = layoutAnchor
        if (prepared == null || anchor == null || prepared.layout != anchor.first) null else {
            val index = if (prepared.layout == ArtistImageLayout.List) prepared.items.indexOfFirst { it.id == anchor.second }
                else prepared.gridRows.indexOfFirst { row -> row.tiles.any { it.media.id == anchor.second } }
            index.takeIf { it >= 0 }?.let { GalleryScrollPosition(it) }
        }
    }
    val scrollKey = "artist:$artistId:${state.artistTab}:${imageLayout.name}" +
        if (state.artistTab == ArtistTab.Images) ":${imageSort.name}" else ""
    Box(Modifier.fillMaxSize()) {
    key(scrollKey) {
        val position = anchoredPosition ?: state.scrollPositions[scrollKey] ?: GalleryScrollPosition()
        val listState = rememberLazyListState(position.index, position.offset)
        var realListWasBound by remember { mutableStateOf(false) }
        var collapsedTabs by rememberSaveable(scrollKey) {
            mutableFloatStateOf(layoutTabs ?: if (position.index > 0 || position.offset > 0) Float.MAX_VALUE else 0f)
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
                    if (source == NestedScrollSource.UserInput && available != Offset.Zero) layoutMotion.cancel()
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
        val canRememberPosition by rememberUpdatedState(realListWasBound)
        val rememberPosition = {
            if (canRememberPosition) currentOnEvent(GalleryEvent.RememberScrollPosition(scrollKey, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset))
        }
        DisposableEffect(listState, scrollKey) { onDispose { rememberPosition() } }
        val mediaLabel = if ((artist?.videoCount ?: 0) > 0) "全部媒体" else "全部图片"
        val selectingCollections = state.entitySelectionKind == EntitySelectionKind.Collections
        val collections = detail?.collections.orEmpty()
        val collectionIds = remember(collections) { collections.map { it.id } }
        val reorder = rememberReorderRows(collectionIds, state.artistTab == ArtistTab.Collections && selectingCollections && !state.isSaving, listState)
        val collectionsById = remember(collections) { collections.associateBy { it.id } }
        val commitOrder = { ids: List<String> -> onEvent(GalleryEvent.ReorderCollections(artistId, ids)) }
        val columns = imageLayout.columns
        val gridRows = prepared?.gridRows.orEmpty()
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
                    } else DDetailHeader(artist?.name ?: "\u00A0", { onEvent(GalleryEvent.OpenDialog(GalleryDialog.ArtistMenu)) }, { rememberPosition(); onEvent(GalleryEvent.NavigateBack) }, onDoubleTap = scrollToTop)
                }
                Box(Modifier.padding(vertical = 16.dp).testTag("artistSummary")) {
                    Text(artist?.let { "${it.collectionCount} 个作品集 · ${it.imageCount} 张图片" + if (it.videoCount > 0) " · ${it.videoCount} 个视频" else "" } ?: "\u00A0", fontSize = 13.sp, lineHeight = 20.sp, color = DColors.Muted)
                    if (artist == null) Box(Modifier.align(Alignment.CenterStart).width(176.dp).height(13.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFE8EAEE)))
                }
                Box(Modifier.fillMaxWidth().testTag("artistTabs").clipToBounds().layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    tabsHeight = placeable.height.toFloat()
                    collapsedTabs = collapsedTabs.coerceIn(0f, tabsHeight)
                    val hiddenHeight = collapsedTabs.roundToInt()
                    layout(placeable.width, placeable.height - hiddenHeight) { placeable.placeRelative(0, -hiddenHeight) }
                }) {
                    BoxWithConstraints(Modifier.padding(top = 8.dp, bottom = 24.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFFE9EBEF)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                        Box(Modifier.matchParentSize()) {
                            Box(Modifier.fillMaxWidth(.5f).fillMaxHeight().graphicsLayer { translationX = size.width * tabPill.value }
                                .padding(vertical = 2.dp).clip(RoundedCornerShape(20.dp)).background(Color.White))
                        }
                        Row(Modifier.fillMaxWidth()) {
                        ArtistTab.entries.forEach { tab ->
                            val selected = state.artistTab == tab
                            Box(Modifier.weight(1f).heightIn(min = 48.dp)
                                .semantics { this.selected = selected }.clickable(enabled = !state.mediaSelectionMode && !selectingCollections && !state.isSaving, role = Role.Tab) { rememberPosition(); onEvent(GalleryEvent.SwitchArtistTab(tab)) }, contentAlignment = Alignment.Center) {
                                Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    Text(if (tab == ArtistTab.Images) mediaLabel else "作品集", fontSize = 15.sp, color = if (selected) DColors.Action else DColors.Ink)
                                }
                            }
                        }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("artistSection"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    if (state.artistTab == ArtistTab.Images) {
                        Text(artist?.let { "$mediaLabel · ${it.imageCount + it.videoCount}" } ?: mediaLabel, fontSize = 13.sp, lineHeight = 20.sp, color = DColors.Muted, modifier = Modifier.weight(1f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DIconButton(DIcon.SortArrows, if ((artist?.videoCount ?: 0) > 0) "媒体排序" else "图片排序", !state.isSaving && !state.mediaSelectionMode,
                                tint = if (imageSort == MediaSort.Default) DColors.Ink else DColors.Action) {
                                rememberPosition(); onEvent(GalleryEvent.OpenDialog(GalleryDialog.ArtistImageSort))
                            }
                            DButton("切换视图", soft = true, enabled = !state.isSaving && !state.mediaSelectionMode) {
                                val first = listState.firstVisibleItemIndex
                                val media = if (imageLayout == ArtistImageLayout.List) prepared?.items?.getOrNull(first)
                                    else prepared?.gridRows?.getOrNull(first)?.tiles?.firstOrNull()?.media
                                val next = when (state.browseOptions.imageLayout) {
                                    ArtistImageLayout.List -> ArtistImageLayout.Large
                                    ArtistImageLayout.Large -> ArtistImageLayout.Medium
                                    ArtistImageLayout.Medium -> ArtistImageLayout.List
                                }
                                layoutAnchor = media?.let { next to it.id }
                                layoutTabs = collapsedTabs
                                layoutMotion.begin(next)
                                rememberPosition(); onEvent(GalleryEvent.CycleImageLayout)
                            }
                        }
                    } else {
                        Text(artist?.let { "${it.collectionCount} 个作品集" } ?: "作品集", color = DColors.Muted, fontSize = 13.sp, lineHeight = 20.sp)
                        DButton("新建作品集", textAction = true, enabled = !selectingCollections && !state.isSaving) { onEvent(GalleryEvent.OpenDialog(GalleryDialog.CreateCollection(artistId))) }
                    }
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned {
                val bounds = androidx.compose.ui.geometry.Rect(it.positionInRoot(), androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat()))
                val before = layoutMotion.viewport
                if (before.width > 0 && before.size != bounds.size) layoutMotion.cancel()
                layoutMotion.viewport = bounds
            }.pointerInput(layoutMotion) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.changedToDownIgnoreConsumed() }) layoutMotion.cancel()
                    }
                }
            }) {
                val tileSize = ((maxWidth - padding * 2 - 3.dp * (columns - 1)) / columns).coerceAtLeast(1.dp)
                val listLayout = imageLayout == ArtistImageLayout.List
                val viewportRows = ceil(maxHeight.value / (if (listLayout) 92f else tileSize.value)).toInt() + 2
                val density = LocalDensity.current
                val thumbnailPx = with(density) { 68.dp.roundToPx() }
                // Row weights distribute leftover physical pixels from the first column.
                val availablePx = with(density) { constraints.maxWidth - padding.roundToPx() * 2 - 3.dp.roundToPx() * (columns - 1) }
                val roundedCellPx = (availablePx.toFloat() / columns).roundToInt()
                val remainder = availablePx - roundedCellPx * columns
                val cellWidths = List(columns) { column ->
                    (roundedCellPx + if (column < kotlin.math.abs(remainder)) remainder.compareTo(0) else 0).coerceAtLeast(1)
                }
                val rowCount = if (listLayout) state.mediaItems.size else gridRows.size
                val needsPreviews = dataReady && state.artistTab == ArtistTab.Images && previewDecoder != null && rowCount > 0
                fun rowMedia(index: Int) = if (listLayout) listOf(state.mediaItems[index]) else gridRows[index].tiles.map { it.media }
                fun cached(media: MediaAssetSummary, column: Int): Boolean {
                    val pixels = if (listLayout) thumbnailPx else cellWidths[column]
                    return previewDecoder?.cachedThumbnail(media.storageKey, pixels, pixels, media.type) != null ||
                        (media.type != MediaType.VIDEO && previewDecoder?.cachedGaussianPreview(media.storageKey, media.type) != null)
                }
                fun windowStart(index: Int) = index.coerceIn(0, (rowCount - 1).coerceAtLeast(0))
                    .coerceAtMost((rowCount - viewportRows).coerceAtLeast(0))
                var initialPreviewsReady by remember(prepared, previewDecoder, cellWidths, needsPreviews) {
                    val start = windowStart(listState.firstVisibleItemIndex)
                    mutableStateOf(!needsPreviews || (start until (start + viewportRows).coerceAtMost(rowCount)).all { index ->
                        rowMedia(index).withIndex().all { (column, media) -> cached(media, column) }
                    })
                }
                LaunchedEffect(prepared, previewDecoder, cellWidths, viewportRows, needsPreviews) {
                    val decoder = previewDecoder ?: return@LaunchedEffect
                    if (!needsPreviews) return@LaunchedEffect
                    suspend fun prepareRows(start: Int, end: Int) {
                        for (index in start until end) {
                            rowMedia(index).forEachIndexed { column, media ->
                                if (!cached(media, column)) {
                                    try {
                                        if (media.type == MediaType.VIDEO) {
                                            val pixels = if (listLayout) thumbnailPx else cellWidths[column]
                                            decoder.decodeThumbnail(media.storageKey, pixels, pixels, media.type)
                                        } else decoder.decodeGaussianPreview(media.storageKey, media.type)
                                    } catch (error: CancellationException) { throw error }
                                    catch (_: Exception) { /* Retain the existing damaged-original placeholder. */ }
                                }
                            }
                        }
                    }
                    snapshotFlow { listState.firstVisibleItemIndex }.distinctUntilChanged().collectLatest { index ->
                        // Restoring near the end can fill earlier rows as well.
                        val start = windowStart(index)
                        val end = (start + viewportRows).coerceAtMost(rowCount)
                        prepareRows(start, end)
                        initialPreviewsReady = true
                        prepareRows(end, (end + viewportRows).coerceAtMost(rowCount))
                    }
                }
                val loading = loadState != LoadState.Failed && (!dataReady || !initialPreviewsReady)
                val reveal = remember { Animatable(if (loading) 0f else 1f) }
                var revealSkeleton by remember { mutableStateOf(loading) }
                LaunchedEffect(loading, loadState, layoutMotion.session != null) {
                    if (loadState == LoadState.Failed) {
                        revealSkeleton = false
                        reveal.snapTo(1f)
                    } else if (layoutMotion.session != null) {
                        revealSkeleton = false
                        reveal.snapTo(1f)
                    } else if (loading) {
                        revealSkeleton = true
                        reveal.snapTo(0f)
                    } else if (revealSkeleton) {
                        reveal.animateTo(1f, tween(350, easing = LinearOutSlowInEasing))
                        revealSkeleton = false
                    }
                }
                val skeletonPhase = if (loadState != LoadState.Failed && (loading || revealSkeleton)) rememberHomeLoadingPhase() else null
                when {
                    loadState == LoadState.Failed -> Box(Modifier.fillMaxWidth().padding(horizontal = padding)) {
                        dLoadState(LoadState.Failed) { onEvent(GalleryEvent.RetryLoad) }
                    }
                    loading -> if (layoutMotion.session == null) ArtistLoadingSkeleton(imageLayout, state.artistTab, prepared, imageSort, position, padding, requireNotNull(skeletonPhase), detail?.collections, artist?.collectionCount, state.mediaSelectionMode, selectingCollections)
                    else -> {
                        SideEffect {
                            realListWasBound = true
                            layoutMotion.bind(imageLayout)
                            if (layoutAnchor?.first == imageLayout) { layoutAnchor = null; layoutTabs = null }
                        }
                        LazyColumn(Modifier.fillMaxSize().graphicsLayer {
                            alpha = layoutMotion.contentAlpha(imageLayout)
                        }.semantics { motionFraction = layoutMotion.contentAlpha(imageLayout) }
                            .onGloballyPositioned { reorder.viewport = it.boundsInRoot() }.nestedScroll(tabScrollConnection).testTag("artistMediaList"), state = listState, contentPadding = PaddingValues(start = padding, end = padding, bottom = 126.dp)) {
                if (state.artistTab == ArtistTab.Images) {
                    if (state.mediaItems.isEmpty()) item {
                        val defaultName = collections.firstOrNull { it.isDefault }?.name ?: "默认图集"
                        DEmpty("还没有图片", "导入到$defaultName，也可以选择其他作品集。", "导入") { onEvent(GalleryEvent.OpenDialog(GalleryDialog.AddMenu)) }
                    }
                    else if (imageLayout == ArtistImageLayout.List) itemsIndexed(state.mediaItems, key = { _, media -> media.id }) { index, media ->
                        if (index > 0) Spacer(Modifier.height(8.dp))
                        Box {
                        val thumbnail: @Composable (Modifier) -> Unit = { modifier ->
                            ArtistLayoutThumbnail(media, previewDecoder, layoutMotion, imageLayout, modifier, 18.dp,
                                !revealSkeleton, IntSize(thumbnailPx, thumbnailPx))
                        }
                        if (state.mediaSelectionMode) {
                            Box(Modifier.testTag("artistSelectedRow:${media.id}")) {
                                MediaListItem(media, previewDecoder, selected = media.id in state.selectedMediaIds, onSelect = { onEvent(GalleryEvent.ToggleMediaSelection(media.id)) }, focusOnLoad = true, revealDetail = !revealSkeleton, thumbnail = thumbnail)
                            }
                        } else if (!state.browseOptions.swipeDelete) {
                            MediaListItem(media, previewDecoder,
                                onOpen = { rememberPosition(); onEvent(GalleryEvent.OpenMedia(media.id)) },
                                onLongClick = { onEvent(GalleryEvent.StartMediaSelection(media.id)) }, focusOnLoad = true, revealDetail = !revealSkeleton, thumbnail = thumbnail)
                        } else SwipeMediaItem(media, previewDecoder, { onEvent(GalleryEvent.OpenDeleteMedia(media.id)) }, { rememberPosition(); onEvent(GalleryEvent.OpenMedia(media.id)) }, { onEvent(GalleryEvent.StartMediaSelection(media.id)) }, revealDetail = !revealSkeleton, thumbnail = thumbnail)
                        if (revealSkeleton && skeletonPhase != null) ArtistLoadingRow(skeletonPhase,
                            Modifier.matchParentSize().graphicsLayer { alpha = 1f - reveal.value })
                        }
                    }
                    else itemsIndexed(gridRows, key = { _, row -> row.tiles.first().media.id }) { index, row ->
                        Column {
                            if (row.dateTitle != null) {
                                Text(row.dateTitle, fontSize = 14.sp, lineHeight = 20.sp, color = DColors.Ink,
                                    modifier = Modifier.padding(top = if (index == 0) 4.dp else 16.dp, bottom = 10.dp).testTag("artistDateHeader:${row.tiles.first().media.id}"))
                            } else if (index > 0) Spacer(Modifier.height(3.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                row.tiles.forEachIndexed { column, tile ->
                                    val media = tile.media
                                    val selected = media.id in state.selectedMediaIds
                                    val selectionScale = animateFloatAsState(if (state.mediaSelectionMode && selected) .94f else 1f, tween(160), label = "mediaSelection")
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
                                            ArtistLayoutThumbnail(media, previewDecoder, layoutMotion, imageLayout, Modifier.fillMaxSize().graphicsLayer { scaleX = selectionScale.value; scaleY = selectionScale.value }, cornerRadius = 0.dp, revealDetail = !revealSkeleton,
                                                initialSizePx = IntSize(cellWidths[column], cellWidths[column]))
                                            if (revealSkeleton && skeletonPhase != null) ArtistLoadingTile(skeletonPhase,
                                                Modifier.matchParentSize().graphicsLayer { alpha = 1f - reveal.value })
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
                } else if (collections.isEmpty()) item {
                    DEmpty("还没有作品集", "名称可以稍后再想。", "先创建一个") { onEvent(GalleryEvent.OpenDialog(GalleryDialog.CreateCollection(artistId))) }
                } else itemsIndexed(reorder.order, key = { _, id -> "collection:$id" }) { index, id ->
                    val c = collectionsById[id] ?: return@itemsIndexed
                    val isSelected = c.id in state.selectedEntityIds
                    ReorderRow(reorder, c.id, Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null,
                        placementSpec = if (selectingCollections && reorder.draggedId != c.id && reorder.settlingId != c.id) spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = .85f) else null)
                        .testTag("collectionRow:${c.id}").semantics {
                        if (selectingCollections && !c.isDefault) {
                            selected = isSelected
                            toggleableState = if (isSelected) ToggleableState.On else ToggleableState.Off
                        }
                    }, itemKey = "collection:${c.id}") {
                        DGroupRow(index == 0, index == reorder.order.lastIndex) {
                            if (index > 0) DDivider()
                            Box {
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
                            if (revealSkeleton && skeletonPhase != null) HomeLoadingSkeletonRow(skeletonPhase,
                                Modifier.matchParentSize().graphicsLayer { alpha = 1f - reveal.value })
                            }
                        }
                    }
                }
            }
                    }
                }
            }
        }
    }
    ArtistLayoutMotionOverlay(layoutMotion, Modifier.matchParentSize())
    }
}

@Composable
private fun SwipeMediaItem(item: MediaAssetSummary, decoder: PreviewDecoder?, onDelete: () -> Unit, onOpen: () -> Unit, onLongClick: () -> Unit, revealDetail: Boolean = true, thumbnail: (@Composable (Modifier) -> Unit)? = null) {
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
            MediaListItem(item, decoder, onOpen = { if (offset < 0f) settle(false) else onOpen() }, onLongClick = { settle(false); onLongClick() }, focusOnLoad = true, revealDetail = revealDetail, thumbnail = thumbnail)
        }
    }
}
