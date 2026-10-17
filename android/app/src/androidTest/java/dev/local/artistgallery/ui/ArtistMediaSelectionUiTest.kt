package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.local.artistgallery.data.ArtistImageLayout
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic selection state only; deletion events never modify a database or media files. */
class ArtistMediaSelectionUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private val events = mutableListOf<GalleryEvent>()
    private lateinit var current: MutableState<GalleryUiState>
    private val images = List(80) { index ->
        MediaAssetSummary("image$index", "$index.png", MediaType.IMAGE, 6000, index, "$index.png", "key$index")
    }
    private val detail = ArtistDetail(ArtistSummary("artist", "画师样本", 1, images.size), emptyList())

    @Test fun longPressStartsListSelectionAndDeleteOnlyRequestsConfirmation() {
        show()
        compose.onNodeWithText("0.png").performTouchInput { longClick() }
        compose.onNodeWithText("已选择 1 项").assertIsDisplayed()
        compose.onNodeWithContentDescription("更多操作").assertDoesNotExist()
        compose.onNodeWithTag("artistSwipe:image0", useUnmergedTree = true).assertDoesNotExist()
        checkbox("image0").assertIsOn()
        compose.onNodeWithText("1.png").performClick()
        checkbox("image1").assertIsOn()
        compose.onNodeWithText("已选择 2 项").assertIsDisplayed()
        compose.onNodeWithTag("artistSelectedRow:image0").performTouchInput { swipeLeft() }
        compose.onNodeWithText("已选择 2 项").assertIsDisplayed()
        compose.onNodeWithText("删除").assertIsEnabled().performClick()
        assertEquals(GalleryEvent.OpenDeleteSelectedMedia, events.last())
        assertTrue(events.none { it is GalleryEvent.OpenMedia || it is GalleryEvent.OpenDeleteMedia || it == GalleryEvent.ConfirmDeleteSelectedMedia })
    }

    @Test fun deselectingAllDisablesDeleteAndBackExitsSelection() {
        show()
        compose.onNodeWithText("0.png").performTouchInput { longClick() }
        compose.onNodeWithText("作品集").assertIsNotEnabled()
        compose.onNodeWithText("0.png").performClick()
        checkbox("image0").assertIsOff()
        compose.onNodeWithText("已选择 0 项").assertIsDisplayed()
        compose.onNodeWithText("删除").assertIsNotEnabled()
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(GalleryEvent.ExitMediaSelection, events.last())
        compose.onNodeWithText("画师样本").assertIsDisplayed()
        compose.onNodeWithContentDescription("更多操作").assertIsDisplayed()
        compose.onNodeWithText("作品集").assertIsEnabled()
        compose.onNodeWithTag("artistSwipe:image0", useUnmergedTree = true).assertExists()
        assertTrue(events.none { it == GalleryEvent.OpenDeleteSelectedMedia })
    }

    @Test fun gridLongPressAndTapsToggleSelectionAcrossBothThumbnailSizes() {
        show(ArtistImageLayout.Medium)
        compose.onNodeWithTag("artistTile:image0").performTouchInput { longClick() }
        compose.onNodeWithTag("artistTile:image0").assertIsOn()
        compose.onNodeWithTag("artistTile:image1").performClick()
        compose.onNodeWithTag("artistTile:image1").assertIsOn()
        compose.onNodeWithText("已选择 2 项").assertIsDisplayed()
        val mediumWidth = compose.onNodeWithTag("artistTile:image0").fetchSemanticsNode().size.width
        compose.runOnIdle { current.value = current.value.copy(browseOptions = BrowseOptions(imageLayout = ArtistImageLayout.Large)) }
        val largeWidth = compose.onNodeWithTag("artistTile:image0").fetchSemanticsNode().size.width
        assertTrue(largeWidth > mediumWidth)
        compose.onNodeWithTag("artistTile:image0").assertIsOn().performClick()
        compose.onNodeWithTag("artistTile:image0").assertIsOff()
        compose.onNodeWithTag("artistTile:image1").assertIsOn()
        compose.onNodeWithText("已选择 1 项").assertIsDisplayed()
        compose.onNodeWithText("0.png").assertDoesNotExist()
        assertTrue(events.none { it is GalleryEvent.OpenMedia || it is GalleryEvent.OpenDeleteMedia })
    }

    @Test fun savingDisablesTheSelectionDeleteAction() {
        show(selection = true, saving = true)
        compose.onNodeWithText("已选择 1 项").assertIsDisplayed()
        compose.onNodeWithText("删除").assertIsNotEnabled()
        assertTrue(events.isEmpty())
    }

    @Test fun enteringAndExitingSelectionPreservesTheScrolledImagePosition() {
        show()
        compose.onNodeWithTag("artistMediaList").performScrollToIndex(30)
        val before = compose.onNodeWithText("30.png").fetchSemanticsNode().positionInRoot.y
        compose.onNodeWithText("30.png").performTouchInput { longClick() }
        checkbox("image30").assertIsOn()
        assertEquals(before, compose.onNodeWithText("30.png").fetchSemanticsNode().positionInRoot.y, 1f)
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("artistSwipe:image30", useUnmergedTree = true).assertExists()
        assertEquals(before, compose.onNodeWithText("30.png").fetchSemanticsNode().positionInRoot.y, 1f)
    }

    @Test fun disablingSwipeDeleteKeepsRowsStationaryAndLongPressSelectionWorks() {
        show(swipeDelete = false)
        val before = compose.onNodeWithText("0.png").fetchSemanticsNode().positionInRoot.x
        compose.onNodeWithText("0.png").performTouchInput { swipeLeft() }
        assertEquals(before, compose.onNodeWithText("0.png").fetchSemanticsNode().positionInRoot.x, 1f)
        compose.onNodeWithTag("artistSwipe:image0", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("artistSwipeDelete:image0", useUnmergedTree = true).assertDoesNotExist()
        assertTrue(events.none { it is GalleryEvent.OpenMedia || it is GalleryEvent.OpenDeleteMedia })
        compose.onNodeWithText("0.png").performTouchInput { longClick() }
        compose.onNodeWithText("已选择 1 项").assertIsDisplayed()
        checkbox("image0").assertIsOn()
    }

    private fun checkbox(id: String) = compose.onNode(
        isToggleable() and hasAnyAncestor(hasTestTag("artistSelectedRow:$id")), useUnmergedTree = true,
    )

    private fun show(layout: ArtistImageLayout = ArtistImageLayout.List, selection: Boolean = false, saving: Boolean = false, swipeDelete: Boolean = true) {
        val initial = GalleryUiState(loadState = LoadState.Ready, mediaLoadState = LoadState.Ready,
            route = GalleryRoute.Artist("artist"), artistDetail = detail,
            browseOptions = BrowseOptions(imageLayout = layout, swipeDelete = swipeDelete), mediaSelectionMode = selection,
            selectedMediaIds = if (selection) setOf("image0") else emptySet(), isSaving = saving).withPreparedMedia(images)
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            current = remember { mutableStateOf(initial) }
            DTheme {
                ArtistDetailScreen(current.value, 18.dp, null, null) { event ->
                    events.add(event)
                    when (event) {
                        is GalleryEvent.StartMediaSelection -> current.value = current.value.copy(mediaSelectionMode = true, selectedMediaIds = setOf(event.assetId))
                        is GalleryEvent.ToggleMediaSelection -> current.value = current.value.copy(selectedMediaIds =
                            if (event.assetId in current.value.selectedMediaIds) current.value.selectedMediaIds - event.assetId else current.value.selectedMediaIds + event.assetId)
                        GalleryEvent.ExitMediaSelection -> current.value = current.value.copy(mediaSelectionMode = false, selectedMediaIds = emptySet())
                        is GalleryEvent.RememberScrollPosition -> current.value = current.value.copy(scrollPositions =
                            current.value.scrollPositions + (event.key to GalleryScrollPosition(event.index, event.offset)))
                        else -> Unit
                    }
                }
            }
        } }
        compose.waitForIdle()
    }
}
