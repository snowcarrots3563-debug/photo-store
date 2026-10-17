package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
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

/** Synthetic browsing state and gestures only; no gallery, files, or preferences are accessed. */
class ScrollToTopUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private lateinit var current: MutableState<GalleryUiState>
    private lateinit var visible: MutableState<Boolean>
    private val events = mutableListOf<GalleryEvent>()
    private val images = List(120) { index ->
        MediaAssetSummary("image$index", "图片 $index.png", MediaType.IMAGE, 6000L + index, index, "$index.png", "key$index", 1_780_000_000_000L + index)
    }
    private val collections = List(40) { CollectionSummary("collection$it", "artist", "作品集 $it", false, images.size, 0) }
    private val detail = ArtistDetail(ArtistSummary("artist", "画师样本", collections.size, images.size), collections)
    private val artists = List(40) { ArtistSummary("artist$it", "画师 $it", 1, 2) }

    @Test fun homeTitleAndItsBlankAreaReturnToSearchWithoutBusinessEvents() {
        show(homeState())
        homeDeep()
        title("画师").performTouchInput { doubleClick() }
        assertTop("artistHomeList")
        compose.onNodeWithTag("artistSearch").assertIsDisplayed()
        homeDeep()
        title("画师").performTouchInput { doubleClick(Offset(width - 4f, centerY)) }
        assertTop("artistHomeList")
        assertTrue(businessEvents().isEmpty())
        assertEquals(artists, value().artists)
    }

    @Test fun homeSingleTapLongPressAndSeparatedTapsDoNotReturnToTop() {
        show(homeState())
        homeDeep()
        val before = position("artistHomeList")
        title("画师").performTouchInput {
            click()
            advanceEventTime(1_000)
            click()
            advanceEventTime(1_000)
            longClick()
        }
        assertEquals(before, position("artistHomeList"), 1f)
        assertTrue(businessEvents().isEmpty())
    }

    @Test fun homeSelectionSurvivesReturnAndHandlesRemainAvailable() {
        show(homeState().copy(entitySelectionKind = EntitySelectionKind.Artists, selectedEntityIds = setOf("artist20")))
        homeDeep()
        title("已选择 1 项").performTouchInput { doubleClick() }
        assertTop("artistHomeList")
        assertEquals(setOf("artist20"), value().selectedEntityIds)
        compose.onNodeWithTag("reorder:artist0").assertIsDisplayed()
        compose.onNodeWithContentDescription("删除所选画师").performTouchInput { doubleClick() }
        assertEquals(listOf(GalleryEvent.OpenDeleteSelectedEntities, GalleryEvent.OpenDeleteSelectedEntities), businessEvents())
    }

    @Test fun artistListScrollsFirstThenGraduallyExpandsTabs() {
        show(artistState())
        val expandedHeight = height("artistTabs")
        artistDeep()
        compose.mainClock.autoAdvance = false
        title("画师样本").performTouchInput { doubleClick() }
        advance(64)
        assertTrue(position("artistMediaList") > 0f)
        assertEquals(0f, height("artistTabs"), 1f)
        framesUntil { position("artistMediaList") == 0f }
        advance(96)
        assertTrue(height("artistTabs") > 0f)
        assertTrue(height("artistTabs") < expandedHeight)
        settle()
        assertTop("artistMediaList")
        assertEquals(expandedHeight, height("artistTabs"), 1f)
        compose.onNodeWithText(images.first().name).assertIsDisplayed()
        assertTrue(businessEvents().isEmpty())
    }

    @Test fun bothGridSizesReturnWithDateSectionsOrCaptionsAndKeepTheirSort() {
        show(artistState(ArtistImageLayout.Large))
        verifyGridReturn()
        compose.onNodeWithTag("artistDateHeader:image0").assertIsDisplayed()
        compose.runOnIdle {
            current.value = current.value.copy(
                browseOptions = current.value.browseOptions.copy(imageLayout = ArtistImageLayout.Medium,
                    artistImageSorts = mapOf("artist" to MediaSort.NameAscending))).withPreparedMedia(sortArtistImages(images, MediaSort.NameAscending))
        }
        verifyGridReturn()
        compose.onNodeWithTag("artistDate:image0").assertIsDisplayed()
        assertEquals(sortArtistImages(images, MediaSort.NameAscending), value().mediaItems)
    }

    @Test fun artistCollectionsTabReturnsWithoutReorderingCollections() {
        show(artistState().copy(artistTab = ArtistTab.Collections))
        drag("artistMediaList", -height("artistMediaList") * .7f)
        compose.onNodeWithTag("collectionRow:collection30").performScrollTo()
        assertTrue(position("artistMediaList") > 0f)
        title("画师样本").performTouchInput { doubleClick() }
        assertTop("artistMediaList")
        compose.onNodeWithTag("collectionRow:collection0").assertIsDisplayed()
        compose.onNodeWithTag("artistTabs").assertIsDisplayed()
        assertEquals(collections, value().artistDetail!!.collections)
        assertTrue(businessEvents().isEmpty())
    }

    @Test fun artistMediaSelectionSurvivesTitleDoubleTap() {
        show(artistState().copy(mediaSelectionMode = true, selectedMediaIds = setOf("image35")))
        artistDeep()
        title("已选择 1 项").performTouchInput { doubleClick() }
        assertTop("artistMediaList")
        assertEquals(setOf("image35"), value().selectedMediaIds)
        compose.onNodeWithTag("artistSelectedRow:image0").assertIsDisplayed()
        assertTrue(businessEvents().isEmpty())
    }

    @Test fun artistCollectionSelectionKeepsHandlesAndSelectedIds() {
        show(artistState().copy(artistTab = ArtistTab.Collections, entitySelectionKind = EntitySelectionKind.Collections,
            selectedEntityIds = setOf("collection20")))
        drag("artistMediaList", -height("artistMediaList") * .7f)
        compose.onNodeWithTag("collectionRow:collection30").performScrollTo()
        title("已选择 1 项").performTouchInput { doubleClick() }
        assertTop("artistMediaList")
        assertEquals(setOf("collection20"), value().selectedEntityIds)
        compose.onNodeWithTag("reorder:collection0").assertIsDisplayed()
        assertTrue(businessEvents().isEmpty())
    }

    @Test fun userDraggingStopsAnArtistReturnBeforeItReachesTheTop() {
        show(artistState())
        artistDeep()
        compose.mainClock.autoAdvance = false
        title("画师样本").performTouchInput { doubleClick() }
        advance(64)
        assertTrue(position("artistMediaList") > 0f)
        drag("artistMediaList", -100f)
        val stopped = position("artistMediaList")
        settle()
        assertTrue(stopped > 0f)
        assertEquals(stopped, position("artistMediaList"), 1f)
        assertEquals(0f, height("artistTabs"), 1f)
    }

    @Test fun downwardDragCancelsTabExpansionBeforeTheEarlyReturn() {
        show(artistState())
        val expandedHeight = height("artistTabs")
        artistDeep()
        compose.mainClock.autoAdvance = false
        title("画师样本").performTouchInput { doubleClick() }
        framesUntil { position("artistMediaList") == 0f }
        advance(64)
        val partial = height("artistTabs")
        assertTrue(partial > 0f && partial < expandedHeight)
        drag("artistMediaList", 60f)
        val stoppedHeight = height("artistTabs")
        assertTrue(stoppedHeight < expandedHeight)
        settle()
        assertEquals(stoppedHeight, height("artistTabs"), 1f)
        assertTop("artistMediaList")
    }

    @Test fun repeatedDoubleTapsAndUpdatedCallbackDoNotLoseTheGesture() {
        show(homeState())
        homeDeep()
        compose.mainClock.autoAdvance = false
        title("画师").performTouchInput { click() }
        compose.runOnIdle { current.value = current.value.copy(isSaving = true) }
        frame()
        title("画师").performTouchInput { advanceEventTime(100); click() }
        advance(64)
        assertTrue(position("artistHomeList") > 0f)
        title("画师").performTouchInput { doubleClick() }
        settle()
        assertTop("artistHomeList")
        assertTrue(businessEvents().isEmpty())
    }

    @Test fun changingArtistSortLayoutOrTabCancelsTheOldJobAndKeepsEachKey() {
        show(artistState())
        artistDeep()
        compose.mainClock.autoAdvance = false
        title("画师样本").performTouchInput { doubleClick() }
        advance(64)
        val newKey = "artist:artist:Images:Large:NameDescending"
        compose.runOnIdle {
            current.value = current.value.copy(
                browseOptions = current.value.browseOptions.copy(imageLayout = ArtistImageLayout.Large,
                    artistImageSorts = mapOf("artist" to MediaSort.NameDescending)),
                scrollPositions = current.value.scrollPositions + (newKey to GalleryScrollPosition(15, 0))).withPreparedMedia(sortArtistImages(images, MediaSort.NameDescending))
        }
        frame()
        val restored = position("artistMediaList")
        assertTrue(restored >= 15f)
        settle()
        assertEquals(restored, position("artistMediaList"), 1f)
        assertEquals(0f, height("artistTabs"), 1f)
        compose.runOnIdle { current.value = current.value.copy(artistTab = ArtistTab.Collections) }
        frame()
        assertTop("artistMediaList")
        compose.onNodeWithTag("collectionRow:collection0").assertIsDisplayed()
        assertTrue(value().scrollPositions.containsKey(newKey))
    }

    @Test fun changingRouteDisposesTheOldReturnWithoutMovingTheNewPage() {
        show(artistState())
        artistDeep()
        compose.mainClock.autoAdvance = false
        title("画师样本").performTouchInput { doubleClick() }
        advance(64)
        compose.runOnIdle {
            current.value = current.value.copy(route = GalleryRoute.Collection("collection0", "artist"), collection = collections.first(),
                scrollPositions = current.value.scrollPositions + ("collection:collection0" to GalleryScrollPosition(35, 0)))
        }
        frame()
        val restored = position("collectionMediaList")
        assertTrue(restored >= 35f)
        settle()
        assertEquals(restored, position("collectionMediaList"), 1f)
        assertTrue(value().scrollPositions.containsKey("artist:artist:Images:List:Default"))
    }

    @Test fun openingAnOverlayStopsAllThreeBrowsingPagesWithoutResumingOnClose() {
        show(homeState())
        homeDeep()
        verifyOverlayCancellation("画师", "artistHomeList")
        compose.runOnIdle { current.value = artistState() }
        frame()
        artistDeep()
        verifyOverlayCancellation("画师样本", "artistMediaList")
        assertEquals(0f, height("artistTabs"), 1f)
        compose.runOnIdle { current.value = collectionState() }
        frame()
        list("collectionMediaList").performScrollToIndex(50)
        verifyOverlayCancellation("作品集 0", "collectionMediaList")
    }

    @Test fun openingAnOverlayAlsoStopsTheArtistTabExpansion() {
        show(artistState())
        val expandedHeight = height("artistTabs")
        artistDeep()
        compose.mainClock.autoAdvance = false
        title("画师样本").performTouchInput { doubleClick() }
        framesUntil { position("artistMediaList") == 0f }
        advance(64)
        compose.runOnIdle { current.value = current.value.copy(overlay = GalleryOverlay.CardViewer("image0")) }
        frame()
        val stoppedHeight = height("artistTabs")
        assertTrue(stoppedHeight > 0f && stoppedHeight < expandedHeight)
        settle()
        assertEquals(stoppedHeight, height("artistTabs"), 1f)
        assertTop("artistMediaList")
    }

    @Test fun collectionTitleIsStickyInNormalAndSelectionModesAndReturnsToTop() {
        show(collectionState())
        list("collectionMediaList").performScrollToIndex(50)
        title("作品集 0").assertIsDisplayed().performTouchInput { doubleClick() }
        assertTop("collectionMediaList")
        compose.onNodeWithText(images.first().name).assertIsDisplayed()
        compose.runOnIdle { current.value = current.value.copy(mediaSelectionMode = true, selectedMediaIds = setOf("image50")) }
        list("collectionMediaList").performScrollToIndex(50)
        compose.onNodeWithTag("collectionSelectionHeader").assertIsDisplayed()
        title("已选择 1 项").assertIsDisplayed().performTouchInput { doubleClick() }
        assertTop("collectionMediaList")
        assertEquals(setOf("image50"), value().selectedMediaIds)
        assertTrue(businessEvents().isEmpty())
    }

    @Test fun collectionHeaderKeepsMediaIndicesAndBothKindsOfScrollKeys() {
        show(collectionState().copy(scrollPositions = mapOf("collection:collection0" to GalleryScrollPosition(32, 0))))
        compose.onNodeWithText(images[30].name).assertIsDisplayed()
        val before = position("collectionMediaList")
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { visible.value = true }
        assertEquals(before, position("collectionMediaList"), 1f)
        assertEquals(GalleryScrollPosition(32, 0), value().scrollPositions["collection:collection0"])
        val customKey = "collection:collection0:SizeDescending"
        compose.runOnIdle {
            current.value = current.value.copy(
                browseOptions = current.value.browseOptions.copy(collectionMediaSorts = mapOf("collection0" to MediaSort.SizeDescending)),
                scrollPositions = current.value.scrollPositions + (customKey to GalleryScrollPosition(22, 0))).withPreparedMedia(sortCollectionMedia(images, MediaSort.SizeDescending))
        }
        title("作品集 0").performTouchInput { doubleClick() }
        assertTop("collectionMediaList")
        compose.runOnIdle { visible.value = false }
        assertEquals(GalleryScrollPosition(), value().scrollPositions[customKey])
        assertEquals(GalleryScrollPosition(32, 0), value().scrollPositions["collection:collection0"])
    }

    @Test fun navigationMenuSortViewAndTabButtonsDoNotTriggerTitleReturn() {
        show(artistState())
        artistDeep()
        val before = position("artistMediaList")
        compose.onNodeWithContentDescription("返回").performTouchInput { doubleClick() }
        compose.onNodeWithContentDescription("更多操作").performTouchInput { doubleClick() }
        compose.onNodeWithContentDescription("图片排序").performTouchInput { doubleClick() }
        compose.onNodeWithText("切换视图").performTouchInput { doubleClick() }
        assertEquals(before, position("artistMediaList"), 1f)
        assertEquals(2, businessEvents().count { it == GalleryEvent.NavigateBack })
        assertEquals(2, businessEvents().count { it == GalleryEvent.OpenDialog(GalleryDialog.ArtistMenu) })
        assertEquals(2, businessEvents().count { it == GalleryEvent.OpenDialog(GalleryDialog.ArtistImageSort) })
        assertEquals(2, businessEvents().count { it == GalleryEvent.CycleImageLayout })
        title("画师样本").performTouchInput { doubleClick() }
        assertTop("artistMediaList")
        compose.onNodeWithText("作品集").performTouchInput { doubleClick() }
        assertEquals(2, businessEvents().count { it == GalleryEvent.SwitchArtistTab(ArtistTab.Collections) })
        compose.runOnIdle { current.value = collectionState() }
        list("collectionMediaList").performScrollToIndex(40)
        val collectionBefore = position("collectionMediaList")
        compose.onNodeWithContentDescription("返回").performTouchInput { doubleClick() }
        compose.onNodeWithContentDescription("更多操作").performTouchInput { doubleClick() }
        assertEquals(collectionBefore, position("collectionMediaList"), 1f)
        assertEquals(2, businessEvents().count { it == GalleryEvent.OpenDialog(GalleryDialog.CollectionMenu) })
    }

    @Test fun rowLongPressAfterReturningStillEntersSelection() {
        show(homeState())
        homeDeep()
        title("画师").performTouchInput { doubleClick() }
        compose.onNodeWithTag("artistRow:artist0").performTouchInput { longClick() }
        assertEquals(setOf("artist0"), value().selectedEntityIds)
        compose.onNodeWithTag("reorder:artist0").assertIsDisplayed()
        compose.runOnIdle { current.value = artistState() }
        artistDeep()
        title("画师样本").performTouchInput { doubleClick() }
        compose.onNodeWithText(images.first().name).performTouchInput { longClick() }
        assertEquals(setOf("image0"), value().selectedMediaIds)
        assertTrue(value().mediaSelectionMode)
    }

    @Test fun emptyTopAndLoadingOrFailedScreensHaveNoBusinessSideEffects() {
        show(homeState().copy(artists = emptyList()))
        title("画师").performTouchInput { doubleClick() }
        compose.runOnIdle { current.value = artistState().copy().withPreparedMedia(emptyList()) }
        title("画师样本").performTouchInput { doubleClick() }
        compose.onNodeWithText("还没有图片").assertIsDisplayed()
        compose.runOnIdle { current.value = current.value.copy(mediaLoadState = LoadState.Loading) }
        title("画师样本").performTouchInput { doubleClick() }
        compose.runOnIdle { current.value = current.value.copy(mediaLoadState = LoadState.Failed) }
        title("画师样本").performTouchInput { doubleClick() }
        compose.runOnIdle { current.value = collectionState().copy().withPreparedMedia(emptyList()) }
        title("作品集 0").performTouchInput { doubleClick() }
        assertTop("collectionMediaList")
        assertTrue(businessEvents().isEmpty())
    }

    private fun title(text: String) = compose.onNodeWithText(text)
    private fun list(tag: String) = compose.onNodeWithTag(tag)
    private fun height(tag: String) = list(tag).fetchSemanticsNode().size.height.toFloat()
    private fun position(tag: String) = list(tag).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun assertTop(tag: String) = assertEquals(0f, position(tag), .1f)
    private fun businessEvents() = events.filterNot { it is GalleryEvent.RememberScrollPosition }
    private fun value(): GalleryUiState {
        lateinit var state: GalleryUiState
        compose.runOnIdle { state = current.value }
        return state
    }

    private fun homeState() = GalleryUiState(loadState = LoadState.Ready, route = GalleryRoute.Home, artists = artists)
    private fun artistState(layout: ArtistImageLayout = ArtistImageLayout.List, sort: MediaSort = MediaSort.Default) = GalleryUiState(
        loadState = LoadState.Ready, mediaLoadState = LoadState.Ready, route = GalleryRoute.Artist("artist"), artistDetail = detail,
         browseOptions = BrowseOptions(imageLayout = layout, artistImageSorts = mapOf("artist" to sort), swipeDelete = false),
    ).withPreparedMedia(sortArtistImages(images, sort))
    private fun collectionState() = artistState().copy(route = GalleryRoute.Collection("collection0", "artist"), collection = collections.first())
    private fun homeDeep() {
        list("artistHomeList").performScrollToIndex(32)
        assertTrue(position("artistHomeList") > 0f)
    }
    private fun artistDeep(index: Int = 60) {
        drag("artistMediaList", -height("artistMediaList") * .7f)
        list("artistMediaList").performScrollToIndex(index)
        assertEquals(0f, height("artistTabs"), 1f)
    }
    private fun verifyGridReturn() {
        artistDeep(15)
        title("画师样本").performTouchInput { doubleClick() }
        assertTop("artistMediaList")
        compose.onNodeWithTag("artistTile:image0").assertIsDisplayed()
        compose.onNodeWithTag("artistTabs").assertIsDisplayed()
        assertTrue(businessEvents().isEmpty())
    }
    private fun verifyOverlayCancellation(titleText: String, tag: String) {
        compose.mainClock.autoAdvance = false
        title(titleText).performTouchInput { doubleClick() }
        advance(64)
        compose.runOnIdle { current.value = current.value.copy(overlay = GalleryOverlay.CardViewer("image0")) }
        frame()
        val stopped = position(tag)
        assertTrue(stopped > 0f)
        settle()
        assertEquals(stopped, position(tag), 1f)
        compose.runOnIdle { current.value = current.value.copy(overlay = GalleryOverlay.None) }
        frame()
        assertEquals(stopped, position(tag), 1f)
    }
    private fun drag(tag: String, distance: Float) {
        list(tag).performTouchInput { down(center) }
        frame()
        list(tag).performTouchInput { moveBy(Offset(0f, distance), delayMillis = 300) }
        frame()
        list(tag).performTouchInput { moveBy(Offset.Zero, delayMillis = 400); up() }
        frame()
    }
    private fun frame() { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
    private fun advance(millis: Long) { compose.mainClock.advanceTimeBy(millis); compose.waitForIdle() }
    private fun settle() = advance(10_000)
    private fun framesUntil(condition: () -> Boolean) {
        repeat(600) { frame(); if (condition()) return }
        fail("Animation did not reach the expected stage")
    }

    private fun show(initial: GalleryUiState) {
        compose.activity.setContent {
            current = remember { mutableStateOf(initial) }
            visible = remember { mutableStateOf(true) }
            val holder = rememberSaveableStateHolder()
            val state = current.value
            val event: (GalleryEvent) -> Unit = { change ->
                events += change
                current.value = when (change) {
                    is GalleryEvent.RememberScrollPosition -> current.value.copy(scrollPositions = current.value.scrollPositions +
                        (change.key to GalleryScrollPosition(change.index, change.offset)))
                    is GalleryEvent.StartMediaSelection -> current.value.copy(mediaSelectionMode = true, selectedMediaIds = setOf(change.assetId))
                    is GalleryEvent.StartEntitySelection -> current.value.copy(entitySelectionKind = change.kind, selectedEntityIds = setOf(change.id))
                    else -> current.value
                }
            }
            val routeKey = when (val route = state.route) {
                GalleryRoute.Home -> "home"
                is GalleryRoute.Artist -> "artist:${route.id}:${state.artistTab}"
                is GalleryRoute.Collection -> "collection:${route.id}"
                else -> error("Unsupported fixture route")
            }
            DTheme {
                if (visible.value) holder.SaveableStateProvider(routeKey) {
                    Box(Modifier.fillMaxSize()) {
                        when (state.route) {
                            GalleryRoute.Home -> ArtistHomeScreen(state, 18.dp, null, {}, null, null, event)
                            is GalleryRoute.Artist -> ArtistDetailScreen(state, 18.dp, null, null, event)
                            is GalleryRoute.Collection -> CollectionScreen(state, 18.dp, null, event)
                            else -> Unit
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
