package dev.local.artistgallery.data.preview

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.artistgallery.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Generated first-frame markers, not gallery imports. Instrumentation runtime remains a separate gate. */
@RunWith(AndroidJUnit4::class)
class VideoThumbnailDecodeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fixture = SyntheticVideoFixture(context)
    private val decoder = PreviewDecoder(context.contentResolver, fixture.reader)
    @After fun cleanup() { fixture.cleanup() }

    @Test fun thumbnailReadsTheMarkedFirstFrameRatherThanLaterBlueFrames() = runBlocking {
        val bitmap = requireNotNull(decoder.decodeThumbnail(fixture.video(), 160, 80, MediaType.VIDEO))
        assertEquals(160, bitmap.width)
        assertEquals(80, bitmap.height)
        assertRed(bitmap.getPixel(80, 40))
        assertWhite(bitmap.getPixel(10, 10))
    }

    @Test fun blackFirstFrameIsSuccessfulAndNeverSkippedForLaterBlueFrames() = runBlocking {
        val key = fixture.video(blackFirst = true)
        val bitmap = requireNotNull(decoder.decodeThumbnail(key, 96, 96, MediaType.VIDEO))
        val pixel = bitmap.getPixel(48, 48)
        assertTrue(Color.red(pixel) < 24 && Color.green(pixel) < 24 && Color.blue(pixel) < 24)
        assertSame(bitmap, decoder.cachedThumbnail(key, 96, 96, MediaType.VIDEO))
    }

    @Test fun visibleSizeAndMediaTypeHaveIndependentCacheEntriesAfterSourceRemoval() = runBlocking {
        val key = fixture.video()
        val small = requireNotNull(decoder.decodeThumbnail(key, 64, 64, MediaType.VIDEO))
        assertNull(decoder.cachedThumbnail(key, 64, 64))
        val large = requireNotNull(decoder.decodeThumbnail(key, 128, 128, MediaType.VIDEO))
        assertNotSame(small, large)
        assertTrue(large.allocationByteCount <= 128 * 128 * 4)
        assertTrue(fixture.files.deleteOriginal(key))
        assertSame(small, decoder.decodeThumbnail(key, 64, 64, MediaType.VIDEO))
        assertSame(large, decoder.cachedThumbnail(key, 128, 128, MediaType.VIDEO))
        assertFalse(small.isRecycled)
    }

    @Test fun portraitFrameKeepsItsMarkerAndBoundedDimensions() = runBlocking {
        val bitmap = requireNotNull(decoder.decodeThumbnail(fixture.video(160, 320), 80, 160, MediaType.VIDEO))
        assertEquals(80, bitmap.width)
        assertEquals(160, bitmap.height)
        assertWhite(bitmap.getPixel(10, 20))
        assertRed(bitmap.getPixel(40, 80))
    }

    @Test fun rotationMetadataIsAppliedOnceToTheFirstFrame() = runBlocking {
        val bitmap = requireNotNull(decoder.decodeThumbnail(fixture.video(rotation = 90), 80, 160, MediaType.VIDEO))
        assertEquals(80, bitmap.width)
        assertEquals(160, bitmap.height)
        assertWhite(bitmap.getPixel(70, 20))
        assertRed(bitmap.getPixel(40, 80))
    }

    @Test fun supportedFourKSourceReturnsOnlyTheRequestedThumbnailSize() = runBlocking {
        val bitmap = requireNotNull(decoder.decodeThumbnail(fixture.video(3840, 2160, frames = 3), 96, 96, MediaType.VIDEO))
        assertEquals(96, bitmap.width)
        assertEquals(96, bitmap.height)
        assertTrue(bitmap.allocationByteCount <= 96 * 96 * 4)
        assertRed(bitmap.getPixel(48, 48))
    }

    @Test fun brokenAndNoTrackSourcesNeverProduceOrCacheAnImage() = runBlocking {
        listOf(fixture.broken(), fixture.noTrack()).forEach { key ->
            assertNull(runCatching { decoder.decodeThumbnail(key, 96, 96, MediaType.VIDEO) }.getOrNull())
            assertNull(decoder.cachedThumbnail(key, 96, 96, MediaType.VIDEO))
        }
        // A subsequent valid request still works after failed extraction released its descriptor/retriever.
        assertNotNull(decoder.decodeThumbnail(fixture.video(), 96, 96, MediaType.VIDEO))
    }

    private fun assertRed(pixel: Int) { assertTrue(Color.red(pixel) > 180 && Color.green(pixel) < 80 && Color.blue(pixel) < 80) }
    private fun assertWhite(pixel: Int) { assertTrue(Color.red(pixel) > 180 && Color.green(pixel) > 180 && Color.blue(pixel) > 180) }
}
