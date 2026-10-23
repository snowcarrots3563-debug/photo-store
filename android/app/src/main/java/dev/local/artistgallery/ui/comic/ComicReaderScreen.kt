package dev.local.artistgallery.ui.comic

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.local.artistgallery.data.preview.PreviewDecoder
import dev.local.artistgallery.model.MediaAssetSummary
import dev.local.artistgallery.model.MediaType
import dev.local.artistgallery.model.naturalCompare
import dev.local.artistgallery.ui.AnimatedGifImage
import dev.local.artistgallery.ui.PreviewImage
import dev.local.artistgallery.ui.rememberPreviewImage
import dev.local.artistgallery.ui.DColors
import dev.local.artistgallery.ui.DIcon
import dev.local.artistgallery.ui.DIconButton
import dev.local.artistgallery.ui.galleryDialogWindowInsets
import dev.local.artistgallery.ui.DIconImage
import dev.local.artistgallery.ui.ComicDirection
import dev.local.artistgallery.ui.GalleryEvent
import dev.local.artistgallery.ui.GalleryUiState
import dev.local.artistgallery.ui.GalleryScrollPosition
import dev.local.artistgallery.ui.DAnimatedDialog
import dev.local.artistgallery.ui.DPopupCard
import dev.local.artistgallery.ui.DPopupHeader
import dev.local.artistgallery.ui.LocalDDialogActive

@Composable
fun ComicReaderScreen(state: GalleryUiState, previewDecoder: PreviewDecoder?, onEvent: (GalleryEvent) -> Unit) {
    val collection = state.collection
    val items = remember(state.mediaItems, state.comicDirection) {
        val ascending = state.mediaItems.filter { it.type != MediaType.VIDEO }.sortedWith { left, right ->
            val byName = naturalCompare(left.name, right.name)
            if (byName != 0) byName else compareValuesBy(left, right, MediaAssetSummary::manualOrder, MediaAssetSummary::id)
        }
        if (state.comicDirection == ComicDirection.NameDescending) ascending.asReversed() else ascending
    }
    val collectionId = collection?.id ?: return
    var previousCollection by remember { mutableStateOf(collectionId) }
    var previousDirection by remember { mutableStateOf(state.comicDirection) }
    val directionChanged = remember(collectionId, state.comicDirection) {
        val changed = previousCollection == collectionId && previousDirection != state.comicDirection
        previousCollection = collectionId
        previousDirection = state.comicDirection
        changed
    }
    val scrollKey = "comic:$collectionId:${state.comicDirection.name}"
    key(scrollKey) {
    val position = if (directionChanged) GalleryScrollPosition() else state.scrollPositions[scrollKey] ?: GalleryScrollPosition()
    val listState = rememberLazyListState(position.index, position.offset)
    val currentOnEvent by rememberUpdatedState(onEvent)
    val rememberPosition = { currentOnEvent(GalleryEvent.RememberScrollPosition(scrollKey, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)) }
    DisposableEffect(listState, scrollKey) { onDispose { rememberPosition() } }
    val density = LocalDensity.current
    val widthPx = with(density) { 430.dp.roundToPx() }
    val visibleIds by remember(items) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { info -> items.getOrNull(info.index)?.id }.toSet()
        }
    }
    var showSort by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(DColors.Background)) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            DIconButton(DIcon.Back, "退出漫画阅读") { rememberPosition(); onEvent(GalleryEvent.CloseOverlay) }
            Text(collection?.name.orEmpty(), Modifier.weight(1f).padding(horizontal = 8.dp), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            DIconButton(DIcon.Sort, "漫画阅读顺序") { showSort = true }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("comicList"), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(items, key = { it.id }) { item ->
                ComicMedia(item, previewDecoder, isVisible = item.id in visibleIds, playGifs = state.browseOptions.playGifs, targetWidthPx = widthPx, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("comicPage:${item.id}")) {
                    rememberPosition()
                    onEvent(GalleryEvent.OpenMedia(item.id))
                }
            }
        }
    }
    ComicSortDialog(state.comicDirection.takeIf { showSort }, onDismiss = { showSort = false }, onSelect = {
        showSort = false
        if (it != state.comicDirection) { rememberPosition(); onEvent(GalleryEvent.ToggleComicDirection) }
    })
    }
}

@Composable
private fun ComicMedia(item: MediaAssetSummary, decoder: PreviewDecoder?, isVisible: Boolean, playGifs: Boolean, targetWidthPx: Int, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.background(Color(0xFFEFF1F5)).clickable(onClick = onClick)) {
        val targetHeightPx = targetWidthPx * 4
        if (item.type == MediaType.GIF && decoder != null && playGifs) {
            AnimatedGifImage(item.storageKey, decoder, Modifier.fillMaxWidth().wrapContentHeight(), isVisible, targetWidthPx, targetHeightPx)
        } else if (decoder != null) {
            val image = rememberPreviewImage(item.storageKey, decoder, targetWidthPx, targetHeightPx, isCurrent = isVisible)
            val bitmap = (image as? PreviewImage.Still)?.bitmap
            if (bitmap != null) {
                Image(bitmap.asImageBitmap(), item.name, Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)), contentScale = ContentScale.Fit)
            } else Box(Modifier.fillMaxWidth().height(280.dp))
        } else Box(Modifier.fillMaxWidth().height(280.dp))
        Text(item.name, Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = .88f)).padding(horizontal = 7.dp, vertical = 3.dp), fontSize = 10.sp, color = DColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ComicSortDialog(direction: ComicDirection?, onDismiss: () -> Unit, onSelect: (ComicDirection) -> Unit) {
    DAnimatedDialog(direction, onDismissRequest = onDismiss) { presentation, panel, fraction ->
        val active = LocalDDialogActive.current
        val dialogInsets = galleryDialogWindowInsets()
        DPopupCard(panel, fraction, dialogInsets, onDismiss) { card ->
            Column(card.verticalScroll(rememberScrollState())) {
                DPopupHeader("漫画阅读顺序", onDismiss)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ComicSortOption("名称正序", ComicDirection.NameAscending, presentation) { if (active) onSelect(it) }
                    ComicSortOption("名称倒序", ComicDirection.NameDescending, presentation) { if (active) onSelect(it) }
                }
            }
        }
    }
}

@Composable
private fun ComicSortOption(label: String, option: ComicDirection, selected: ComicDirection, onSelect: (ComicDirection) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).clip(RoundedCornerShape(17.dp)).semantics { this.selected = option == selected }
        .clickable(enabled = LocalDDialogActive.current) { onSelect(option) }.padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DIconImage(DIcon.Sort, Color(0xFF6B7D98), 23.dp, strokeWidth = 1.75f)
        Text(label, Modifier.weight(1f), fontSize = 15.sp, lineHeight = 24.sp, color = if (option == selected) DColors.Action else DColors.Ink)
        if (option == selected) DIconImage(DIcon.Check, DColors.Action, 18.dp)
    }
}

