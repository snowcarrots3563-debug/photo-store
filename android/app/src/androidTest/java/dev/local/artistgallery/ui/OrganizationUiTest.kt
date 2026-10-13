package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic rows and events only: no real library, permissions, or files are changed. */
class OrganizationUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private lateinit var current: MutableState<GalleryUiState>
    private val events = mutableListOf<GalleryEvent>()
    private val artists = List(12) { ArtistSummary("artist$it", "画师 $it", 2, 3) }
    private val collections = List(12) { CollectionSummary("collection$it", "artist0", "作品集 $it", false, 3, 0) }
    private val detail = ArtistDetail(artists.first(), collections)

    @Test fun homeLongPressAndTapsSelectWhileTrashOnlyOpensConfirmation() {
        showHome()
        compose.onNodeWithTag("reorder:artist0").assertDoesNotExist()
        compose.onNodeWithText("画师 0").performTouchInput { longClick() }
        compose.onNodeWithText("已选择 1 项").assertIsDisplayed()
        compose.onNodeWithContentDescription("添加").assertDoesNotExist()
        compose.onNodeWithTag("reorder:artist0").assertIsDisplayed()
        compose.onNodeWithText("画师 1").performClick()
        compose.onNodeWithText("已选择 2 项").assertIsDisplayed()
        compose.onNodeWithContentDescription("删除所选画师").performClick()
        assertEquals(GalleryEvent.OpenDeleteSelectedEntities, events.last())
        assertTrue(events.none { it is GalleryEvent.OpenArtist || it == GalleryEvent.ConfirmDeleteSelectedEntities })
    }

    @Test fun deselectingAllKeepsModeAndDeepSelectionStillShowsTrash() {
        showHome()
        compose.onNodeWithText("画师 10").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithContentDescription("删除所选画师").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("画师 10").performClick()
        compose.onNodeWithText("已选择 0 项").assertIsDisplayed()
        compose.onNodeWithContentDescription("删除所选画师").assertIsNotEnabled()
        compose.onNodeWithContentDescription("退出选择").performClick()
        compose.onNodeWithContentDescription("添加").assertIsDisplayed()
        compose.onNodeWithTag("reorder:artist10").assertDoesNotExist()
        assertEquals(GalleryEvent.ExitEntitySelection, events.last())
    }

    @Test fun handleDragCommitsOrderWithoutOpeningOrSelectingTheArtist() {
        showHome()
        compose.onNodeWithTag("reorder:artist0").assertDoesNotExist()
        compose.onNodeWithText("画师 0").performTouchInput { longClick() }
        val first = compose.onNodeWithTag("reorder:artist0")
        val second = compose.onNodeWithTag("reorder:artist1")
        val distance = second.fetchSemanticsNode().positionInRoot.y - first.fetchSemanticsNode().positionInRoot.y
        first.performTouchInput {
            down(center)
            moveBy(Offset.Zero, delayMillis = 700)
            moveBy(Offset(0f, distance * 1.2f), delayMillis = 160)
            up()
        }
        compose.waitForIdle()
        val commits = events.filterIsInstance<GalleryEvent.ReorderArtists>()
        assertEquals(1, commits.size)
        assertEquals(listOf("artist1", "artist0"), commits.single().ids.take(2))
        assertEquals(1, events.filterIsInstance<GalleryEvent.StartEntitySelection>().size)
        assertEquals(setOf("artist0"), current.value.selectedEntityIds)
        assertTrue(events.none { it is GalleryEvent.ToggleEntitySelection || it is GalleryEvent.OpenArtist })
    }

    @Test fun collectionSelectionDisablesTabsAndCreationAndRequestsConfirmation() {
        show(GalleryUiState(loadState = LoadState.Ready, mediaLoadState = LoadState.Ready,
            artistDetail = detail, route = GalleryRoute.Artist("artist0"), artistTab = ArtistTab.Collections)) {
            ArtistDetailScreen(it, 18.dp, null, null, ::event)
        }
        compose.onNodeWithTag("reorder:collection0").assertDoesNotExist()
        compose.onNodeWithText("作品集 0").performTouchInput { longClick() }
        compose.onNodeWithTag("reorder:collection0").assertIsDisplayed()
        compose.onNodeWithText("已选择 1 项").assertIsDisplayed()
        compose.onNodeWithText("全部图片").assertIsNotEnabled()
        compose.onNodeWithText("新建作品集").assertIsNotEnabled()
        compose.onNodeWithText("作品集 1").performClick()
        compose.onNodeWithContentDescription("删除所选作品集").performClick()
        assertEquals(GalleryEvent.OpenDeleteSelectedEntities, events.last())
        assertTrue(events.none { it is GalleryEvent.OpenCollection || it == GalleryEvent.ConfirmDeleteSelectedEntities })
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("reorder:collection0").assertDoesNotExist()
    }

    @Test fun collectionHandleDragCommitsTheArtistScopedOrder() {
        show(GalleryUiState(loadState = LoadState.Ready, mediaLoadState = LoadState.Ready,
            artistDetail = detail, route = GalleryRoute.Artist("artist0"), artistTab = ArtistTab.Collections)) {
            ArtistDetailScreen(it, 18.dp, null, null, ::event)
        }
        compose.onNodeWithTag("reorder:collection0").assertDoesNotExist()
        compose.onNodeWithText("作品集 0").performTouchInput { longClick() }
        val first = compose.onNodeWithTag("reorder:collection0")
        val second = compose.onNodeWithTag("reorder:collection1")
        val distance = second.fetchSemanticsNode().positionInRoot.y - first.fetchSemanticsNode().positionInRoot.y
        first.performTouchInput {
            down(center)
            moveBy(Offset.Zero, delayMillis = 700)
            moveBy(Offset(0f, distance * 1.2f), delayMillis = 160)
            up()
        }
        compose.waitForIdle()
        val commits = events.filterIsInstance<GalleryEvent.ReorderCollections>()
        assertEquals(1, commits.size)
        assertEquals("artist0", commits.single().artistId)
        assertEquals(listOf("collection1", "collection0"), commits.single().ids.take(2))
        assertEquals(1, events.filterIsInstance<GalleryEvent.StartEntitySelection>().size)
        assertEquals(setOf("collection0"), current.value.selectedEntityIds)
        assertTrue(events.none { it is GalleryEvent.ToggleEntitySelection || it is GalleryEvent.OpenCollection })
    }

    @Test fun imageViewCapsuleSendsCycleInsteadOfOpeningTheOldMenu() {
        show(GalleryUiState(loadState = LoadState.Ready, mediaLoadState = LoadState.Ready,
            artistDetail = detail, route = GalleryRoute.Artist("artist0"))) {
            ArtistDetailScreen(it, 18.dp, null, null, ::event)
        }
        compose.onNodeWithText("切换视图").assertIsDisplayed().performClick()
        assertEquals(GalleryEvent.CycleImageLayout, events.last())
        assertTrue(events.none { it == GalleryEvent.OpenDialog(GalleryDialog.ImageDisplayMenu) })
    }

    @Test fun noticeCloseHidesTheEntireNotice() {
        show(GalleryUiState(loadState = LoadState.Ready, route = GalleryRoute.Manage)) { ManageScreen(it, 18.dp, ::event) }
        compose.onNodeWithContentDescription("关闭保存提示").performClick()
        assertEquals(GalleryEvent.DismissStorageNotice, events.last())
        compose.onNodeWithText("保存在这台设备上").assertDoesNotExist()
        compose.onNodeWithContentDescription("关闭保存提示").assertDoesNotExist()
    }

    @Test fun collectionImageTrashRequestsGlobalDeleteConfirmation() {
        val image = MediaAssetSummary("image", "图片.png", MediaType.IMAGE, 6000, 0, "图片.png", "key")
        show(GalleryUiState(loadState = LoadState.Ready, mediaLoadState = LoadState.Ready,
            route = GalleryRoute.Collection("collection0", "artist0"), artistDetail = detail,
            collection = collections.first(), mediaItems = listOf(image))) { CollectionScreen(it, 18.dp, null, ::event) }
        compose.onNodeWithContentDescription("删除图片").performClick()
        assertEquals(GalleryEvent.OpenDeleteMedia("image"), events.last())
        assertTrue(events.none { it is GalleryEvent.OpenRemoveMedia || it == GalleryEvent.ConfirmDeleteMedia })
    }

    private fun showHome() = show(GalleryUiState(loadState = LoadState.Ready, artists = artists)) {
        ArtistHomeScreen(it, 18.dp, null, {}, null, null, ::event)
    }

    private fun show(initial: GalleryUiState, screen: @Composable (GalleryUiState) -> Unit) {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            current = remember { mutableStateOf(initial) }
            DTheme { screen(current.value) }
        } }
        compose.waitForIdle()
    }

    private fun event(event: GalleryEvent) {
        events.add(event)
        when (event) {
            is GalleryEvent.StartEntitySelection -> current.value = current.value.copy(entitySelectionKind = event.kind, selectedEntityIds = setOf(event.id))
            is GalleryEvent.ToggleEntitySelection -> current.value = current.value.copy(selectedEntityIds =
                if (event.id in current.value.selectedEntityIds) current.value.selectedEntityIds - event.id else current.value.selectedEntityIds + event.id)
            GalleryEvent.ExitEntitySelection -> current.value = current.value.copy(entitySelectionKind = null, selectedEntityIds = emptySet())
            GalleryEvent.DismissStorageNotice -> current.value = current.value.copy(browseOptions = current.value.browseOptions.copy(storageNoticeDismissed = true))
            else -> Unit
        }
    }
}
