package dev.local.artistgallery.data.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.artistgallery.data.importing.MediaFileStore
import dev.local.artistgallery.model.MediaType
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Own UUID fixtures only; no gallery database or existing media is accessed. */
@RunWith(AndroidJUnit4::class)
class GaussianPreviewTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val files = MediaFileStore(context)
    private val reader = AssetReader(context, files)
    private val decoder = PreviewDecoder(context.contentResolver, reader)
    private val owned = mutableSetOf<String>()
    private val staging = mutableSetOf<String>()

    @After fun cleanup() = runBlocking {
        val root = requireNotNull(context.getExternalFilesDir(null)).canonicalFile
        owned.forEach { key ->
            check(files.originalFile(key).canonicalFile == File(root, "originals/$key"))
            check(files.gaussianPreviewFile(key).canonicalFile == File(context.cacheDir, "gaussian-previews-v1/$key.png").canonicalFile)
            assertTrue(files.deleteOriginal(key))
        }
        staging.forEach { key -> assertTrue(files.deleteStaging(key)) }
    }

    @Test fun gaussianSmoothsAColorBoundaryWithoutRecyclingTheSharpSource() = runBlocking {
        val key = png()
        val preview = requireNotNull(decoder.decodeGaussianPreview(key))
        val sharp = requireNotNull(decoder.cachedThumbnail(key, 96, 96))
        assertEquals(96, preview.width)
        assertEquals(96, preview.height)
        assertTrue(Color.red(preview.getPixel(48, 48)) in 1..254)
        assertTrue(Color.blue(preview.getPixel(48, 48)) in 1..254)
        assertEquals(Color.BLUE, sharp.getPixel(48, 48))
        assertFalse(sharp.isRecycled)
        assertSame(preview, decoder.cachedGaussianPreview(key))
        assertSame(preview, decoder.decodeGaussianPreview(key))
    }

    @Test fun newDecoderLoadsThePersistedPreviewWithoutReadingTheOriginal() = runBlocking {
        val key = png()
        val first = requireNotNull(decoder.decodeGaussianPreview(key))
        val original = files.originalFile(key)
        val movedKey = UUID.randomUUID().toString().also { staging += it }
        val moved = files.stagingFile(movedKey)
        assertFalse(moved.exists())
        assertTrue(original.renameTo(moved))
        try {
            val next = PreviewDecoder(context.contentResolver, reader)
            val loaded = requireNotNull(next.decodeGaussianPreview(key))
            assertEquals(first.getPixel(48, 48), loaded.getPixel(48, 48))
            assertNull(next.cachedThumbnail(key, 96, 96))
        } finally {
            assertTrue(moved.renameTo(original))
        }
    }

    @Test fun corruptDiskEntryIsRegeneratedFromItsOwnOriginal() = runBlocking {
        val key = png()
        val preview = requireNotNull(decoder.decodeGaussianPreview(key))
        files.gaussianPreviewFile(key).writeText("not a PNG")
        val next = PreviewDecoder(context.contentResolver, reader)
        val regenerated = requireNotNull(next.decodeGaussianPreview(key))
        assertEquals(preview.getPixel(48, 48), regenerated.getPixel(48, 48))
        val dimensions = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(files.gaussianPreviewFile(key).absolutePath, dimensions)
        assertEquals(96, dimensions.outWidth)
        assertEquals(96, dimensions.outHeight)
    }

    @Test fun tinyRectangularSourceHasSquarePreviewThatCanBeReadFromDisk() = runBlocking {
        val key = png(24, 12)
        val preview = requireNotNull(decoder.decodeGaussianPreview(key))
        assertEquals(96, preview.width)
        assertEquals(96, preview.height)
        assertTrue(Color.red(preview.getPixel(0, 48)) > 250)
        assertTrue(Color.blue(preview.getPixel(95, 48)) > 250)
        val next = PreviewDecoder(context.contentResolver, reader)
        val loaded = requireNotNull(next.decodeGaussianPreview(key))
        assertEquals(preview.getPixel(48, 48), loaded.getPixel(48, 48))
        assertNull(next.cachedThumbnail(key, 96, 96))
    }

    @Test fun diskWriteFailureStillReturnsTheMemoryPreview() = runBlocking {
        val key = png()
        val target = files.gaussianPreviewFile(key)
        requireNotNull(target.parentFile).mkdirs()
        assertTrue(target.mkdir())
        val preview = requireNotNull(decoder.decodeGaussianPreview(key))
        assertSame(preview, decoder.cachedGaussianPreview(key))
        assertTrue(target.isDirectory)
    }

    @Test fun deletingAnOriginalAlsoDeletesItsDerivedPreview() = runBlocking {
        val key = png()
        decoder.decodeGaussianPreview(key)
        assertTrue(files.gaussianPreviewFile(key).isFile)
        assertTrue(files.deleteOriginal(key))
        assertFalse(files.gaussianPreviewFile(key).exists())
    }

    @Test fun videoSkipsGaussianDecodingAndNeverReadsAnOriginal() = runBlocking {
        val key = UUID.randomUUID().toString()
        assertNull(decoder.decodeGaussianPreview(key, MediaType.VIDEO))
        assertNull(decoder.cachedGaussianPreview(key, MediaType.VIDEO))
        assertNull(decoder.cachedThumbnail(key, 96, 96, MediaType.VIDEO))
    }

    private suspend fun png(width: Int = 96, height: Int = 96): String {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(width * height) { if (it % width < width / 2) Color.RED else Color.BLUE }, 0, width, 0, 0, width, height)
        val staged = UUID.randomUUID().toString().also { staging += it }
        val key = UUID.randomUUID().toString().also { owned += it }
        check(!files.hasOriginal(key) && !files.hasStaging(staged) && !files.gaussianPreviewFile(key).exists())
        try {
            files.writeToStaging(staged) { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
            files.promote(staged, key)
        } finally { bitmap.recycle() }
        return key
    }
}
