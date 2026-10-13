package dev.local.artistgallery.data.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.artistgallery.data.importing.MediaFileStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic JPEGs only; no database calls, existing originals, or gallery records are changed. */
@RunWith(AndroidJUnit4::class)
class ThumbnailDecodeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val files = MediaFileStore(context)
    private val decoder = PreviewDecoder(context.contentResolver, AssetReader(context, files))
    private val ownedOriginals = mutableSetOf<String>()
    private val ownedStaging = mutableSetOf<String>()

    @After fun cleanup() = runBlocking {
        val root = requireNotNull(context.getExternalFilesDir(null)).canonicalFile
        ownedOriginals.forEach { key ->
            check(files.originalFile(key).canonicalFile == File(root, "originals/$key"))
            assertTrue(files.deleteOriginal(key))
        }
        ownedStaging.forEach { key ->
            check(files.stagingFile(key).canonicalFile == File(root, ".artistgallery-imports/$key.part"))
            assertTrue(files.deleteStaging(key))
        }
    }

    @Test fun smallThumbnailIsBoundedAndUsesLessMemoryThanThePrevious512Request() = runBlocking {
        val key = jpeg(Color.RED)
        val small = requireNotNull(decoder.decodeThumbnail(key, 128, 128))
        val previousSize = requireNotNull(decoder.decodeBitmap(key, 512, 512))
        assertTrue(small.width in 1..128)
        assertTrue(small.height in 1..128)
        assertTrue(previousSize.width > small.width)
        assertTrue(previousSize.height > small.height)
        assertTrue(small.allocationByteCount < previousSize.allocationByteCount)
        assertSame(small, decoder.cachedThumbnail(key, 128, 128))
    }

    @Test fun repeatedThumbnailRequestReusesBitmapWithoutReadingTheRemovedOriginal() = runBlocking {
        val key = jpeg(Color.RED)
        assertNull(decoder.cachedThumbnail(key, 128, 128))
        val first = requireNotNull(decoder.decodeThumbnail(key, 128, 128))
        assertTrue(files.deleteOriginal(key))
        assertFalse(files.hasOriginal(key))
        assertSame(first, decoder.cachedThumbnail(key, 128, 128))
        assertSame(first, decoder.decodeThumbnail(key, 128, 128))
    }

    @Test fun wideThumbnailCropsToItsBoundsWhileViewerDecodeKeepsTheOriginalAspectRatio() = runBlocking {
        val key = jpeg(Color.RED, width = 2048, height = 512)
        val thumbnail = requireNotNull(decoder.decodeThumbnail(key, 128, 128))
        val viewer = requireNotNull(decoder.decodeBitmap(key, 128, 128))

        assertEquals(128, thumbnail.width)
        assertEquals(128, thumbnail.height)
        assertEquals(128, viewer.width)
        assertEquals(32, viewer.height)
        assertSame(thumbnail, decoder.cachedThumbnail(key, 128, 128))
    }

    @Test fun longStripThumbnailSelectsTheCenterAndKeepsSmallBitmapBounds() = runBlocking {
        val source = Bitmap.createBitmap(512, 8192, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        val paint = Paint()
        paint.color = Color.RED
        canvas.drawRect(0f, 0f, 512f, 2730f, paint)
        paint.color = Color.BLUE
        canvas.drawRect(0f, 2730f, 512f, 5462f, paint)
        paint.color = Color.GREEN
        canvas.drawRect(0f, 5462f, 512f, 8192f, paint)
        val key = jpeg(source)

        val thumbnail = requireNotNull(decoder.decodeThumbnail(key, 128, 128))

        assertEquals(128, thumbnail.width)
        assertEquals(128, thumbnail.height)
        assertTrue(thumbnail.allocationByteCount <= 128 * 128 * 4)
        val center = thumbnail.getPixel(64, 64)
        assertTrue(Color.blue(center) > 200 && Color.red(center) < 32 && Color.green(center) < 32)
        assertSame(thumbnail, decoder.cachedThumbnail(key, 128, 128))
    }

    @Test fun largerTargetGetsItsOwnHigherResolutionBitmapAndKeepsTheSmallEntry() = runBlocking {
        val key = jpeg(Color.RED)
        val small = requireNotNull(decoder.decodeThumbnail(key, 128, 128))
        assertNull(decoder.cachedThumbnail(key, 256, 256))
        val large = requireNotNull(decoder.decodeThumbnail(key, 256, 256))
        assertNotSame(small, large)
        assertTrue(large.width > small.width)
        assertTrue(large.height > small.height)
        assertTrue(large.width <= 256 && large.height <= 256)
        assertSame(small, decoder.cachedThumbnail(key, 128, 128))
        assertSame(large, decoder.cachedThumbnail(key, 256, 256))
        assertNull(decoder.cachedThumbnail(key, 128, 256))
        val tallerTarget = requireNotNull(decoder.decodeThumbnail(key, 128, 256))
        assertNotSame(small, tallerTarget)
        assertSame(tallerTarget, decoder.cachedThumbnail(key, 128, 256))
    }

    @Test fun differentStorageKeysNeverReuseAnotherImagesBitmap() = runBlocking {
        val redKey = jpeg(Color.RED)
        val blueKey = jpeg(Color.BLUE)
        val red = requireNotNull(decoder.decodeThumbnail(redKey, 128, 128))
        assertNull(decoder.cachedThumbnail(blueKey, 128, 128))
        val blue = requireNotNull(decoder.decodeThumbnail(blueKey, 128, 128))
        assertNotSame(red, blue)
        val redPixel = red.getPixel(red.width / 2, red.height / 2)
        val bluePixel = blue.getPixel(blue.width / 2, blue.height / 2)
        assertTrue(Color.red(redPixel) > 200 && Color.blue(redPixel) < 32)
        assertTrue(Color.blue(bluePixel) > 200 && Color.red(bluePixel) < 32)
        assertSame(red, decoder.cachedThumbnail(redKey, 128, 128))
        assertSame(blue, decoder.cachedThumbnail(blueKey, 128, 128))
    }

    private suspend fun jpeg(color: Int, width: Int = 2048, height: Int = 2048): String =
        jpeg(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) })

    private suspend fun jpeg(source: Bitmap): String {
        val stagingKey = UUID.randomUUID().toString()
        val storageKey = UUID.randomUUID().toString()
        check(!files.hasStaging(stagingKey) && !files.hasOriginal(storageKey))
        ownedStaging += stagingKey
        ownedOriginals += storageKey
        try {
            files.writeToStaging(stagingKey) { output -> check(source.compress(Bitmap.CompressFormat.JPEG, 90, output)) }
            files.promote(stagingKey, storageKey)
        } finally {
            source.recycle()
        }
        return storageKey
    }
}
