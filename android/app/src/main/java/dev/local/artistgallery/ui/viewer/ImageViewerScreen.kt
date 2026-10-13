package dev.local.artistgallery.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.local.artistgallery.data.preview.PreviewDecoder
import dev.local.artistgallery.model.MediaAssetSummary
import dev.local.artistgallery.model.MediaType
import dev.local.artistgallery.ui.DButton
import dev.local.artistgallery.ui.DColors
import dev.local.artistgallery.ui.DIcon
import dev.local.artistgallery.ui.DIconButton
import dev.local.artistgallery.ui.DIconImage
import dev.local.artistgallery.ui.GalleryDialog
import dev.local.artistgallery.ui.GalleryEvent
import dev.local.artistgallery.ui.GalleryUiState
import dev.local.artistgallery.ui.AnimatedGifImage
import dev.local.artistgallery.ui.DGroup
import dev.local.artistgallery.ui.galleryDialogWindowInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

val ViewerZoomKey = SemanticsPropertyKey<Float>("ViewerZoom")
val ViewerPanKey = SemanticsPropertyKey<Offset>("ViewerPan")
val ViewerCardDragKey = SemanticsPropertyKey<Offset>("ViewerCardDrag")

@Composable
fun ImageViewerScreen(state: GalleryUiState, assetId: String, previewDecoder: PreviewDecoder?, onEvent: (GalleryEvent) -> Unit) {
    ImageViewerScreen(state, assetId, previewDecoder, onEvent, onExport = null)
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun ImageViewerScreen(state: GalleryUiState, assetId: String, previewDecoder: PreviewDecoder?, onEvent: (GalleryEvent) -> Unit,
                      onExport: ((String) -> Unit)? = null, onShare: ((MediaAssetSummary) -> Unit)? = null) {
    val items = state.mediaItems.filter { it.type != MediaType.VIDEO }
    val index = items.indexOfFirst { it.id == assetId }
    val item = items.getOrNull(index)
    if (item == null) {
        DGroup(Modifier.fillMaxSize().padding(24.dp)) {
            Text("这张图片当前不可用。", color = DColors.Muted, modifier = Modifier.padding(20.dp))
            DButton("返回", primary = true, onClick = { onEvent(GalleryEvent.CloseOverlay) })
        }
        return
    }
    val decoder = previewDecoder
    val density = LocalDensity.current
    val pager = rememberPagerState(initialPage = index) { items.size }
    val scope = rememberCoroutineScope()
    val latestItems by rememberUpdatedState(items)
    val latestAssetId by rememberUpdatedState(assetId)
    val latestOnEvent by rememberUpdatedState(onEvent)
    var zoomedPageId by remember(state.browseOptions.cardEffects) { mutableStateOf<String?>(null) }
    LaunchedEffect(index, state.browseOptions.cardEffects) {
        if (state.browseOptions.cardEffects || (!pager.isScrollInProgress && pager.currentPage != index)) pager.scrollToPage(index)
    }
    LaunchedEffect(pager, state.browseOptions.cardEffects) {
        if (!state.browseOptions.cardEffects) snapshotFlow { pager.settledPage to pager.isScrollInProgress }.collect { (page, scrolling) ->
            if (!scrolling) latestItems.getOrNull(page)?.let { selected ->
                if (selected.id != latestAssetId) latestOnEvent(GalleryEvent.OpenMedia(selected.id))
            }
        }
    }
    var showActions by remember(item.id) { mutableStateOf(false) }
    var showOriginal by remember(item.id) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(DColors.Background).padding(horizontal = 18.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            DIconButton(DIcon.Back, "返回") { onEvent(GalleryEvent.CloseOverlay) }
            Text("查看原图", color = DColors.Action, fontSize = 14.sp, modifier = Modifier.clickable { showOriginal = true })
            DIconButton(DIcon.More, "图片操作") { showActions = true }
        }
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("imagePager"),
            reverseLayout = false,
            pageSpacing = 0.dp,
            beyondViewportPageCount = if (state.browseOptions.cardEffects) 0 else 1,
            userScrollEnabled = !state.browseOptions.cardEffects && zoomedPageId != items.getOrNull(pager.currentPage)?.id,
            key = { items[it].id },
        ) { page ->
            val pageItem = items[page]
            var zoom by remember(pageItem.id, state.browseOptions.cardEffects) { mutableFloatStateOf(1f) }
            var pan by remember(pageItem.id, state.browseOptions.cardEffects) { mutableStateOf(Offset.Zero) }
            var drag by remember(pageItem.id, state.browseOptions.cardEffects) { mutableStateOf(Offset.Zero) }
            var decodeScale by remember(pageItem.id) { mutableIntStateOf(1) }
            val isCurrent = page == pager.currentPage
            val animatedDrag by androidx.compose.animation.core.animateOffsetAsState(
                drag, androidx.compose.animation.core.spring(dampingRatio = .72f, stiffness = 220f), label = "cardSpringBack",
            )
            LaunchedEffect(isCurrent, state.browseOptions.cardEffects) {
                if (!isCurrent) {
                    zoom = 1f
                    pan = Offset.Zero
                    drag = Offset.Zero
                    decodeScale = 1
                    if (zoomedPageId == pageItem.id) zoomedPageId = null
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().testTag("imagePage:${pageItem.id}").semantics {
                this[ViewerZoomKey] = zoom
                this[ViewerPanKey] = pan
                this[ViewerCardDragKey] = animatedDrag
            }, contentAlignment = Alignment.Center) {
                val maxWidthPx = with(density) { maxWidth.roundToPx() }
                val maxHeightPx = with(density) { maxHeight.roundToPx() }
                val bitmap by produceState<Bitmap?>(null, pageItem.storageKey, decoder, maxWidthPx, maxHeightPx, decodeScale) {
                    val decoded = withContext(Dispatchers.IO) { runCatching { decoder?.decodeBitmap(pageItem.storageKey, maxWidthPx * decodeScale, maxHeightPx * decodeScale)?.also { it.prepareToDraw() } }.getOrNull() }
                    if (decoded != null) value = decoded
                }
                val aspect = bitmap?.let { (it.width.toFloat() / it.height.coerceAtLeast(1)).coerceIn(.62f, 1.65f) } ?: .76f
                val cardWidth = minOf(maxWidth, maxHeight * aspect)
                val cardWidthPx = with(density) { cardWidth.toPx() }
                val cardHeightPx = cardWidthPx / aspect
                val imageAspect = bitmap?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: aspect
                val imagePadding = with(density) { 16.dp.toPx() }
                val imageWidth = minOf((cardWidthPx - imagePadding).coerceAtLeast(1f), (cardHeightPx - imagePadding).coerceAtLeast(1f) * imageAspect)
                val imageHeight = imageWidth / imageAspect
                LaunchedEffect(imageWidth, imageHeight, maxWidthPx, maxHeightPx) {
                    val maxPanX = ((imageWidth * zoom - maxWidthPx) / 2f).coerceAtLeast(0f)
                    val maxPanY = ((imageHeight * zoom - maxHeightPx) / 2f).coerceAtLeast(0f)
                    pan = Offset(pan.x.coerceIn(-maxPanX, maxPanX), pan.y.coerceIn(-maxPanY, maxPanY))
                }
                val transformState = rememberTransformableState { zoomChange, panChange, _ ->
                    zoom = (zoom * zoomChange).coerceIn(1f, 3f)
                    if (zoom > 1f) {
                        drag = Offset.Zero
                        decodeScale = maxOf(decodeScale, ceil(zoom).toInt())
                        val maxPanX = ((imageWidth * zoom - maxWidthPx) / 2f).coerceAtLeast(0f)
                        val maxPanY = ((imageHeight * zoom - maxHeightPx) / 2f).coerceAtLeast(0f)
                        pan = (pan + panChange).let { Offset(it.x.coerceIn(-maxPanX, maxPanX), it.y.coerceIn(-maxPanY, maxPanY)) }
                    } else {
                        pan = Offset.Zero
                        drag = if (state.browseOptions.cardEffects && zoomChange == 1f) (drag + panChange).let {
                            Offset(it.x.coerceIn(-cardWidthPx * .7f, cardWidthPx * .7f), it.y.coerceIn(-cardHeightPx * .7f, cardHeightPx * .7f))
                        } else Offset.Zero
                    }
                    if (isCurrent) zoomedPageId = if (zoom > 1f) pageItem.id else null
                }
                LaunchedEffect(transformState.isTransformInProgress) { if (!transformState.isTransformInProgress) drag = Offset.Zero }
                Box(Modifier.fillMaxSize().transformable(transformState, canPan = { zoom > 1f || state.browseOptions.cardEffects }, enabled = isCurrent && !pager.isScrollInProgress), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.width(cardWidth).aspectRatio(aspect)
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = pan.x
                                translationY = pan.y
                                rotationY = if (state.browseOptions.cardEffects) (animatedDrag.x / maxWidthPx.coerceAtLeast(1) * -14f).coerceIn(-14f, 14f) else 0f
                                rotationX = if (state.browseOptions.cardEffects) (animatedDrag.y / maxHeightPx.coerceAtLeast(1) * 14f).coerceIn(-14f, 14f) else 0f
                                cameraDistance = 18f * density.density
                                shadowElevation = if (state.browseOptions.cardEffects) 8.dp.toPx() else 0f
                            }
                            .clip(RoundedCornerShape(24.dp)).background(Color.White)
                            .border(1.dp, Color(0xFFDCE6F3), RoundedCornerShape(24.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (pageItem.type == MediaType.GIF && decoder != null && state.browseOptions.playGifs) {
                            AnimatedGifImage(pageItem.storageKey, decoder, Modifier.fillMaxSize().padding(8.dp), isVisible = isCurrent, targetWidthPx = maxWidthPx * decodeScale, targetHeightPx = maxHeightPx * decodeScale)
                        } else if (bitmap != null) {
                            Image(bitmap!!.asImageBitmap(), contentDescription = pageItem.name, modifier = Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
                        } else {
                            DIconImage(DIcon.Image, Color(0xFF969EAD))
                        }
                        if (state.browseOptions.cardEffects) Canvas(Modifier.fillMaxSize()) {
                            val x = (size.width * (.5f + animatedDrag.x / maxWidthPx.coerceAtLeast(1))).coerceIn(0f, size.width)
                            val y = (size.height * (.5f + animatedDrag.y / maxHeightPx.coerceAtLeast(1))).coerceIn(0f, size.height)
                            drawRect(Brush.linearGradient(listOf(Color.Transparent, DColors.Blue.copy(alpha = .11f), Color.White.copy(alpha = .18f), Color.Transparent), start = androidx.compose.ui.geometry.Offset(x - size.width * .45f, y - size.height * .3f), end = androidx.compose.ui.geometry.Offset(x + size.width * .45f, y + size.height * .3f)))
                        }
                    }
                }
            }
        }
        Text(item.name, Modifier.fillMaxWidth().padding(top = 14.dp), textAlign = TextAlign.Center, fontSize = 15.sp, minLines = if (state.browseOptions.cardEffects) 1 else 2, maxLines = 2)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            DIconButton(DIcon.Back, "上一张", enabled = index > 0 && !pager.isScrollInProgress) {
                if (state.browseOptions.cardEffects) onEvent(GalleryEvent.StepViewer(-1))
                else scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) }
            }
            Text("${index + 1} / ${items.size}", color = DColors.Muted, fontSize = 13.sp)
            DIconButton(DIcon.Next, "下一张", enabled = index < items.lastIndex && !pager.isScrollInProgress) {
                if (state.browseOptions.cardEffects) onEvent(GalleryEvent.StepViewer(1))
                else scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(items.lastIndex)) }
            }
        }
        if (state.browseOptions.cardEffects) Text("按住画作拖动，松手回正", Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), textAlign = TextAlign.Center, color = DColors.Muted, fontSize = 12.sp)
    }

    if (showActions) ViewerActionsDialog(
        onClose = { showActions = false },
        onAdd = { showActions = false; onEvent(GalleryEvent.AddViewerMediaToCollection) },
        onExport = {
            showActions = false
            if (onExport != null) onExport((items.getOrNull(pager.currentPage) ?: item).id)
            else onEvent(GalleryEvent.OpenDialog(GalleryDialog.Unavailable("导出这张图片", 5)))
        },
        onShare = {
            if (!pager.isScrollInProgress) items.getOrNull(pager.settledPage)?.let { selected -> onShare?.invoke(selected) }
            showActions = false
        },
        shareEnabled = onShare != null && !pager.isScrollInProgress,
    )
    if (showOriginal && decoder != null) OriginalImageDialog(item, decoder) { showOriginal = false }
}

