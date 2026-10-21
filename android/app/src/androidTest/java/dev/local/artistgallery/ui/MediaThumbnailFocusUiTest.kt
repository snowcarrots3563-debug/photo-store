package dev.local.artistgallery.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.local.artistgallery.data.importing.MediaFileStore
import dev.local.artistgallery.data.preview.AssetReader
import dev.local.artistgallery.data.preview.PreviewDecoder
import dev.local.artistgallery.data.preview.SyntheticVideoFixture
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.model.MediaType
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Owned UUID fixtures only; no database, preferences, or existing gallery media. */
class MediaThumbnailFocusUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private val files by lazy { MediaFileStore(compose.activity) }
    private val decoder by lazy { PreviewDecoder(compose.activity.contentResolver, AssetReader(compose.activity, files)) }
    private val originals = mutableSetOf<String>()
    private val staging = mutableSetOf<String>()
    private lateinit var currentKey: MutableState<String>
    private lateinit var visible: MutableState<Boolean>
    private var videoFixture: SyntheticVideoFixture? = null
    private val targetPx get() = (128 * compose.activity.resources.displayMetrics.density).roundToInt()

    @After fun cleanup() = runBlocking {
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { if (::visible.isInitialized) visible.value = false }
        compose.waitForIdle()
        videoFixture?.cleanup()
        val root = requireNotNull(compose.activity.getExternalFilesDir(null)).canonicalFile
        originals.forEach { key ->
            check(files.originalFile(key).canonicalFile == File(root, "originals/$key"))
            assertTrue(files.deleteOriginal(key))
        }
        staging.forEach { key ->
            check(files.stagingFile(key).canonicalFile == File(root, ".artistgallery-imports/$key.part"))
            assertTrue(files.deleteStaging(key))
        }
    }

    @Test fun coldLoadKeepsSameColorPreviewAndRevealsDetailWithoutMovingTheCell() = runBlocking {
        val key = png(pattern = true)
        assertNull(decoder.cachedThumbnail(key, 12, 12))
        show(key)
        waitForFinal(key)
        compose.mainClock.advanceTimeByFrame()
        val tiny = requireNotNull(decoder.cachedThumbnail(key, 12, 12))
        val beforeBounds = compose.onNodeWithTag("thumbnail").fetchSemanticsNode().boundsInRoot
        val blurred = capture()
        val left = blurred.toPixelMap()[blurred.width / 4, blurred.height / 2]
        val right = blurred.toPixelMap()[blurred.width * 3 / 4, blurred.height / 2]
        assertTrue(left.red > left.blue + .2f)
        assertTrue(right.blue > right.red + .2f)

        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.advanceTimeByFrame()
        val clear = capture()
        assertTrue("Clear image must reveal finer stripes", detail(clear) > detail(blurred) * 2 + .01f)
        assertEquals(beforeBounds, compose.onNodeWithTag("thumbnail").fetchSemanticsNode().boundsInRoot)
        assertEquals(12, tiny.width)
        assertEquals(12, tiny.height)
        assertFalse(tiny.isRecycled)
    }

    @Test fun fullCacheIsImmediatelyVisibleWithoutTinyReadEvenAfterSourceRemoval() = runBlocking {
        val key = png(Color.RED)
        val cached = requireNotNull(decoder.decodeThumbnail(key, targetPx, targetPx))
        assertTrue(files.deleteOriginal(key))
        show(key)
        val pixel = capture().toPixelMap()[targetPx / 2, targetPx / 2]
        assertTrue(pixel.red > .9f && pixel.blue < .1f)
        assertNull(decoder.cachedThumbnail(key, 12, 12))
        assertSame(cached, decoder.cachedThumbnail(key, targetPx, targetPx))
        assertFalse(cached.isRecycled)
    }

    @Test fun defaultThumbnailCallDoesNotRequestTheTinyPreview() = runBlocking {
        val key = png(Color.BLUE)
        show(key, focus = false)
        waitForFinal(key)
        compose.mainClock.advanceTimeByFrame()
        val pixel = capture().toPixelMap()[targetPx / 2, targetPx / 2]
        assertTrue(pixel.blue > .9f && pixel.red < .1f)
        assertNull(decoder.cachedThumbnail(key, 12, 12))
    }

    @Test fun replacingTheKeyDoesNotShowThePreviousCachedColor() = runBlocking {
        val red = png(Color.RED)
        val blue = png(Color.BLUE)
        decoder.decodeThumbnail(red, targetPx, targetPx)
        decoder.decodeThumbnail(blue, targetPx, targetPx)
        show(red)
        assertTrue(capture().toPixelMap()[targetPx / 2, targetPx / 2].red > .9f)
        compose.runOnIdle { currentKey.value = blue }
        compose.mainClock.advanceTimeByFrame()
        val pixel = capture().toPixelMap()[targetPx / 2, targetPx / 2]
        assertTrue(pixel.blue > .9f && pixel.red < .1f)
        assertNull(decoder.cachedThumbnail(blue, 12, 12))
    }

    @Test fun translucentArtworkHasNoOpaquePreviewJumpAtTheEnd() = runBlocking {
        val key = png(0x80FF0000.toInt())
        decoder.decodeThumbnail(key, 12, 12)
        show(key)
        waitForFinal(key)
        compose.mainClock.advanceTimeByFrame()
        var previous = capture().toPixelMap()[targetPx / 2, targetPx / 2]
        var largestStep = 0f
        repeat(30) {
            compose.mainClock.advanceTimeBy(16)
            val next = capture().toPixelMap()[targetPx / 2, targetPx / 2]
            largestStep = maxOf(largestStep, abs(next.green - previous.green), abs(next.blue - previous.blue))
            previous = next
        }
        val settled = previous
        assertTrue("Fixture must remain translucent", settled.green > .3f && settled.blue > .3f)
        assertTrue("Removing the tiny layer must not change artwork opacity abruptly",
            largestStep < .12f)
    }

    @Test fun videoFocusOptInStillRequestsOnlyTheNormalFirstFrame() {
        val fixture = SyntheticVideoFixture(compose.activity).also { videoFixture = it }
        val key = fixture.video()
        val videoDecoder = PreviewDecoder(compose.activity.contentResolver, fixture.reader)
        show(key, type = MediaType.VIDEO, previewDecoder = videoDecoder)
        waitForFinal(key, videoDecoder, MediaType.VIDEO)
        assertNull(videoDecoder.cachedThumbnail(key, 12, 12, MediaType.VIDEO))
    }

    private fun show(key: String, focus: Boolean = true, type: MediaType = MediaType.IMAGE, previewDecoder: PreviewDecoder = decoder) {
        compose.mainClock.autoAdvance = false
        currentKey = mutableStateOf(key)
        visible = mutableStateOf(true)
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            DTheme {
                if (visible.value) {
                    val modifier = Modifier.size(128.dp).testTag("thumbnail")
                    if (focus) MediaThumbnail(currentKey.value, type, previewDecoder, modifier, cornerRadius = 0.dp, focusOnLoad = true)
                    else MediaThumbnail(currentKey.value, type, previewDecoder, modifier, cornerRadius = 0.dp)
                }
            }
        } }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
    }

    private fun waitForFinal(key: String, previewDecoder: PreviewDecoder = decoder, type: MediaType = MediaType.IMAGE) {
        compose.waitUntil(10_000) { previewDecoder.cachedThumbnail(key, targetPx, targetPx, type) != null }
    }

    private fun capture() = compose.onNodeWithTag("thumbnail").captureToImage()

    private fun detail(image: ImageBitmap): Float {
        val pixels = image.toPixelMap()
        val y = image.height / 2
        return (1 until image.width).sumOf { x ->
            (abs(pixels[x, y].red - pixels[x - 1, y].red) + abs(pixels[x, y].blue - pixels[x - 1, y].blue)).toDouble()
        }.toFloat() / (image.width - 1)
    }

    private suspend fun png(color: Int = Color.RED, pattern: Boolean = false): String {
        val source = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        source.eraseColor(color)
        if (pattern) {
            val canvas = Canvas(source)
            val paint = Paint().apply { this.color = Color.BLUE }
            canvas.drawRect(256f, 0f, 512f, 512f, paint)
            paint.color = Color.WHITE
            for (index in 0 until 16) {
                val x = index * 32f + (index % 3) * 7f
                canvas.drawRect(x, 0f, x + 4f, 512f, paint)
            }
        }
        val staged = UUID.randomUUID().toString().also { staging.add(it) }
        val key = UUID.randomUUID().toString().also { originals.add(it) }
        check(!files.hasOriginal(key) && !files.hasStaging(staged))
        try {
            files.writeToStaging(staged) { output -> check(source.compress(Bitmap.CompressFormat.PNG, 100, output)) }
            files.promote(staged, key)
        } finally { source.recycle() }
        return key
    }
}
