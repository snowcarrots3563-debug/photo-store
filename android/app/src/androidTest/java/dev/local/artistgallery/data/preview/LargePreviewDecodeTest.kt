package dev.local.artistgallery.data.preview

import android.content.Context
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.local.artistgallery.data.importing.MediaFileStore
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.ui.PreviewImage
import dev.local.artistgallery.ui.rememberPreviewImage
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Generated two-frame GIFs only. Device execution is a separate, currently paused acceptance step. */
class LargePreviewDecodeTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private val context: Context get() = compose.activity
    private val files by lazy { MediaFileStore(context) }
    private val decoder by lazy { PreviewDecoder(context.contentResolver, AssetReader(context, files)) }
    private val originals = mutableSetOf<String>()
    private val staging = mutableSetOf<String>()

    @After fun cleanup() = runBlocking {
        originals.forEach { files.deleteOriginal(it) }
        staging.forEach { files.deleteStaging(it) }
    }

    @Test fun animatedDimensionsFitTheTargetOnBothImageDecoderAndMoviePaths() = runBlocking {
        val key = gif()
        val drawable = decoder.decodeAnimated(key, 4, 4)
        assertEquals(4, drawable.intrinsicWidth)
        assertEquals(2, drawable.intrinsicHeight)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            assertTrue(drawable is AnimatedImageDrawable)
            assertFalse((drawable as AnimatedImageDrawable).isRunning)
        }
        val static = requireNotNull(decoder.decodeBitmap(key, 4, 4))
        assertEquals(2f, static.width.toFloat() / static.height, .01f)
        static.recycle()
    }

    @Test fun pagesReceiveSeparateDrawablesAndAnimationCanBeStopped() = runBlocking {
        val key = gif()
        val first = decoder.decodeAnimated(key, 8, 4)
        val second = decoder.decodeAnimated(key, 8, 4)
        assertNotSame(first, second)
        compose.runOnIdle {
            first.startIfAnimated()
            first.stopIfAnimated()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                assertFalse((first as AnimatedImageDrawable).isRunning)
                assertFalse((second as AnimatedImageDrawable).isRunning)
            }
        }
    }

    @Test fun readyNeighborIsReusedAndGifSettingChangesTheDecodedRepresentation() {
        val key = runBlocking { gif() }
        val current = mutableStateOf(false)
        val animated = mutableStateOf(true)
        val shown = AtomicReference<PreviewImage?>()
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                val image = rememberPreviewImage(key, decoder, 8, 4, animated.value, current.value)
                SideEffect { shown.set(image) }
            }
        }
        compose.waitUntil(5_000) { shown.get() is PreviewImage.Animated }
        val neighbor = shown.get()
        compose.runOnIdle { current.value = true }
        compose.waitForIdle()
        assertSame(neighbor, shown.get())
        compose.runOnIdle { animated.value = false }
        compose.waitUntil(5_000) { shown.get() is PreviewImage.Still }
        val bitmap = (shown.get() as PreviewImage.Still).bitmap
        assertEquals(2f, bitmap.width.toFloat() / bitmap.height, .01f)
        compose.runOnIdle { animated.value = true }
        compose.waitUntil(5_000) { shown.get() is PreviewImage.Animated }
        assertNotSame(neighbor, shown.get())
    }

    private suspend fun gif(): String {
        val stagingKey = UUID.randomUUID().toString()
        val storageKey = UUID.randomUUID().toString()
        check(!files.hasStaging(stagingKey) && !files.hasOriginal(storageKey))
        staging += stagingKey
        originals += storageKey
        files.writeToStaging(stagingKey) { it.write(twoFrameGif()) }
        files.promote(stagingKey, storageKey)
        return storageKey
    }

    private fun twoFrameGif(): ByteArray = ByteArrayOutputStream().apply {
        write("GIF89a".toByteArray(Charsets.US_ASCII))
        write(byteArrayOf(8, 0, 4, 0, 0x80.toByte(), 0, 0, 0, 0, 0, 0xff.toByte(), 0, 0))
        repeat(2) { frame ->
            write(byteArrayOf(0x21, 0xf9.toByte(), 4, 0, 10, 0, 0, 0))
            write(byteArrayOf(0x2c, 0, 0, 0, 0, 8, 0, 4, 0, 0))
            val codes = List(32) { listOf(4, (it + frame) % 2) }.flatten() + 5
            val packed = ByteArray((codes.size * 3 + 7) / 8)
            codes.forEachIndexed { index, code ->
                repeat(3) { bit ->
                    val position = index * 3 + bit
                    packed[position / 8] = (packed[position / 8].toInt() or (((code shr bit) and 1) shl (position % 8))).toByte()
                }
            }
            write(2); write(packed.size); write(packed); write(0)
        }
        write(0x3b)
    }.toByteArray()
}
