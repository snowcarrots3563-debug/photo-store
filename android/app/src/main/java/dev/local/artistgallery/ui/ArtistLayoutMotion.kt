package dev.local.artistgallery.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.local.artistgallery.data.ArtistImageLayout
import dev.local.artistgallery.data.preview.PreviewDecoder
import dev.local.artistgallery.model.MediaAssetSummary
import dev.local.artistgallery.model.MediaType
import kotlinx.coroutines.flow.first
import kotlin.math.max
import kotlin.math.roundToInt

internal class ArtistLayoutMotionEntry(
    val layout: ArtistImageLayout,
    var bounds: Rect = Rect.Zero,
    var image: ImageBitmap? = null,
    val radius: Float,
    val video: Boolean,
)

internal data class ArtistLayoutMotionFrame(val bounds: Rect, val image: ImageBitmap, val radius: Float, val video: Boolean, val alpha: Float = 1f)

internal class ArtistLayoutMotionSession(val layout: ArtistImageLayout, val sources: Map<String, ArtistLayoutMotionFrame>) {
    val progress = Animatable(0f)
    val targets = mutableStateMapOf<String, ArtistLayoutMotionEntry>()
    var bound by mutableStateOf(false)
}

/** Only visible presentation pixels survive the layout key; no coordinates, requests or business trees. */
internal class ArtistLayoutMotionState {
    private val entries = mutableMapOf<String, ArtistLayoutMotionEntry>()
    var session by mutableStateOf<ArtistLayoutMotionSession?>(null)
        private set
    var viewport = Rect.Zero

    fun register(id: String, entry: ArtistLayoutMotionEntry) { entries[id] = entry }
    fun remove(id: String, entry: ArtistLayoutMotionEntry) { if (entries[id] === entry) entries.remove(id) }
    fun position(id: String, entry: ArtistLayoutMotionEntry) {
        session?.takeIf { it.layout == entry.layout }?.targets?.set(id,
            ArtistLayoutMotionEntry(entry.layout, entry.bounds, radius = entry.radius, video = entry.video))
    }
    fun bind(layout: ArtistImageLayout) { session?.takeIf { it.layout == layout }?.bound = true }
    fun cancel() { session = null }

    fun begin(layout: ArtistImageLayout) {
        val sources = entries.mapNotNull { (id, entry) ->
            entry.image?.takeIf { entry.bounds.overlaps(viewport) }?.let {
                id to ArtistLayoutMotionFrame(entry.bounds, it, entry.radius, entry.video)
            }
        }.toMap().toMutableMap()
        // A second click continues the image currently on screen, rather than its hidden endpoint.
        sources.putAll(drawnFrames().filterValues { it.alpha > 0f && it.bounds.overlaps(viewport) })
        session = sources.takeIf { it.isNotEmpty() }?.let { ArtistLayoutMotionSession(layout, it) }
    }

    fun drawnFrames(): Map<String, ArtistLayoutMotionFrame> {
        val current = session ?: return emptyMap()
        val fraction = current.progress.value
        return current.sources.mapValues { (id, from) ->
            val to = current.targets[id]
            if (to == null) from.copy(alpha = from.alpha * (1f - fraction))
            else from.copy(bounds = lerp(from.bounds, to.bounds, fraction),
                radius = from.radius + (to.radius - from.radius) * fraction,
                alpha = from.alpha + (1f - from.alpha) * fraction)
        }
    }

    fun imageAlpha(id: String): Float {
        val current = session ?: return 1f
        return if (id in current.sources) 0f else 1f
    }

    fun contentAlpha(layout: ArtistImageLayout): Float = session?.let { if (it.layout == layout) it.progress.value else 0f } ?: 1f
}

@Composable
internal fun ArtistLayoutThumbnail(media: MediaAssetSummary, decoder: PreviewDecoder?, motion: ArtistLayoutMotionState,
    layout: ArtistImageLayout, modifier: Modifier, cornerRadius: Dp, revealDetail: Boolean, initialSizePx: IntSize) {
    val radius = with(LocalDensity.current) { cornerRadius.toPx() }
    val entry = remember(motion, media.id, layout, radius) { ArtistLayoutMotionEntry(layout, radius = radius, video = media.type == MediaType.VIDEO) }
    val initialImage = remember(entry) { motion.session?.sources?.get(media.id)?.image }
    val covered = motion.session?.sources?.containsKey(media.id) == true
    DisposableEffect(motion, entry) {
        motion.register(media.id, entry)
        onDispose { motion.remove(media.id, entry) }
    }
    Box(modifier.onGloballyPositioned {
        // Keep the full rectangle even when the LazyColumn clips a partial first/last cell.
        entry.bounds = Rect(it.positionInRoot(), androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat()))
        motion.position(media.id, entry)
    }) {
        MediaThumbnail(media.storageKey, media.type, decoder,
            Modifier.fillMaxSize().graphicsLayer { alpha = motion.imageAlpha(media.id) },
            cornerRadius, focusOnLoad = true, revealDetail = revealDetail && !covered, initialSizePx = initialSizePx,
            initialImage = initialImage, onPresentedImage = { entry.image = it })
    }
}

@Composable
internal fun ArtistLayoutMotionOverlay(motion: ArtistLayoutMotionState, modifier: Modifier = Modifier) {
    val current = motion.session ?: return
    var origin by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(current) {
        try {
            snapshotFlow { current.bound && current.targets.isNotEmpty() }.first { it }
            withFrameNanos { } // Use the real destination placements from this layout pass.
            current.progress.animateTo(1f, tween(460, easing = FastOutSlowInEasing))
        } finally { if (motion.session === current) motion.cancel() }
    }
    Canvas(modifier.onGloballyPositioned { origin = it.positionInRoot() }.testTag("artistLayoutOverlay")
        .clearAndSetSemantics { motionFraction = current.progress.value }) {
        translate(-origin.x, -origin.y) {
            val viewport = motion.viewport
            clipRect(viewport.left, viewport.top, viewport.right, viewport.bottom) {
                motion.drawnFrames().values.forEach { frame ->
                    val rect = frame.bounds
                    clipPath(Path().apply { addRoundRect(RoundRect(rect, CornerRadius(frame.radius))) }) {
                        val canvas = drawContext.canvas
                        if (frame.alpha < 1f) canvas.saveLayer(rect, Paint().apply { alpha = frame.alpha })
                        drawLayoutImage(frame.image, rect)
                        if (frame.video) {
                            drawCircle(Color.Black.copy(alpha = .5f), 20.dp.toPx(), rect.center)
                            val half = 7.dp.toPx()
                            drawPath(Path().apply {
                                moveTo(rect.center.x - half * .6f, rect.center.y - half)
                                lineTo(rect.center.x + half, rect.center.y)
                                lineTo(rect.center.x - half * .6f, rect.center.y + half); close()
                            }, Color.White)
                        }
                        if (frame.alpha < 1f) canvas.restore()
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawLayoutImage(image: ImageBitmap, rect: Rect) {
    val scale = max(rect.width / image.width, rect.height / image.height)
    val source = IntSize((rect.width / scale).roundToInt().coerceIn(1, image.width), (rect.height / scale).roundToInt().coerceIn(1, image.height))
    drawRect(Color(0xFFEFF1F5), rect.topLeft, rect.size)
    drawImage(image, IntOffset((image.width - source.width) / 2, (image.height - source.height) / 2), source,
        IntOffset(rect.left.roundToInt(), rect.top.roundToInt()), IntSize(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1)),
        filterQuality = FilterQuality.High)
}
