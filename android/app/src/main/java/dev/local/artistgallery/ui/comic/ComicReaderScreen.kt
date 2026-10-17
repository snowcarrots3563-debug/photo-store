package dev.local.artistgallery.ui.comic

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val widthPx = with(density) { 430.dp.roundToPx() }
    val visibleIds by remember(items) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { info -> items.getOrNull(info.index)?.id }.toSet()
        }
    }
    var showSort by remember { mutableStateOf(false) }
    LaunchedEffect(state.comicDirection, state.route) { listState.scrollToItem(0) }

    Column(Modifier.fillMaxSize().background(DColors.Background)) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            DIconButton(DIcon.Back, "退出漫画阅读") { onEvent(GalleryEvent.CloseOverlay) }
            Text(collection?.name.orEmpty(), Modifier.weight(1f).padding(horizontal = 8.dp), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            DIconButton(DIcon.Sort, "漫画阅读顺序") { showSort = true }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(items, key = { it.id }) { item ->
                ComicMedia(item, previewDecoder, isVisible = item.id in visibleIds, playGifs = state.browseOptions.playGifs, targetWidthPx = widthPx, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    onEvent(GalleryEvent.OpenMedia(item.id))
                }
            }
        }
    }
    if (showSort) ComicSortDialog(state.comicDirection, onDismiss = { showSort = false }, onSelect = {
        showSort = false
        if (it != state.comicDirection) onEvent(GalleryEvent.ToggleComicDirection)
    })
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
private fun ComicSortDialog(direction: ComicDirection, onDismiss: () -> Unit, onSelect: (ComicDirection) -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(decorFitsSystemWindows = false)) {
        val dialogInsets = galleryDialogWindowInsets()
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .22f)).windowInsetsPadding(dialogInsets), contentAlignment = Alignment.TopEnd) {
            Column(Modifier.padding(top = 132.dp, end = 16.dp).width(240.dp).clip(RoundedCornerShape(28.dp)).background(DColors.Panel).padding(vertical = 12.dp)) {
                Text("漫画阅读顺序", fontSize = 13.sp, lineHeight = 20.sp, color = DColors.Muted, modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 6.dp))
                ComicSortOption("名称正序", ComicDirection.NameAscending, direction, onSelect)
                ComicSortOption("名称倒序", ComicDirection.NameDescending, direction, onSelect)
            }
        }
    }
}

@Composable
private fun ComicSortOption(label: String, option: ComicDirection, selected: ComicDirection, onSelect: (ComicDirection) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onSelect(option) }.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 16.sp, lineHeight = 24.sp, color = if (option == selected) DColors.Action else DColors.Ink)
        if (option == selected) DIconImage(DIcon.Check, DColors.Action, 18.dp)
    }
}

