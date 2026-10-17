package dev.local.artistgallery.ui

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import dev.local.artistgallery.data.preview.PreviewDecoder
import dev.local.artistgallery.data.preview.PreviewRequest
import dev.local.artistgallery.data.preview.startIfAnimated
import dev.local.artistgallery.data.preview.stopIfAnimated
import dev.local.artistgallery.model.MediaType
import kotlinx.coroutines.CancellationException

@Composable
fun MediaThumbnail(storageKey: String, type: MediaType, previewDecoder: PreviewDecoder?, modifier: Modifier = Modifier, cornerRadius: Dp = 18.dp) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var image by remember(storageKey, type, previewDecoder, size) {
        mutableStateOf<Bitmap?>(if (size.width > 0 && size.height > 0) {
            previewDecoder?.cachedThumbnail(storageKey, size.width, size.height, type)
        } else null)
    }
    LaunchedEffect(storageKey, type, previewDecoder, size) {
        if (size.width > 0 && size.height > 0) {
            image = try {
                previewDecoder?.decodeThumbnail(storageKey, size.width, size.height, type)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
    }
    Box(modifier.onSizeChanged { size = it }.clip(RoundedCornerShape(cornerRadius)).background(Color(0xFFEFF1F5)), contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else DIconImage(if (type == MediaType.VIDEO) DIcon.Play else DIcon.Image, Color(0xFF969EAD))
        if (type == MediaType.VIDEO && bitmap != null) {
            Box(Modifier.size(40.dp).background(Color.Black.copy(alpha = .5f), CircleShape), contentAlignment = Alignment.Center) {
                DIconImage(DIcon.Play, Color.White)
            }
        }
    }
}

@Composable
fun AnimatedGifImage(
    storageKey: String,
    previewDecoder: PreviewDecoder,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true,
    targetWidthPx: Int,
    targetHeightPx: Int,
) {
    val image = rememberPreviewImage(storageKey, previewDecoder, targetWidthPx, targetHeightPx, animated = true, isCurrent = isVisible)
    val drawable = (image as? PreviewImage.Animated)?.drawable
    if (drawable != null) AnimatedDrawableImage(drawable, modifier, isVisible)
    else Box(modifier.background(Color(0xFFEFF1F5)))
}

internal sealed interface PreviewImage {
    val width: Int
    val height: Int
    class Still(val bitmap: Bitmap) : PreviewImage {
        override val width get() = bitmap.width
        override val height get() = bitmap.height
    }
    class Animated(val drawable: Drawable) : PreviewImage {
        override val width get() = drawable.intrinsicWidth
        override val height get() = drawable.intrinsicHeight
    }
}

@Composable
internal fun rememberPreviewImage(storageKey: String, decoder: PreviewDecoder?, width: Int, height: Int,
                                  animated: Boolean = false, isCurrent: Boolean = true): PreviewImage? {
    val scope = rememberCoroutineScope()
    val image = remember(storageKey, decoder, animated) { mutableStateOf<PreviewImage?>(null) }
    val request = remember(storageKey, decoder, animated) {
        PreviewRequest<IntSize, PreviewImage>(scope, { size, priority, started ->
            if (animated) decoder?.decodeAnimated(storageKey, size.width, size.height, priority, started)?.let { PreviewImage.Animated(it) }
            else decoder?.decodeBitmap(storageKey, size.width, size.height, priority, started)?.let { PreviewImage.Still(it) }
        }, { image.value = it })
    }
    DisposableEffect(request) { onDispose { request.cancel() } }
    SideEffect { if (decoder != null && width > 0 && height > 0) request.update(IntSize(width, height), isCurrent) }
    return image.value
}

@Composable
internal fun AnimatedDrawableImage(drawable: Drawable, modifier: Modifier = Modifier, isVisible: Boolean = true) {
    val context = LocalContext.current
    DisposableEffect(drawable, isVisible) {
        if (isVisible) drawable.startIfAnimated() else drawable.stopIfAnimated()
        onDispose { drawable.stopIfAnimated() }
    }
    AndroidView(
        modifier = modifier,
        factory = { ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true } },
        update = { view ->
            if (view.drawable !== drawable) view.setImageDrawable(drawable)
            if (isVisible) drawable.startIfAnimated() else drawable.stopIfAnimated()
        },
        onRelease = { view -> view.drawable?.stopIfAnimated(); view.setImageDrawable(null) },
    )
}
