package dev.local.artistgallery.data.preview

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.os.Build
import android.os.SystemClock
import android.util.LruCache
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class PreviewDecoder(
    private val contentResolver: ContentResolver,
    private val assetReader: AssetReader,
) {
    private data class ThumbnailKey(val storageKey: String, val width: Int, val height: Int)
    private val thumbnailCache = object : LruCache<ThumbnailKey, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 16).coerceIn(4L * 1024 * 1024, 32L * 1024 * 1024).toInt(),
    ) {
        override fun sizeOf(key: ThumbnailKey, value: Bitmap): Int = value.allocationByteCount
    }
    private val thumbnailDecodes = Semaphore(2)

    fun cachedThumbnail(storageKey: String, targetWidthPx: Int, targetHeightPx: Int): Bitmap? =
        thumbnailCache.get(ThumbnailKey(storageKey, targetWidthPx, targetHeightPx))

    suspend fun decodeThumbnail(storageKey: String, targetWidthPx: Int, targetHeightPx: Int): Bitmap? {
        val key = ThumbnailKey(storageKey, targetWidthPx, targetHeightPx)
        thumbnailCache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            thumbnailDecodes.withPermit {
                currentCoroutineContext().ensureActive()
                thumbnailCache.get(key) ?: readThumbnail(storageKey, targetWidthPx, targetHeightPx)?.also {
                    it.prepareToDraw()
                    // A native decode can finish after its row leaves the screen; retain it for a return scroll.
                    thumbnailCache.put(key, it)
                }
            }
        }
    }

    suspend fun decodeBitmap(storageKey: String, targetWidthPx: Int, targetHeightPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        readBitmap(storageKey, targetWidthPx, targetHeightPx)
    }

    private fun readThumbnail(storageKey: String, targetWidthPx: Int, targetHeightPx: Int): Bitmap? {
        val uri = assetReader.contentUri(storageKey)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || bounds.outMimeType in listOf("image/jpeg", "image/png", "image/webp")) {
            return decodeLegacy(uri.toString(), targetWidthPx, targetHeightPx, crop = true)
        }
        // ImageDecoder.setCrop crops only its output; keep the full temporary image fit-sized.
        return readBitmap(storageKey, targetWidthPx, targetHeightPx)?.let { cropThumbnail(it, targetWidthPx, targetHeightPx) }
    }

    private fun readBitmap(storageKey: String, targetWidthPx: Int, targetHeightPx: Int): Bitmap? {
        val uri = assetReader.contentUri(storageKey)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val size = fittedSize(info.size.width, info.size.height, targetWidthPx, targetHeightPx)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSize(size.first, size.second)
            }
        } else {
            decodeLegacy(uri.toString(), targetWidthPx, targetHeightPx)
        }
    }

    suspend fun decodeAnimated(storageKey: String, targetWidthPx: Int, targetHeightPx: Int): Drawable = withContext(Dispatchers.IO) {
        val uri = assetReader.contentUri(storageKey)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            val drawable = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                val size = fittedSize(info.size.width, info.size.height, targetWidthPx, targetHeightPx)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSize(size.first, size.second)
            }
            if (drawable !is AnimatedImageDrawable) throw IOException("无法读取 GIF 动画")
            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            drawable
        } else {
            val movie = contentResolver.openInputStream(uri)?.use { Movie.decodeStream(it) }
                ?: throw FileNotFoundException("无法读取 GIF 动画")
            MovieDrawable(movie)
        }
    }

    @Suppress("DEPRECATION")
    private fun decodeLegacy(uri: String, targetWidthPx: Int, targetHeightPx: Int, crop: Boolean = false): Bitmap? {
        val parsed = android.net.Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val orientation = contentResolver.openInputStream(parsed)?.use { input ->
            ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val rotated = orientation == ExifInterface.ORIENTATION_TRANSPOSE || orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE || orientation == ExifInterface.ORIENTATION_ROTATE_270
        val regionDecode = crop && bounds.outMimeType in listOf("image/jpeg", "image/png", "image/webp")
        val region = Rect(0, 0, bounds.outWidth, bounds.outHeight)
        val requestedWidth = (if (crop && rotated) targetHeightPx else targetWidthPx).coerceAtLeast(1)
        val requestedHeight = (if (crop && rotated) targetWidthPx else targetHeightPx).coerceAtLeast(1)
        if (regionDecode) {
            val ratio = requestedWidth.toDouble() / requestedHeight
            val width = min(bounds.outWidth, (bounds.outHeight * ratio).toInt().coerceAtLeast(1))
            val height = min(bounds.outHeight, (bounds.outWidth / ratio).toInt().coerceAtLeast(1))
            region.set((bounds.outWidth - width) / 2, (bounds.outHeight - height) / 2,
                (bounds.outWidth - width) / 2 + width, (bounds.outHeight - height) / 2 + height)
        }
        // Region decoding keeps long JPEG/PNG/WebP thumbnails bounded before allocating pixels.
        // Other formats (including static GIF frames) retain the bounded fit sampling path.
        val size = fittedSize(region.width(), region.height(), requestedWidth, requestedHeight)
        val sample = max(1, min(region.width() / size.first, region.height() / size.second))
        val options = BitmapFactory.Options().apply { inSampleSize = max(1, Integer.highestOneBit(sample)) }
        val decoded = contentResolver.openInputStream(parsed)?.use { input ->
            if (regionDecode) {
                val decoder = BitmapRegionDecoder.newInstance(input, false) ?: return@use null
                try { decoder.decodeRegion(region, options) } finally { decoder.recycle() }
            } else BitmapFactory.decodeStream(input, null, options)
        } ?: return null
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        val oriented = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
            if (it !== decoded) decoded.recycle()
        }
        if (!crop) return oriented
        return cropThumbnail(oriented, targetWidthPx, targetHeightPx)
    }

    private fun cropThumbnail(bitmap: Bitmap, targetWidthPx: Int, targetHeightPx: Int): Bitmap {
        val scaledSize = fittedSize(bitmap.width, bitmap.height, targetWidthPx, targetHeightPx, crop = true)
        val scaled = Bitmap.createScaledBitmap(bitmap, scaledSize.first, scaledSize.second, true)
        if (scaled !== bitmap) bitmap.recycle()
        val width = min(targetWidthPx.coerceAtLeast(1), scaled.width)
        val height = min(targetHeightPx.coerceAtLeast(1), scaled.height)
        return Bitmap.createBitmap(scaled, (scaled.width - width) / 2, (scaled.height - height) / 2, width, height).also {
            if (it !== scaled) scaled.recycle()
        }
    }

    private fun fittedSize(width: Int, height: Int, targetWidthPx: Int, targetHeightPx: Int, crop: Boolean = false): Pair<Int, Int> {
        val targetWidth = targetWidthPx.coerceAtLeast(1)
        val targetHeight = targetHeightPx.coerceAtLeast(1)
        val widthScale = targetWidth.toFloat() / width.coerceAtLeast(1)
        val heightScale = targetHeight.toFloat() / height.coerceAtLeast(1)
        val scale = (if (crop) max(widthScale, heightScale) else min(widthScale, heightScale)).coerceAtMost(1f)
        if (crop) return max(1, ceil(width * scale).toInt()) to max(1, ceil(height * scale).toInt())
        return max(1, (width * scale).toInt()) to max(1, (height * scale).toInt())
    }
}

