package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic Compose state only; no gallery, files or persisted device preferences are changed. */
class ArtistSortUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private val events = mutableListOf<GalleryEvent>()
    private lateinit var current: MutableState<GalleryUiState>
    private val images = List(60) { index ->
        MediaAssetSummary("image$index", "图片 $index.png", MediaType.IMAGE, 6000L + index, index,
            "$index.png", "key$index", index.toLong())
    }
    private val collection = CollectionSummary("collection", "artist", "作品集样本", false, 60, 0)
    private val detail = ArtistDetail(ArtistSummary("artist", "画师样本", 1, images.size), listOf(collection))

    @Test fun sortingEntrySitsBesideViewSwitchAndOnlyAppearsForArtistImages() {
        show()
        val sorting = compose.onNodeWithContentDescription("图片排序").assertIsDisplayed().fetchSemanticsNode()
        val view = compose.onNodeWithText("切换视图").fetchSemanticsNode()
        assertTrue(sorting.positionInRoot.x < view.positionInRoot.x)
        compose.onNodeWithContentDescription("图片排序").performClick()
        assertTrue(events[events.lastIndex - 1] is GalleryEvent.RememberScrollPosition)
        assertEquals(GalleryEvent.OpenDialog(GalleryDialog.ArtistImageSort), events.last())
        compose.runOnIdle { current.value = current.value.copy(artistTab = ArtistTab.Collections, dialog = GalleryDialog.None) }
        compose.onNodeWithContentDescription("图片排序").assertDoesNotExist()
        compose.runOnIdle { current.value = current.value.copy(route = GalleryRoute.Collection(collection.id, "artist"), collection = collection) }
        compose.onNodeWithContentDescription("图片排序").assertDoesNotExist()
    }

    @Test fun selectionAndSavingDisableTheSortingEntry() {
        show(initial().copy(mediaSelectionMode = true, selectedMediaIds = setOf("image0")))
        compose.onNodeWithContentDescription("图片排序").assertIsNotEnabled()
        compose.runOnIdle { current.value = current.value.copy(mediaSelectionMode = false, isSaving = true) }
        compose.onNodeWithContentDescription("图片排序").assertIsNotEnabled()
        assertTrue(events.none { it is GalleryEvent.OpenDialog || it is GalleryEvent.SetArtistImageSort })
    }

    @Test fun sevenScrollableChoicesShowCurrentSelectionAndChooseOnceThenClose() {
        show(initial().copy(dialog = GalleryDialog.ArtistImageSort,
            browseOptions = BrowseOptions(artistImageSorts = mapOf("artist" to MediaSort.SizeDescending))))
        compose.onNodeWithText(MediaSort.SizeDescending.label).performScrollTo().assertIsSelected()
        MediaSort.entries.forEach { sort -> compose.onNodeWithText(sort.label).performScrollTo().assertExists() }
        compose.onNodeWithText(MediaSort.NameDescending.label).performScrollTo().performClick()
        compose.onNodeWithText("图片排序").assertDoesNotExist()
        assertEquals(listOf(GalleryEvent.SetArtistImageSort(MediaSort.NameDescending)), events.filterIsInstance<GalleryEvent.SetArtistImageSort>())
        compose.runOnIdle { assertEquals(MediaSort.NameDescending, current.value.browseOptions.artistImageSorts["artist"]) }
    }

    @Test fun outsideDismissKeepsThePreviouslySavedChoice() {
        show(initial().copy(dialog = GalleryDialog.ArtistImageSort,
            browseOptions = BrowseOptions(artistImageSorts = mapOf("artist" to MediaSort.AddedDescending))))
        compose.onNode(isDialog()).performTouchInput { click(Offset(1f, centerY)) }
        compose.onNodeWithText("图片排序").assertDoesNotExist()
        assertTrue(events.none { it is GalleryEvent.SetArtistImageSort })
        assertEquals(GalleryEvent.DismissDialog, events.last())
        compose.runOnIdle { assertEquals(MediaSort.AddedDescending, current.value.browseOptions.artistImageSorts["artist"]) }
    }

    @Test fun backDismissesThePopupWithoutChangingTheSort() {
        show(initial().copy(dialog = GalleryDialog.ArtistImageSort))
        pressBack()
        compose.onNodeWithText("图片排序").assertDoesNotExist()
        assertTrue(events.none { it is GalleryEvent.SetArtistImageSort })
        compose.runOnIdle { assertTrue(current.value.browseOptions.artistImageSorts.isEmpty()) }
    }

    @Test fun staleSortingDialogDoesNotAppearOutsideAllowedState() {
        show(initial().copy(dialog = GalleryDialog.ArtistImageSort, artistTab = ArtistTab.Collections))
        compose.onNodeWithText("图片排序").assertDoesNotExist()
        listOf(
            initial().copy(route = GalleryRoute.Unassigned),
            initial().copy(mediaSelectionMode = true),
            initial().copy(entitySelectionKind = EntitySelectionKind.Collections),
            initial().copy(isSaving = true),
            initial().copy(overlay = GalleryOverlay.CardViewer("image0")),
        ).forEach { state ->
            compose.runOnIdle { current.value = state.copy(dialog = GalleryDialog.ArtistImageSort) }
            compose.onNodeWithText("图片排序").assertDoesNotExist()
        }
    }

    @Test fun changingSortUsesAnIndependentScrollPositionAndReturnsToVisitedSort() {
        show()
        compose.onNodeWithTag("artistMediaList").performScrollToIndex(15)
        compose.onNodeWithContentDescription("图片排序").performClick()
        compose.runOnIdle { assertTrue(current.value.scrollPositions.getValue("artist:artist:Images:List:Default").index >= 15) }
        compose.onNodeWithText(MediaSort.NameDescending.label).performScrollTo().performClick()
        compose.onNodeWithText("图片 59.png").assertIsDisplayed()
        compose.onNodeWithTag("artistMediaList").performScrollToIndex(8)
        compose.onNodeWithContentDescription("图片排序").performClick()
        compose.runOnIdle { assertTrue(current.value.scrollPositions.getValue("artist:artist:Images:List:NameDescending").index >= 8) }
        compose.onNodeWithText(MediaSort.Default.label).performScrollTo().performClick()
        compose.onNodeWithText("图片 15.png").assertIsDisplayed()
        compose.onNodeWithText("图片 0.png").assertDoesNotExist()
    }

    private fun initial() = GalleryUiState(loadState = LoadState.Ready, mediaLoadState = LoadState.Ready,
        route = GalleryRoute.Artist("artist"), artistDetail = detail, mediaItems = images)

    private fun show(state: GalleryUiState = initial()) {
        compose.activity.setContent {
            current = remember { mutableStateOf(state) }
            val onEvent: (GalleryEvent) -> Unit = { event ->
                events.add(event)
                val old = current.value
                current.value = when (event) {
                    is GalleryEvent.OpenDialog -> old.copy(dialog = event.dialog)
                    GalleryEvent.DismissDialog -> old.copy(dialog = GalleryDialog.None)
                    is GalleryEvent.SetArtistImageSort -> old.copy(dialog = GalleryDialog.None,
                        mediaItems = sortArtistImages(images, event.sort),
                        browseOptions = old.browseOptions.copy(artistImageSorts = old.browseOptions.artistImageSorts + ("artist" to event.sort)))
                    is GalleryEvent.RememberScrollPosition -> old.copy(scrollPositions = old.scrollPositions +
                        (event.key to GalleryScrollPosition(event.index, event.offset)))
                    else -> old
                }
            }
            DTheme {
                if (current.value.route is GalleryRoute.Collection) CollectionScreen(current.value, 18.dp, null, onEvent)
                else ArtistDetailScreen(current.value, 18.dp, null, null, onEvent)
                GalleryDialogs(current.value, null, onEvent)
            }
        }
    }
}
