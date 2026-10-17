package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real row gestures over synthetic loaded content; no gallery files, database, or deletion run. */
class ArtistSwipeUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private val events = mutableListOf<GalleryEvent>()
    private val images = List(25) { index ->
        MediaAssetSummary("image$index", "$index.png", MediaType.IMAGE, 6000, index, "$index.png", "key$index")
    }
    private val revealWidth get() = with(compose.density) { 80.dp.toPx() }

    @Test fun fullRevealReleaseKeepsTheDraggedPositionAcrossFrames() {
        show()
        drag(-revealWidth * 1.6f)
        assertOffset(-revealWidth)
        row().performTouchInput { up() }
        repeat(4) {
            frame()
            assertOffset(-revealWidth)
        }
        settle()
        assertOffset(-revealWidth)
        assertTrue(events.isEmpty())
    }

    @Test fun reversingAnUnfinishedSettleClosesAndFurtherDragsStillWork() {
        show()
        drag(-revealWidth * .95f)
        row().performTouchInput { up() }
        repeat(2) { frame() }
        val settlingOffset = offset()
        assertTrue("The interrupted animation must be between its anchors", settlingOffset < -1f && settlingOffset > -revealWidth + 1f)
        drag(revealWidth * 1.6f)
        row().performTouchInput { up() }
        settle()
        assertOffset(0f)
        releaseDrag(-revealWidth * 1.6f)
        assertOffset(-revealWidth)
        releaseDrag(revealWidth * 1.6f)
        assertOffset(0f)
        assertTrue(events.isEmpty())
    }

    @Test fun pointerCancellationRestoresClosedAndOpenAnchors() {
        show()
        drag(-revealWidth * 1.6f)
        assertOffset(-revealWidth)
        row().performTouchInput { cancel() }
        settle()
        assertOffset(0f)
        releaseDrag(-revealWidth * 1.6f)
        drag(revealWidth * 1.6f)
        assertOffset(0f)
        row().performTouchInput { cancel() }
        settle()
        assertOffset(-revealWidth)
        releaseDrag(revealWidth * 1.6f)
        assertOffset(0f)
        assertTrue(events.isEmpty())
    }

    @Test fun verticalGestureOnARevealedRowStillScrollsTheArtistList() {
        show()
        releaseDrag(-revealWidth * 1.6f)
        val list = compose.onNodeWithTag("artistMediaList")
        val before = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val start = row().fetchSemanticsNode().boundsInRoot.center - list.fetchSemanticsNode().boundsInRoot.topLeft
        val distance = with(compose.density) { 160.dp.toPx() }
        list.performTouchInput { down(start) }
        frame()
        list.performTouchInput { moveTo(start - Offset(0f, distance / 2f), delayMillis = 160) }
        frame()
        list.performTouchInput { moveTo(start - Offset(0f, distance), delayMillis = 160) }
        frame()
        list.performTouchInput { up() }
        settle()
        val after = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("Vertical scrolling must remain responsive after a row swipe", after > before)
        assertTrue(events.isEmpty())
    }

    @Test fun rowOpensItsImageAfterTheRevealIsClosed() {
        show()
        releaseDrag(-revealWidth * 1.6f)
        releaseDrag(revealWidth * 1.6f)
        assertOffset(0f)
        compose.onNodeWithTag("artistSwipeContent:image0", useUnmergedTree = true).performTouchInput { click(center) }
        frame()
        assertEquals(listOf(GalleryEvent.OpenMedia("image0")), events.filterIsInstance<GalleryEvent.OpenMedia>())
        assertTrue(events.none { it is GalleryEvent.OpenDeleteMedia })
    }

    @Test fun exposedDeleteButtonRequestsConfirmationWithoutDeleting() {
        show()
        releaseDrag(-revealWidth * 1.6f)
        compose.onNodeWithTag("artistSwipeDelete:image0", useUnmergedTree = true).assertIsDisplayed().performClick()
        frame()
        assertEquals(listOf(GalleryEvent.OpenDeleteMedia("image0")), events)
        assertFalse(events.contains(GalleryEvent.ConfirmDeleteMedia))
        compose.onNodeWithText(images.first().name).assertExists()
    }

    private fun row() = compose.onNodeWithTag("artistSwipe:image0", useUnmergedTree = true)

    private fun offset(): Float {
        val outerX = row().fetchSemanticsNode().positionInRoot.x
        val foregroundX = compose.onNodeWithTag("artistSwipeContent:image0", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x
        return foregroundX - outerX
    }

    private fun assertOffset(expected: Float) = assertEquals(expected, offset(), 1f)

    /** Keep a pointer down across real Compose frames, including state and placement updates. */
    private fun drag(distance: Float) {
        val startX = row().fetchSemanticsNode().size.width * if (distance < 0f) .8f else .2f
        row().performTouchInput { down(Offset(startX, centerY)) }
        frame()
        row().performTouchInput { moveTo(Offset(startX + distance / 2f, centerY), delayMillis = 160) }
        frame()
        row().performTouchInput { moveTo(Offset(startX + distance, centerY), delayMillis = 160) }
        frame()
    }

    private fun releaseDrag(distance: Float) {
        drag(distance)
        row().performTouchInput { up() }
        settle()
    }

    private fun frame() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun show() {
        val detail = ArtistDetail(ArtistSummary("artist", "画师样本", 1, images.size), emptyList())
        val state = GalleryUiState(loadState = LoadState.Ready, mediaLoadState = LoadState.Ready,
            route = GalleryRoute.Artist("artist"), artistDetail = detail, ).withPreparedMedia(images)
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            DTheme { ArtistDetailScreen(state, 18.dp, null, null, events::add) }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }
}
