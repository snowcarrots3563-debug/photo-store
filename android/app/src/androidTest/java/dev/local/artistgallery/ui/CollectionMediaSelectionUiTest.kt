package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Local selection state only; delete events never access gallery files, preferences, or a database. */
class CollectionMediaSelectionUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private val events = mutableListOf<GalleryEvent>()
    private lateinit var current: MutableState<GalleryUiState>
    private val images = List(80) { index ->
        MediaAssetSummary("image$index", "$index.png", MediaType.IMAGE, 6000, index, "$index.png", "key$index")
    }
    private val collection = CollectionSummary("collection", "artist", "作品集样本", false, images.size, 0)
    private val detail = ArtistDetail(ArtistSummary("artist", "画师样本", 1, images.size), listOf(collection))

    @Test fun selectionAtADeepPositionPinsDeleteAndFurtherLongPressDoesNotResetIt() {
        show()
        compose.onNodeWithTag("collectionMediaList").performScrollToIndex(20)
        val before = scrollPosition()
        compose.onNodeWithText("18.png").assertIsDisplayed().performTouchInput { longClick() }
        compose.onNodeWithTag("collectionSelectionHeader").assertIsDisplayed()
        compose.onNodeWithText("已选择 1 项").assertIsDisplayed()
        compose.onNodeWithText("删除").assertIsDisplayed().assertIsEnabled()
        assertEquals(before, scrollPosition(), .1f)
        assertEquals(setOf("image18"), selectedIds())

        compose.onNodeWithText("20.png").assertIsDisplayed().performClick()
        assertEquals(setOf("image18", "image20"), selectedIds())
        compose.onNodeWithText("已选择 2 项").assertIsDisplayed()
        compose.onNodeWithText("21.png").assertIsDisplayed().performTouchInput { longClick() }
        val selected = selectedIds()
        assertTrue("Repeated long press must keep the previous selection", selected.containsAll(setOf("image18", "image20")))
        assertEquals(1, events.filterIsInstance<GalleryEvent.StartMediaSelection>().size)
        compose.onNodeWithText("已选择 ${selected.size} 项").assertIsDisplayed()
        assertEquals(before, scrollPosition(), .1f)

        compose.onNodeWithText("删除").performClick()
        assertEquals(GalleryEvent.OpenDeleteSelectedMedia, events.last())
        assertTrue(events.none { it == GalleryEvent.ConfirmDeleteSelectedMedia || it is GalleryEvent.OpenDeleteMedia || it is GalleryEvent.OpenMedia })
        compose.onNodeWithContentDescription("返回").assertIsDisplayed().performClick()
        assertEquals(GalleryEvent.ExitMediaSelection, events.last())
        assertTrue(selectedIds().isEmpty())
        compose.runOnIdle { assertFalse(current.value.mediaSelectionMode) }
        compose.onNodeWithTag("collectionSelectionHeader").assertDoesNotExist()
        compose.onNodeWithText("18.png").assertIsDisplayed()
        assertEquals(before, scrollPosition(), .1f)
    }

    private fun scrollPosition() = compose.onNodeWithTag("collectionMediaList")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun selectedIds(): Set<String> {
        lateinit var result: Set<String>
        compose.runOnIdle { result = current.value.selectedMediaIds.toSet() }
        return result
    }

    private fun show() {
        val initial = GalleryUiState(loadState = LoadState.Ready, mediaLoadState = LoadState.Ready,
            route = GalleryRoute.Collection(collection.id, collection.artistId), artistDetail = detail,
            collection = collection, ).withPreparedMedia(images)
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            current = remember { mutableStateOf(initial) }
            DTheme {
                CollectionScreen(current.value, 18.dp, null) { event ->
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