private class MovieDrawable(private val movie: Movie) : Drawable(), Runnable {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var startedAt = 0L
    private var running = false

    override fun draw(canvas: Canvas) {
        val bounds: Rect = bounds
        if (bounds.isEmpty) return
        val duration = movie.duration().coerceAtLeast(1)
        if (running) movie.setTime(((SystemClock.uptimeMillis() - startedAt) % duration).toInt())
        val scale = min(bounds.width().toFloat() / movie.width().coerceAtLeast(1), bounds.height().toFloat() / movie.height().coerceAtLeast(1))
        val x = bounds.left + (bounds.width() - movie.width() * scale) / 2f
        val y = bounds.top + (bounds.height() - movie.height() * scale) / 2f
        val save = canvas.save()
        canvas.translate(x, y)
        canvas.scale(scale, scale)
        movie.draw(canvas, 0f, 0f, paint)
        canvas.restoreToCount(save)
        if (running) scheduleSelf(this, SystemClock.uptimeMillis() + FRAME_MILLIS)
    }

    override fun run() = invalidateSelf()
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    fun start() {
        if (running) return
        running = true
        startedAt = SystemClock.uptimeMillis()
        scheduleSelf(this, startedAt)
    }

    fun stop() {
        running = false
        unscheduleSelf(this)
    }

    companion object { private const val FRAME_MILLIS = 16L }
}

fun Drawable.startIfAnimated() {
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && this is AnimatedImageDrawable -> start()
        this is MovieDrawable -> start()
    }
}

fun Drawable.stopIfAnimated() {
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && this is AnimatedImageDrawable -> stop()
        this is MovieDrawable -> stop()
    }
}
