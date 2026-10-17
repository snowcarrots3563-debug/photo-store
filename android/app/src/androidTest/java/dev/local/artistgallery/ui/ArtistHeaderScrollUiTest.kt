package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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

/** Scroll gestures over synthetic content; no gallery database, media, or preferences are changed. */
class ArtistHeaderScrollUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private val events = mutableListOf<GalleryEvent>()
    private lateinit var current: MutableState<GalleryUiState>
    private lateinit var visible: MutableState<Boolean>
    private val images = List(100) { index ->
        MediaAssetSummary("image$index", "$index.png", MediaType.IMAGE, 6000, index, "$index.png", "key$index")
    }
    private val collections = List(40) { index -> CollectionSummary("collection$index", "artist", "作品集 $index", false, 5, 0) }
    private val detail = ArtistDetail(ArtistSummary("artist", "画师样本", collections.size, images.size), collections)

    @Test fun headerAndSectionStayVisibleWhileOnlyTabsCollapse() {
        show()
        val headerY = y("artistHeader")
        val summaryY = y("artistSummary")
        val sectionY = y("artistSection")
        val tabsHeight = height("artistTabs")
        scrollUp()
        compose.onNodeWithTag("artistTabs").assertIsNotDisplayed()
        assertEquals(headerY, y("artistHeader"), 1f)
        assertEquals(summaryY, y("artistSummary"), 1f)
        assertEquals(sectionY - tabsHeight, y("artistSection"), 1f)
        val collapsedSectionY = y("artistSection")
        scrollUp()
        assertEquals(headerY, y("artistHeader"), 1f)
        assertEquals(summaryY, y("artistSummary"), 1f)
        assertEquals(collapsedSectionY, y("artistSection"), 1f)
        compose.onNodeWithText("切换视图").assertIsDisplayed().performClick()
        assertEquals(GalleryEvent.CycleImageLayout, events.last())
        compose.onNodeWithContentDescription("更多操作").assertIsDisplayed().performClick()
        assertEquals(GalleryEvent.OpenDialog(GalleryDialog.ArtistMenu), events.last())
        compose.onNodeWithContentDescription("返回").assertIsDisplayed().performClick()
        assertEquals(GalleryEvent.NavigateBack, events.last())
    }

    @Test fun downwardScrollingOnlyRevealsTabsAfterTheListReachesItsTop() {
        show()
        val expandedHeight = height("artistTabs")
        scrollUp()
        list().performScrollToIndex(40)
        val before = scrollPosition()
        dragList(height("artistMediaList") * .25f)
        assertTrue(scrollPosition() < before)
        assertTrue(scrollPosition() > 0f)
        compose.onNodeWithTag("artistTabs").assertIsNotDisplayed()
        list().performScrollToIndex(0)
        compose.onNodeWithTag("artistTabs").assertIsNotDisplayed()
        dragList(expandedHeight + with(compose.density) { 40.dp.toPx() })
        compose.onNodeWithTag("artistTabs").assertIsDisplayed()
        assertEquals(expandedHeight, height("artistTabs"), 1f)
        compose.onNodeWithText("全部图片").assertIsDisplayed()
        compose.onNodeWithText(images.first().name).assertIsDisplayed()
        assertTrue(events.none { it is GalleryEvent.OpenMedia || it is GalleryEvent.OpenDeleteMedia })
    }

    @Test fun collectionHeaderAndCreateButtonStayUsableAfterCollapsingTabs() {
        show(ArtistTab.Collections)
        val headerY = y("artistHeader")
        scrollUp()
        compose.onNodeWithTag("artistTabs").assertIsNotDisplayed()
        val sectionY = y("artistSection")
        scrollUp()
        assertEquals(headerY, y("artistHeader"), 1f)
        assertEquals(sectionY, y("artistSection"), 1f)
        compose.onNodeWithText("新建作品集").assertIsDisplayed().performClick()
        assertEquals(GalleryEvent.OpenDialog(GalleryDialog.CreateCollection("artist")), events.last())
    }

    @Test fun partialTabCollapseIsSavedSeparatelyFromTheListPosition() {
        show()
        val expandedHeight = height("artistTabs")
        dragList(-with(compose.density) { 50.dp.toPx() })
        val partialHeight = height("artistTabs")
        assertTrue(partialHeight > 0f && partialHeight < expandedHeight)
        assertEquals(0f, scrollPosition(), .1f)
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithTag("artistMediaList").assertDoesNotExist()
        compose.runOnIdle { visible.value = true }
        assertEquals(partialHeight, height("artistTabs"), 1f)
        assertEquals(0f, scrollPosition(), .1f)
    }

    private fun list() = compose.onNodeWithTag("artistMediaList")
    private fun y(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y
    private fun height(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().size.height.toFloat()
    private fun scrollPosition() = list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun scrollUp() = dragList(-height("artistMediaList") * .7f)

    /** Pause before release so assertions check scrolling, without a later fling moving the viewport. */
    private fun dragList(distance: Float) {
        list().performTouchInput {
            down(center)
            moveBy(Offset(0f, distance), delayMillis = 300)
            moveBy(Offset.Zero, delayMillis = 400)
            up()
        }
        compose.waitForIdle()
    }

    private fun show(tab: ArtistTab = ArtistTab.Images) {
        val initial = GalleryUiState(loadState = LoadState.Ready, mediaLoadState = LoadState.Ready,
            route = GalleryRoute.Artist("artist"), artistDetail = detail, artistTab = tab, ).withPreparedMedia(images)
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            current = remember { mutableStateOf(initial) }
            visible = remember { mutableStateOf(true) }
            val holder = rememberSaveableStateHolder()
            DTheme {
                if (visible.value) holder.SaveableStateProvider("artist") {
                    ArtistDetailScreen(current.value, 18.dp, null, null) { event ->
                        events.add(event)
                        if (event is GalleryEvent.RememberScrollPosition) current.value = current.value.copy(
                            scrollPositions = current.value.scrollPositions + (event.key to GalleryScrollPosition(event.index, event.offset)),
                        )
                    }
                }
            }
        } }
        compose.waitForIdle()
    }
}
