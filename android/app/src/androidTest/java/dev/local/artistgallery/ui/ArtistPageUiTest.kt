package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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

/** Local UI state only; no gallery database, files, or device preferences are written. */
class ArtistPageUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private val events = mutableListOf<GalleryEvent>()
    private lateinit var current: MutableState<GalleryUiState>
    private lateinit var visible: MutableState<Boolean>
    private val images = List(60) { index ->
        MediaAssetSummary("image$index", "图片 $index.png", MediaType.IMAGE, 6000, index, "$index.png", "key$index")
    }
    private val collections = List(35) { index -> CollectionSummary("collection$index", "artist", "作品集 $index", false, 60, 0) }
    private val detail = ArtistDetail(ArtistSummary("artist", "画师样本", 35, 60), collections)

    @Test fun defaultListKeepsFilenamesAndSelectorOpensDisplayChoices() {
        show(artistState())
        compose.onNodeWithText(images.first().name).assertIsDisplayed()
        compose.onNodeWithTag("artistTile:image0").assertDoesNotExist()
        compose.onNodeWithText("切换视图").performClick()
        assertTrue(events[events.lastIndex - 1] is GalleryEvent.RememberScrollPosition)
        assertEquals(GalleryEvent.CycleImageLayout, events.last())
    }

    @Test fun gridsHaveSquareImageOnlyCellsAndDifferentThumbnailSizes() {
        show(artistState(ArtistImageLayout.Medium))
        compose.onNodeWithText(images.first().name).assertDoesNotExist()
        val medium = compose.onNodeWithTag("artistTile:image0").fetchSemanticsNode()
        val fifth = compose.onNodeWithTag("artistTile:image4").fetchSemanticsNode()
        assertEquals(medium.size.width, medium.size.height)
        assertEquals(medium.positionInRoot.y, fifth.positionInRoot.y, 1f)
        compose.onNodeWithTag("artistTile:image2").performClick()
        val openIndex = events.indexOfLast { it is GalleryEvent.OpenMedia }
        assertTrue(events[openIndex - 1] is GalleryEvent.RememberScrollPosition)
        assertEquals(GalleryEvent.OpenMedia("image2"), events.last { it !is GalleryEvent.RememberScrollPosition })
        compose.runOnIdle {
            current.value = current.value.copy(browseOptions = BrowseOptions(imageLayout = ArtistImageLayout.Large))
            visible.value = true
        }
        val large = compose.onNodeWithTag("artistTile:image0").fetchSemanticsNode()
        val fourth = compose.onNodeWithTag("artistTile:image3").fetchSemanticsNode()
        assertEquals(large.size.width, large.size.height)
        assertTrue(large.size.width > medium.size.width)
        assertTrue(fourth.positionInRoot.y > large.positionInRoot.y)
        compose.onNodeWithText(images.first().name).assertDoesNotExist()
    }

    @Test fun artistImageReturnRestoresPositionAfterBothLoadsFinish() {
        show(artistState())
        compose.onNodeWithTag("artistMediaList").performScrollToIndex(15)
        val target = compose.onNodeWithText("图片 15.png")
        val before = target.fetchSemanticsNode().positionInRoot.y
        target.performClick()
        assertTrue(savedPosition("artist:artist:Images:List:Default").index >= 15)
        restoreAfterLoading("artistMediaList")
        compose.onNodeWithText("图片 15.png").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithText("图片 15.png").fetchSemanticsNode().positionInRoot.y, 1f)
        compose.onNodeWithText(images.first().name).assertDoesNotExist()
    }

    @Test fun collectionImageReturnRestoresPositionAfterBothLoadsFinish() {
        show(artistState().copy(route = GalleryRoute.Collection("collection0", "artist"), collection = collections.first()))
        compose.onNodeWithTag("collectionMediaList").performScrollToIndex(17)
        val target = compose.onNodeWithText("图片 15.png")
        val before = target.fetchSemanticsNode().positionInRoot.y
        target.performClick()
        assertTrue(savedPosition("collection:collection0").index >= 17)
        restoreAfterLoading("collectionMediaList")
        compose.onNodeWithText("图片 15.png").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithText("图片 15.png").fetchSemanticsNode().positionInRoot.y, 1f)
    }

    @Test fun returningFromCollectionKeepsItsLocationInArtistCollectionTab() {
        show(artistState().copy(artistTab = ArtistTab.Collections))
        val target = compose.onNodeWithText("作品集 20")
        target.performScrollTo().assertIsDisplayed()
        val before = target.fetchSemanticsNode().positionInRoot.y
        target.performClick()
        assertEquals(GalleryEvent.OpenCollection("collection20", "artist"), events.last { it !is GalleryEvent.RememberScrollPosition })
        assertTrue(savedPosition("artist:artist:Collections:List").offset > 0)
        restoreAfterLoading("artistMediaList")
        compose.onNodeWithText("作品集 20").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithText("作品集 20").fetchSemanticsNode().positionInRoot.y, 1f)
    }

    private fun artistState(layout: ArtistImageLayout = ArtistImageLayout.List) = GalleryUiState(
        loadState = LoadState.Ready, mediaLoadState = LoadState.Ready,
        route = GalleryRoute.Artist("artist"), artistDetail = detail, mediaItems = images,
        browseOptions = BrowseOptions(imageLayout = layout),
    )

    private fun savedPosition(key: String): GalleryScrollPosition {
        lateinit var position: GalleryScrollPosition
        compose.runOnIdle { position = current.value.scrollPositions.getValue(key) }
        return position
    }

    private fun restoreAfterLoading(listTag: String) {
        compose.runOnIdle {
            current.value = current.value.copy(loadState = LoadState.Loading, mediaLoadState = LoadState.Loading, mediaItems = emptyList())
            visible.value = true
        }
        compose.onNodeWithTag(listTag).assertDoesNotExist()
        compose.runOnIdle { current.value = current.value.copy(loadState = LoadState.Ready) }
        compose.onNodeWithTag(listTag).assertDoesNotExist()
        compose.runOnIdle { current.value = current.value.copy(mediaLoadState = LoadState.Ready, mediaItems = images) }
        compose.onNodeWithTag(listTag).assertExists()
    }

    private fun show(initial: GalleryUiState) {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            current = remember { mutableStateOf(initial) }
            visible = remember { mutableStateOf(true) }
            val onEvent: (GalleryEvent) -> Unit = { event ->
                events.add(event)
                when (event) {
                    is GalleryEvent.RememberScrollPosition -> current.value = current.value.copy(
                        scrollPositions = current.value.scrollPositions + (event.key to GalleryScrollPosition(event.index, event.offset)),
                    )
                    is GalleryEvent.OpenMedia, is GalleryEvent.OpenCollection -> visible.value = false
                    else -> Unit
                }
            }
            DTheme {
                val holder = rememberSaveableStateHolder()
                if (visible.value) {
                    holder.SaveableStateProvider("browse") {
                    if (current.value.route is GalleryRoute.Artist) ArtistDetailScreen(current.value, 18.dp, null, null, onEvent)
                    else CollectionScreen(current.value, 18.dp, null, onEvent)
                    }
                }
            }
        } }
    }
}
