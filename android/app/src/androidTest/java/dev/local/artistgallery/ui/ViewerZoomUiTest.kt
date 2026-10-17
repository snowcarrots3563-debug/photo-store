package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.model.*
import dev.local.artistgallery.ui.viewer.ImageViewerScreen
import dev.local.artistgallery.ui.viewer.ViewerCardDragKey
import dev.local.artistgallery.ui.viewer.ViewerPanKey
import dev.local.artistgallery.ui.viewer.ViewerZoomKey
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** Synthetic UI state with no decoder; no gallery data, files, or preferences are written. */
class ViewerZoomUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private val events = mutableListOf<GalleryEvent>()
    private lateinit var options: MutableState<BrowseOptions>
    private val images = listOf(
        MediaAssetSummary("first", "1.png", MediaType.IMAGE, 6000, 0, "1.png", "first"),
        MediaAssetSummary("middle", "2.png", MediaType.IMAGE, 6000, 1, "2.png", "middle"),
        MediaAssetSummary("gif", "3.gif", MediaType.GIF, 6000, 2, "3.gif", "gif"),
    )

    @Test fun pinchZoomAndBoundedPanKeepTheCurrentPage() {
        showViewer()
        pinch("middle", .12f, .45f)
        assertEquals(3f, zoom("middle"), .01f)
        compose.onNodeWithTag("imagePage:middle").performTouchInput {
            down(center)
            moveTo(Offset(centerX + width * .4f, centerY + height * .35f), delayMillis = 250)
            up()
        }
        val node = compose.onNodeWithTag("imagePage:middle").fetchSemanticsNode()
        val pan = node.config[ViewerPanKey]
        assertTrue(pan != Offset.Zero)
        assertTrue(abs(pan.x) <= node.size.width * (zoom("middle") - 1f) / 2f)
        assertTrue(abs(pan.y) <= node.size.height * (zoom("middle") - 1f) / 2f)
        compose.onNodeWithText("2 / 3").assertIsDisplayed()
        assertTrue(events.isEmpty())
    }

    @Test fun pinchBackToOneResetsPanAndRestoresSeamlessPaging() {
        showViewer()
        pinch("middle", .14f, .44f)
        assertTrue(zoom("middle") > 1f)
        pinch("middle", .44f, .06f)
        assertEquals(1f, zoom("middle"), .01f)
        assertEquals(Offset.Zero, compose.onNodeWithTag("imagePage:middle").fetchSemanticsNode().config[ViewerPanKey])
        compose.onNodeWithTag("imagePager").performTouchInput { swipeLeft() }
        compose.onNodeWithText("3 / 3").assertIsDisplayed()
        compose.onNodeWithTag("imagePager").performTouchInput { swipeRight() }
        compose.onNodeWithText("2 / 3").assertIsDisplayed()
        assertEquals(listOf("gif", "middle"), events.filterIsInstance<GalleryEvent.OpenMedia>().map { it.assetId })
    }

    @Test fun pageButtonsResetZoomAndPreloadedPagesDoNotClearActiveZoom() {
        showViewer()
        pinch("middle", .15f, .4f)
        compose.waitForIdle()
        assertTrue(zoom("middle") > 1.5f)
        compose.onNodeWithTag("imagePager").performTouchInput { swipeLeft() }
        compose.onNodeWithText("2 / 3").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一张").performClick()
        compose.onNodeWithText("3 / 3").assertIsDisplayed()
        assertEquals(1f, zoom("gif"), .01f)
        compose.onNodeWithContentDescription("上一张").performClick()
        compose.onNodeWithText("2 / 3").assertIsDisplayed()
        assertEquals(1f, zoom("middle"), .01f)
    }

    @Test fun cardDragSpringsBackAndPinchStillZooms() {
        showViewer(cardEffects = true)
        compose.onNodeWithTag("imagePage:middle").performTouchInput {
            down(center)
            moveTo(Offset(centerX + width * .25f, centerY + height * .1f), delayMillis = 250)
        }
        val heldDrag = compose.onNodeWithTag("imagePage:middle").fetchSemanticsNode().config[ViewerCardDragKey]
        assertTrue(heldDrag != Offset.Zero)
        compose.onNodeWithTag("imagePage:middle").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_500)
        val releasedDrag = compose.onNodeWithTag("imagePage:middle").fetchSemanticsNode().config[ViewerCardDragKey]
        assertEquals(0f, releasedDrag.x, .1f)
        assertEquals(0f, releasedDrag.y, .1f)
        pinch("middle", .15f, .4f)
        assertTrue(zoom("middle") > 1.5f)
        compose.onNodeWithText("2 / 3").assertIsDisplayed()
        assertTrue(events.isEmpty())
    }

    @Test fun changingCardModeResetsZoomAndPan() {
        showViewer(cardEffects = true)
        pinch("middle", .15f, .4f)
        assertTrue(zoom("middle") > 1.5f)
        compose.runOnIdle { options.value = options.value.copy(cardEffects = false) }
        assertEquals(1f, zoom("middle"), .01f)
        assertEquals(Offset.Zero, compose.onNodeWithTag("imagePage:middle").fetchSemanticsNode().config[ViewerPanKey])
        compose.onNodeWithTag("imagePager").performTouchInput { swipeLeft() }
        compose.onNodeWithText("3 / 3").assertIsDisplayed()
    }

    @Test fun gifAssetsUseTheSameZoomAndPageResetLayer() {
        showViewer(initial = "gif")
        pinch("gif", .15f, .4f)
        assertTrue(zoom("gif") > 1.5f)
        compose.onNodeWithTag("imagePager").performTouchInput { swipeRight() }
        compose.onNodeWithText("3 / 3").assertIsDisplayed()
        compose.onNodeWithContentDescription("上一张").performClick()
        compose.onNodeWithText("2 / 3").assertIsDisplayed()
        assertEquals(1f, zoom("middle"), .01f)
    }

    private fun zoom(id: String) = compose.onNodeWithTag("imagePage:$id").fetchSemanticsNode().config[ViewerZoomKey]

    private fun pinch(id: String, startFraction: Float, endFraction: Float) {
        compose.onNodeWithTag("imagePage:$id").performTouchInput {
            down(0, Offset(centerX - width * startFraction, centerY))
            down(1, Offset(centerX + width * startFraction, centerY))
            repeat(12) { step ->
                val fraction = startFraction + (endFraction - startFraction) * (step + 1) / 12f
                updatePointerTo(0, Offset(centerX - width * fraction, centerY))
                updatePointerTo(1, Offset(centerX + width * fraction, centerY))
                move(delayMillis = 32)
            }
            up(0)
            up(1)
        }
    }

    private fun showViewer(initial: String = "middle", cardEffects: Boolean = false) {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            var selected by remember { mutableStateOf(initial) }
            options = remember { mutableStateOf(BrowseOptions(cardEffects = cardEffects)) }
            DTheme {
                ImageViewerScreen(GalleryUiState( browseOptions = options.value).withPreparedMedia(images), selected, null) { event ->
                    events.add(event)
                    when (event) {
                        is GalleryEvent.OpenMedia -> selected = event.assetId
                        is GalleryEvent.StepViewer -> selected = images[(images.indexOfFirst { it.id == selected } + event.direction).coerceIn(images.indices)].id
                        else -> Unit
                    }
                }
            }
        } }
    }
}
