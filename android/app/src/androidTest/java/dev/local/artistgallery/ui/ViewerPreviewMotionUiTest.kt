package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.data.importing.MediaFileStore
import dev.local.artistgallery.data.preview.AssetReader
import dev.local.artistgallery.data.preview.PreviewDecoder
import dev.local.artistgallery.model.MediaAssetSummary
import dev.local.artistgallery.model.MediaType
import dev.local.artistgallery.ui.viewer.ImageViewerScreen
import dev.local.artistgallery.ui.viewer.ViewerPanKey
import dev.local.artistgallery.ui.viewer.ViewerZoomKey
import kotlinx.coroutines.runBlocking
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ViewerPreviewMotionUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private val files by lazy { MediaFileStore(compose.activity) }
    private val decoder by lazy { PreviewDecoder(compose.activity.contentResolver, AssetReader(compose.activity, files)) }
    private val owned = mutableListOf<String>()

    @After fun cleanup() = runBlocking {
        compose.mainClock.autoAdvance = true
        compose.activityRule.scenario.onActivity { it.setContent { } }
        compose.waitForIdle()
        owned.forEach { files.deleteOriginal(it) }
    }

    @Test fun pendingViewerUsesOnlyItsOwnCachedGaussianWithoutReadingTheOriginalAgain() = runBlocking {
        val first = png(Color.RED)
        val second = png(Color.BLUE)
        decoder.decodeGaussianPreview(first)
        decoder.decodeGaussianPreview(second)
        files.deleteOriginal(first)
        files.deleteOriginal(second)
        val media = listOf(first, second).mapIndexed { index, key ->
            MediaAssetSummary(key, "$index.png", MediaType.IMAGE, 1, index, key, key)
        }
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            var selected by remember { mutableStateOf(first) }
            DTheme { ImageViewerScreen(GalleryUiState(browseOptions = BrowseOptions(cardEffects = true)).withPreparedMedia(media), selected, decoder) { event ->
                if (event is GalleryEvent.StepViewer) selected = second
            } }
        } }
        compose.onNodeWithTag("viewerPreview:$first").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一张").performClick()
        compose.onNodeWithTag("viewerPreview:$second").assertIsDisplayed()
        compose.onNodeWithTag("viewerPreview:$first").assertDoesNotExist()
        Unit
    }

    @Test fun originalSliderAndDragShareFitBoundsAndCloseImmediatelyDisablesControls() = runBlocking {
        val key = png(Color.RED)
        val media = MediaAssetSummary(key, "fixture.png", MediaType.IMAGE, 1, 0, key, key)
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            DTheme { ImageViewerScreen(GalleryUiState().withPreparedMedia(listOf(media)), key, decoder) { } }
        } }
        compose.onNodeWithText("查看原图").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("originalImagePixels").fetchSemanticsNodes().isNotEmpty() }
        val frame = compose.onNodeWithTag("originalImageFrame")
        compose.onNodeWithTag("originalZoomSlider").performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        frame.performTouchInput { swipe(center, Offset(centerX + width * .4f, centerY + height * .3f), 300) }
        val node = frame.fetchSemanticsNode()
        assertEquals(3f, node.config[ViewerZoomKey], .01f)
        val pan = node.config[ViewerPanKey]
        val fit = minOf(node.size.width / 600f, node.size.height / 300f)
        assertTrue(kotlin.math.abs(pan.x) <= ((600 * fit * 3 - node.size.width) / 2).coerceAtLeast(0f) + 1)
        assertTrue(kotlin.math.abs(pan.y) <= ((300 * fit * 3 - node.size.height) / 2).coerceAtLeast(0f) + 1)
        compose.onNodeWithTag("originalZoomSlider").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        assertEquals(Offset.Zero, frame.fetchSemanticsNode().config[ViewerPanKey])
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("originalZoomSlider").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        Unit
    }

    private suspend fun png(color: Int): String {
        val key = UUID.randomUUID().toString()
        val staged = UUID.randomUUID().toString()
        val bitmap = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        try {
            files.writeToStaging(staged) { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
            files.promote(staged, key)
            owned.add(key)
        } finally { bitmap.recycle(); files.deleteStaging(staged) }
        return key
    }

    @Test fun pagingSameKindDoesNotReplayEntryAndReturnKeepsBaseComposition() {
        lateinit var overlay: MutableState<GalleryOverlay>
        var baseCompositions = 0
        var overlayDisposed = 0
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            overlay = remember { mutableStateOf(GalleryOverlay.None) }
            DOverlaySurfaceMotion(overlay.value) { baseMotion, overlayMotion ->
                Box(baseMotion.fillMaxSize().testTag("base")) {
                    DisposableEffect(Unit) { baseCompositions++; onDispose { } }
                }
                if (overlay.value != GalleryOverlay.None) Box(overlayMotion.fillMaxSize().testTag("overlay")) {
                    DisposableEffect(Unit) { onDispose { overlayDisposed++ } }
                }
            }
        } }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { overlay.value = GalleryOverlay.CardViewer("first") }
        compose.mainClock.advanceTimeBy(400)
        val ready = compose.onNodeWithTag("overlay").fetchSemanticsNode().config[DMotionFraction]
        assertEquals(1f, ready, .001f)
        compose.runOnIdle { overlay.value = GalleryOverlay.CardViewer("next") }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1f, compose.onNodeWithTag("overlay").fetchSemanticsNode().config[DMotionFraction], .001f)
        compose.runOnIdle { overlay.value = GalleryOverlay.None }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1, overlayDisposed)
        assertEquals(1, baseCompositions)
        compose.mainClock.advanceTimeBy(400)
        assertEquals(1f, compose.onNodeWithTag("base").fetchSemanticsNode().config[DMotionFraction], .001f)
        compose.mainClock.autoAdvance = true
    }
}
