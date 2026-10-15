package dev.local.artistgallery.ui

import android.graphics.Bitmap
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import dev.local.artistgallery.data.preview.startIfAnimated
import dev.local.artistgallery.data.preview.stopIfAnimated
import dev.local.artistgallery.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

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
    val context = LocalContext.current
    val drawable = produceState<android.graphics.drawable.Drawable?>(null, storageKey, previewDecoder, targetWidthPx, targetHeightPx) {
        value = withContext(Dispatchers.IO) {
            runCatching { previewDecoder.decodeAnimated(storageKey, targetWidthPx, targetHeightPx) }.getOrNull()
        }
    }.value
    DisposableEffect(drawable, isVisible) {
        if (isVisible) drawable?.startIfAnimated() else drawable?.stopIfAnimated()
        onDispose { drawable?.stopIfAnimated() }
    }
    if (drawable != null) {
        AndroidView(
            modifier = modifier,
            factory = { ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true } },
            update = { view ->
                if (view.drawable !== drawable) view.setImageDrawable(drawable)
                if (isVisible) drawable.startIfAnimated() else drawable.stopIfAnimated()
            },
            onRelease = { drawable.stopIfAnimated() },
        )
    } else {
        Box(modifier.background(Color(0xFFEFF1F5)))
    }
}
