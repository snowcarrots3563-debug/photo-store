package dev.local.artistgallery.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
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
import dev.local.artistgallery.ui.AnimatedDrawableImage
import dev.local.artistgallery.ui.PreviewImage
import dev.local.artistgallery.ui.rememberPreviewImage
import dev.local.artistgallery.ui.DGroup
import dev.local.artistgallery.ui.DAnimatedDialog
import dev.local.artistgallery.ui.DPopupCard
import dev.local.artistgallery.ui.DPopupHeader
import dev.local.artistgallery.ui.DPopupOption
import dev.local.artistgallery.ui.LocalDDialogActive
import dev.local.artistgallery.ui.galleryDialogWindowInsets
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
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
    val items = remember(state.mediaItems) { state.mediaItems.filter { it.type != MediaType.VIDEO } }
    val index = remember(items, assetId) { items.indexOfFirst { it.id == assetId } }
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
            var pointerHeld by remember(pageItem.id, state.browseOptions.cardEffects) { mutableStateOf(false) }
            var returnJob by remember(pageItem.id, state.browseOptions.cardEffects) { mutableStateOf<Job?>(null) }
            var decodeScale by remember(pageItem.id) { mutableIntStateOf(1) }
            val isCurrent = page == pager.currentPage
            DisposableEffect(pageItem.id, state.browseOptions.cardEffects) { onDispose { returnJob?.cancel() } }
            LaunchedEffect(isCurrent, state.browseOptions.cardEffects) {
                if (!isCurrent) {
                    returnJob?.cancel()
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
                this[ViewerCardDragKey] = if (state.browseOptions.cardEffects) drag else Offset.Zero
            }, contentAlignment = Alignment.Center) {
                val maxWidthPx = with(density) { maxWidth.roundToPx() }
                val maxHeightPx = with(density) { maxHeight.roundToPx() }
                val image = rememberPreviewImage(pageItem.storageKey, decoder, maxWidthPx * decodeScale, maxHeightPx * decodeScale,
                    animated = pageItem.type == MediaType.GIF && state.browseOptions.playGifs, isCurrent = isCurrent)
                var gaussian by remember(pageItem.id, decoder) { mutableStateOf(decoder?.cachedGaussianPreview(pageItem.storageKey, pageItem.type)) }
                val clarity = remember(pageItem.id, decoder) { Animatable(0f) }
                LaunchedEffect(image != null) {
                    if (image != null && gaussian != null) {
                        clarity.animateTo(1f, tween(350))
                        gaussian = null
                    }
                }
                val aspect = image?.let { (it.width.toFloat() / it.height.coerceAtLeast(1)).coerceIn(.62f, 1.65f) } ?: .76f
                val cardWidth = minOf(maxWidth, maxHeight * aspect)
                val cardWidthPx = with(density) { cardWidth.toPx() }
                val cardHeightPx = cardWidthPx / aspect
                val imageAspect = image?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: aspect
                val imagePadding = with(density) { 16.dp.toPx() }
                val imageWidth = minOf((cardWidthPx - imagePadding).coerceAtLeast(1f), (cardHeightPx - imagePadding).coerceAtLeast(1f) * imageAspect)
                val imageHeight = imageWidth / imageAspect
                LaunchedEffect(imageWidth, imageHeight, maxWidthPx, maxHeightPx) {
                    val maxPanX = ((imageWidth * zoom - maxWidthPx) / 2f).coerceAtLeast(0f)
                    val maxPanY = ((imageHeight * zoom - maxHeightPx) / 2f).coerceAtLeast(0f)
                    pan = Offset(pan.x.coerceIn(-maxPanX, maxPanX), pan.y.coerceIn(-maxPanY, maxPanY))
                }
                val transformState = rememberTransformableState { zoomChange, panChange, _ ->
                    returnJob?.cancel()
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
                LaunchedEffect(pointerHeld, transformState.isTransformInProgress) {
                    if (!pointerHeld && !transformState.isTransformInProgress && drag != Offset.Zero) {
                        returnJob?.cancel()
                        returnJob = scope.launch {
                            animate(initialValue = drag, targetValue = Offset.Zero, typeConverter = Offset.VectorConverter,
                                animationSpec = spring(dampingRatio = .8f, stiffness = 220f)) { value, _ -> drag = value }
                        }
                    }
                }
                Box(Modifier.fillMaxSize().pointerInput(pageItem.id, state.browseOptions.cardEffects) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        returnJob?.cancel()
                        pointerHeld = true
                        try {
                            while (awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }) { }
                        } finally { pointerHeld = false }
                    }
                }.transformable(transformState, canPan = { zoom > 1f || state.browseOptions.cardEffects }, enabled = isCurrent && !pager.isScrollInProgress), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.width(cardWidth).aspectRatio(aspect)
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = pan.x
                                translationY = pan.y
                                rotationY = if (state.browseOptions.cardEffects) (drag.x / maxWidthPx.coerceAtLeast(1) * -14f).coerceIn(-14f, 14f) else 0f
                                rotationX = if (state.browseOptions.cardEffects) (drag.y / maxHeightPx.coerceAtLeast(1) * 14f).coerceIn(-14f, 14f) else 0f
                                cameraDistance = 18f * density.density
                                shadowElevation = if (state.browseOptions.cardEffects) 8.dp.toPx() else 0f
                            }
                            .clip(RoundedCornerShape(24.dp)).background(Color.White)
                            .border(1.dp, Color(0xFFDCE6F3), RoundedCornerShape(24.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        gaussian?.let { preview -> Image(preview.asImageBitmap(), null,
                            Modifier.fillMaxSize().padding(8.dp).testTag("viewerPreview:${pageItem.id}")
                                .graphicsLayer { alpha = ((1f - clarity.value) / .2f).coerceIn(0f, 1f) },
                            contentScale = ContentScale.Crop, filterQuality = FilterQuality.High) }
                        Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (gaussian != null) clarity.value else 1f }) {
                        when (image) {
                            is PreviewImage.Animated -> AnimatedDrawableImage(image.drawable, Modifier.fillMaxSize().padding(8.dp), isVisible = isCurrent)
                            is PreviewImage.Still -> Image(image.bitmap.asImageBitmap(), contentDescription = pageItem.name, modifier = Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
                            null -> if (gaussian != null) Unit else if (pageItem.type == MediaType.GIF && decoder != null && state.browseOptions.playGifs) {
                                Box(Modifier.fillMaxSize().padding(8.dp).background(Color(0xFFEFF1F5)))
                            } else DIconImage(DIcon.Image, Color(0xFF969EAD))
                        }
                        }
                        if (state.browseOptions.cardEffects) Canvas(Modifier.fillMaxSize()) {
                            val x = (size.width * (.5f + drag.x / maxWidthPx.coerceAtLeast(1))).coerceIn(0f, size.width)
                            val y = (size.height * (.5f + drag.y / maxHeightPx.coerceAtLeast(1))).coerceIn(0f, size.height)
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

    ViewerActionsDialog(visible = showActions,
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
    if (decoder != null) OriginalImageDialog(item.takeIf { showOriginal }, decoder) { showOriginal = false }
}

@Composable
private fun ViewerActionsDialog(visible: Boolean, onClose: () -> Unit, onAdd: () -> Unit, onExport: () -> Unit, onShare: () -> Unit, shareEnabled: Boolean) {
    DAnimatedDialog(Unit.takeIf { visible }, onDismissRequest = onClose) { _, panel, fraction ->
        val dialogInsets = galleryDialogWindowInsets()
        DPopupCard(panel, fraction, dialogInsets, onClose) { card ->
            Column(card.verticalScroll(rememberScrollState())) {
                DPopupHeader("图片操作", onClose)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DPopupOption("加入其他作品集", DIcon.SettingsFolder, onClick = onAdd)
                    DPopupOption("导出这张图片", DIcon.SettingsUpload, onClick = onExport)
                    DPopupOption("分享这张图片", DIcon.SettingsImage, enabled = shareEnabled, onClick = onShare)
                }
            }
        }
    }
}

@Composable
private fun OriginalImageDialog(item: MediaAssetSummary?, decoder: PreviewDecoder, onClose: () -> Unit) {
    DAnimatedDialog(item, onDismissRequest = onClose, contentKey = item?.id) { presentation, panel, fraction ->
        val active = LocalDDialogActive.current
        var zoom by remember(presentation.id) { mutableFloatStateOf(1f) }
        var pan by remember(presentation.id) { mutableStateOf(Offset.Zero) }
        var frame by remember(presentation.id) { mutableStateOf(IntSize.Zero) }
        var decodeScale by remember(presentation.id) { mutableIntStateOf(2) }
        var lastBitmap by remember(presentation.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
        val dialogInsets = galleryDialogWindowInsets()
        DPopupCard(panel, fraction, dialogInsets, onClose) { card ->
            val frameHeight = minOf(390.dp, maxHeight * .54f)
            // Removing this branch cancels the original request immediately; only pixels remain during exit.
            val image = if (active && frame.width > 0 && frame.height > 0) rememberPreviewImage(presentation.storageKey, decoder, frame.width * decodeScale, frame.height * decodeScale) else null
            val decoded = (image as? PreviewImage.Still)?.bitmap
            SideEffect { if (decoded != null) lastBitmap = decoded }
            val bitmap = decoded ?: lastBitmap
            LaunchedEffect(bitmap, frame) {
                pan = boundedOriginalPan(pan, zoom, bitmap?.width ?: 0, bitmap?.height ?: 0, frame)
            }
            Column(card) {
                DPopupHeader("无特效原图", onClose)
                Box(Modifier.weight(1f, fill = false).fillMaxWidth().height(frameHeight).testTag("originalImageFrame")
                    .onSizeChanged {
                        frame = it
                        pan = boundedOriginalPan(pan, zoom, bitmap?.width ?: 0, bitmap?.height ?: 0, it)
                    }.semantics { this[ViewerZoomKey] = zoom; this[ViewerPanKey] = pan }
                    .clip(RoundedCornerShape(22.dp)).background(Color(0xFFEFF1F5)).clipToBounds(), contentAlignment = Alignment.Center) {
                    if (bitmap != null) Image(
                        bitmap.asImageBitmap(), contentDescription = presentation.name, modifier = Modifier.fillMaxSize().testTag("originalImagePixels")
                            .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = pan.x, translationY = pan.y)
                            .pointerInput(presentation.id, active, bitmap.width, bitmap.height, frame) {
                                if (active) detectTransformGestures { _, panChange, zoomChange, _ ->
                                    zoom = (zoom * zoomChange).coerceIn(1f, 3f)
                                    decodeScale = maxOf(decodeScale, ceil(zoom).toInt())
                                    pan = boundedOriginalPan(pan + panChange, zoom, bitmap.width, bitmap.height, frame)
                                }
                            }, contentScale = ContentScale.Fit,
                    ) else DIconImage(DIcon.Image, Color(0xFF969EAD), 36.dp)
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("缩放", fontSize = 13.sp)
                    Slider(zoom, {
                        if (active) {
                            zoom = it
                            decodeScale = maxOf(decodeScale, ceil(it).toInt())
                            pan = boundedOriginalPan(pan, zoom, bitmap?.width ?: 0, bitmap?.height ?: 0, frame)
                        }
                    }, Modifier.weight(1f).padding(horizontal = 8.dp).testTag("originalZoomSlider"), enabled = active, valueRange = 1f..3f)
                    Text("${(zoom * 100).toInt()}%", fontSize = 13.sp, color = DColors.Muted)
                }
                Text("${presentation.name} · 放大后可拖动查看细节", Modifier.fillMaxWidth().padding(top = 2.dp), color = DColors.Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

internal fun boundedOriginalPan(pan: Offset, zoom: Float, width: Int, height: Int, frame: IntSize): Offset {
    if (zoom <= 1f || width <= 0 || height <= 0 || frame.width <= 0 || frame.height <= 0) return Offset.Zero
    val fit = minOf(frame.width.toFloat() / width, frame.height.toFloat() / height)
    val maxX = ((width * fit * zoom - frame.width) / 2f).coerceAtLeast(0f)
    val maxY = ((height * fit * zoom - frame.height) / 2f).coerceAtLeast(0f)
    return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
}