@Composable
private fun ViewerActionsDialog(onClose: () -> Unit, onAdd: () -> Unit, onExport: () -> Unit, onShare: () -> Unit, shareEnabled: Boolean) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(decorFitsSystemWindows = false)) {
        val dialogInsets = galleryDialogWindowInsets()
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .22f)).windowInsetsPadding(dialogInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)), contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(DColors.Panel).windowInsetsPadding(dialogInsets.only(WindowInsetsSides.Bottom)).padding(horizontal = 20.dp, vertical = 14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("图片操作", fontSize = 20.sp, lineHeight = 29.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    DIconButton(DIcon.Close, "关闭") { onClose() }
                }
                Spacer(Modifier.height(8.dp))
                ViewerActionRow("加入其他作品集", DIcon.Plus, onAdd)
                ViewerActionRow("导出这张图片", DIcon.Export, onExport)
                ViewerActionRow("分享这张图片", DIcon.Export, onShare, enabled = shareEnabled)
            }
        }
    }
}

@Composable
private fun ViewerActionRow(label: String, icon: DIcon, onClick: () -> Unit, enabled: Boolean = true) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable(enabled = enabled, onClick = onClick), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        DIconImage(icon, if (enabled) DColors.Ink else DColors.Muted)
        Text(label, Modifier.weight(1f), color = if (enabled) DColors.Ink else DColors.Muted, fontSize = 15.sp)
        DIconImage(DIcon.Next, DColors.Muted, 18.dp)
    }
}

