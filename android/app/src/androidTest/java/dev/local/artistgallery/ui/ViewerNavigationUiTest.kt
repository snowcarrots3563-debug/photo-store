package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.model.*
import dev.local.artistgallery.ui.viewer.ImageViewerScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Local Compose state only; no gallery or device preferences are written. */
class ViewerNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private val events = mutableListOf<GalleryEvent>()
    private val images = listOf(
        MediaAssetSummary("first", "这是用来检查长文件名换行时图片区域保持固定大小的第一张图片.png", MediaType.IMAGE, 6000, 0, "1.png", "first"),
        MediaAssetSummary("video", "2.mp4", MediaType.VIDEO, 6000, 1, "2.mp4", "video"),
        MediaAssetSummary("middle", "3.png", MediaType.IMAGE, 6000, 2, "3.png", "middle"),
        MediaAssetSummary("last", "4.gif", MediaType.GIF, 6000, 3, "4.gif", "last"),
    )

    @Test fun leftSwipeAdvancesRightSwipeReturnsAndButtonsUseSameSequence() {
        showViewer("middle")
        val viewportSize = compose.onNodeWithTag("imagePager").fetchSemanticsNode().size
        compose.onNodeWithText("2 / 3").assertExists()
        compose.onNodeWithTag("imagePager").performTouchInput { swipeLeft() }
        compose.onNodeWithText("3 / 3").assertExists()
        compose.onNodeWithTag("imagePager").performTouchInput { swipeRight() }
        compose.onNodeWithText("2 / 3").assertExists()
        compose.onNodeWithContentDescription("上一张").performClick()
        compose.onNodeWithText("1 / 3").assertExists()
        assertEquals(viewportSize, compose.onNodeWithTag("imagePager").fetchSemanticsNode().size)
        compose.onNodeWithContentDescription("下一张").performClick()
        compose.onNodeWithText("2 / 3").assertExists()
        assertEquals(listOf("last", "middle", "first", "middle"), events.filterIsInstance<GalleryEvent.OpenMedia>().map { it.assetId })
    }

    @Test fun adjacentPagesMoveTogetherWithoutGapBeforeSelectionChanges() {
        showViewer("middle")
        val before = compose.onNodeWithTag("imagePage:middle").fetchSemanticsNode().positionInRoot.x
        compose.onNodeWithTag("imagePager").performTouchInput {
            down(center)
            moveTo(Offset(centerX - width * .3f, centerY), delayMillis = 200)
        }
        val current = compose.onNodeWithTag("imagePage:middle").fetchSemanticsNode()
        val next = compose.onNodeWithTag("imagePage:last").fetchSemanticsNode()
        assertTrue(current.positionInRoot.x < before)
        assertEquals(current.positionInRoot.x + current.size.width, next.positionInRoot.x, 1f)
        assertTrue(events.isEmpty())
        compose.onNodeWithTag("imagePager").performTouchInput { up() }
    }

    @Test fun incompleteDragReturnsToCurrentPageAndEndsDoNotWrap() {
        showViewer("first")
        compose.onNodeWithTag("imagePager").performTouchInput { swipeRight() }
        compose.onNodeWithText("1 / 3").assertExists()
        compose.onNodeWithTag("imagePager").performTouchInput {
            down(center)
            moveTo(Offset(centerX - width * .08f, centerY), delayMillis = 500)
            advanceEventTime(500)
            up()
        }
        compose.onNodeWithText("1 / 3").assertExists()
        assertTrue(events.isEmpty())
        compose.onNodeWithTag("imagePager").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("imagePager").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("imagePager").performTouchInput { swipeLeft() }
        compose.onNodeWithText("3 / 3").assertExists()
        assertEquals(2, events.filterIsInstance<GalleryEvent.OpenMedia>().size)
    }

    @Test fun cardDragDoesNotTurnPagesAndButtonsStillWork() {
        showViewer("middle", cardEffects = true)
        compose.onNodeWithTag("imagePage:middle").performTouchInput { swipeRight() }
        compose.onNodeWithText("2 / 3").assertExists()
        assertTrue(events.isEmpty())
        compose.onNodeWithContentDescription("下一张").performClick()
        compose.onNodeWithText("3 / 3").assertExists()
        assertEquals(listOf(GalleryEvent.StepViewer(1)), events)
    }

    @Test fun homeCoverNavigatesToArtistAndHasNoCoverEditEntry() {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            DTheme { ArtistHomeScreen(
                GalleryUiState(loadState = LoadState.Ready, artists = listOf(ArtistSummary("artist", "画师样本", 1, 3))),
                18.dp, null, {}, null, null, events::add,
            ) }
        } }
        compose.onNodeWithContentDescription("更换封面").assertDoesNotExist()
        val coverX = with(compose.density) { 46.dp.toPx() }
        compose.onNodeWithText("画师样本").performTouchInput { click(Offset(coverX, centerY)) }
        assertEquals(listOf(GalleryEvent.OpenArtist("artist")), events)
    }

    @Test fun artistMenuOpensExistingCoverPicker() {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            var state by remember { mutableStateOf(GalleryUiState(route = GalleryRoute.Artist("artist"), dialog = GalleryDialog.ArtistMenu)) }
            DTheme { GalleryDialogs(state, null, { event ->
                events.add(event)
                if (event is GalleryEvent.OpenArtistCover) state = state.copy(dialog = GalleryDialog.ArtistCover(event.artistId), coverLoadState = LoadState.Ready, coverItems = listOf(images.first()))
            }) }
        } }
        compose.onNodeWithText("更换封面").performClick()
        compose.onNodeWithText("更换画师封面").assertIsDisplayed()
        compose.onNodeWithText("设为封面").assertIsDisplayed()
        assertEquals(listOf(GalleryEvent.OpenArtistCover("artist")), events)
    }

    private fun showViewer(initial: String, cardEffects: Boolean = false) {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            var selected by remember { mutableStateOf(initial) }
            DTheme { ImageViewerScreen(
                GalleryUiState( browseOptions = BrowseOptions(cardEffects, false)).withPreparedMedia(images), selected, null,
            ) { event ->
                events.add(event)
                when (event) {
                    is GalleryEvent.OpenMedia -> selected = event.assetId
                    is GalleryEvent.StepViewer -> {
                        val sequence = images.filter { it.type != MediaType.VIDEO }
                        selected = sequence[(sequence.indexOfFirst { it.id == selected } + event.direction).coerceIn(sequence.indices)].id
                    }
                    else -> Unit
                }
            } }
        } }
    }
}