@Composable
private fun OriginalImageDialog(item: MediaAssetSummary, decoder: PreviewDecoder, onClose: () -> Unit) {
    var zoom by remember(item.id) { mutableFloatStateOf(1f) }
    var pan by remember(item.id) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var bitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    val density = LocalDensity.current
    Dialog(onDismissRequest = onClose, properties = DialogProperties(decorFitsSystemWindows = false)) {
        val dialogInsets = galleryDialogWindowInsets()
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .22f)).windowInsetsPadding(dialogInsets).padding(16.dp), contentAlignment = Alignment.Center) {
            val viewportWidth = with(density) { maxWidth.roundToPx() }
            val viewportHeight = with(density) { (maxHeight - 190.dp).coerceAtLeast(120.dp).roundToPx() }
            val decodeScale = maxOf(2, ceil(zoom).toInt())
            LaunchedEffect(item.storageKey, viewportWidth, viewportHeight, decodeScale) {
                val decoded = withContext(Dispatchers.IO) { runCatching { decoder.decodeBitmap(item.storageKey, viewportWidth * decodeScale, viewportHeight * decodeScale) }.getOrNull() }
                if (decoded != null) bitmap = decoded
            }
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).clip(RoundedCornerShape(28.dp)).background(DColors.Panel).padding(horizontal = 20.dp, vertical = 14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("无特效原图", fontSize = 20.sp, lineHeight = 29.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    DIconButton(DIcon.Close, "关闭") { onClose() }
                }
                Box(Modifier.weight(1f, fill = false).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFFEFF1F5)).clipToBounds(), contentAlignment = Alignment.Center) {
                    if (bitmap != null) Image(
                        bitmap!!.asImageBitmap(), contentDescription = item.name, modifier = Modifier.fillMaxSize()
                            .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = pan.x, translationY = pan.y)
                            .pointerInput(item.id) {
                                detectTransformGestures { _, panChange, zoomChange, _ ->
                                    zoom = (zoom * zoomChange).coerceIn(1f, 3f)
                                    pan += panChange
                                }
                            }, contentScale = ContentScale.Fit,
                    ) else DIconImage(DIcon.Image, Color(0xFF969EAD), 36.dp)
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("缩放", fontSize = 13.sp)
                    Slider(zoom, { zoom = it; if (it == 1f) pan = androidx.compose.ui.geometry.Offset.Zero }, Modifier.weight(1f).padding(horizontal = 8.dp), valueRange = 1f..3f)
                    Text("${(zoom * 100).toInt()}%", fontSize = 13.sp, color = DColors.Muted)
                }
                Text("${item.name} · 放大后可拖动查看细节", Modifier.fillMaxWidth().padding(top = 2.dp), color = DColors.Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }
    }
}
