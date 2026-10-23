package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.model.*
import dev.local.artistgallery.ui.comic.ComicReaderScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** In-memory state only; no private gallery, decoder or preferences. */
class ComicReaderMotionUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private val events = mutableListOf<GalleryEvent>()
    private lateinit var shown: MutableState<Boolean>
    private lateinit var state: MutableState<GalleryUiState>

    @Test fun savedWindowSurvivesExitAndReentryAndOpeningImageSavesBeforeNavigation() {
        show()
        compose.onNodeWithTag("comicList").performScrollToIndex(8)
        compose.onNodeWithContentDescription("退出漫画阅读").performClick()
        val saved = events.filterIsInstance<GalleryEvent.RememberScrollPosition>().last()
        assertTrue(saved.index >= 8)
        compose.runOnIdle { shown.value = true }
        compose.onNodeWithTag("comicPage:page8").assertIsDisplayed()
        compose.onNodeWithTag("comicPage:page8").performClick()
        assertTrue(events[events.lastIndex - 1] is GalleryEvent.RememberScrollPosition)
        assertEquals(GalleryEvent.OpenMedia("page8"), events.last())
    }

    @Test fun explicitDirectionChangeStartsAtTopAndCollectionKeysAreIsolated() {
        show()
        compose.onNodeWithTag("comicList").performScrollToIndex(8)
        compose.onNodeWithContentDescription("漫画阅读顺序").performClick()
        compose.onNodeWithText("名称倒序").performClick()
        compose.onNodeWithTag("comicPage:page19").assertIsDisplayed()
        assertEquals(1, events.count { it == GalleryEvent.ToggleComicDirection })
        compose.runOnIdle {
            state.value = state.value.copy(collection = state.value.collection!!.copy(id = "other"),
                route = GalleryRoute.Collection("other", "artist"))
        }
        compose.onNodeWithTag("comicPage:page19").assertIsDisplayed()
    }

    private fun show() {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            shown = remember { mutableStateOf(true) }
            state = remember { mutableStateOf(GalleryUiState(loadState = LoadState.Ready,
                collection = CollectionSummary("comic", "artist", "样例图集", false, 20, 0),
                route = GalleryRoute.Collection("comic", "artist")).withPreparedMedia((0..19).map {
                MediaAssetSummary("page$it", "$it.png", MediaType.IMAGE, 1, it, "$it.png", "page$it")
            })) }
            if (shown.value) DTheme { ComicReaderScreen(state.value, null) { event ->
                events.add(event)
                when (event) {
                    is GalleryEvent.RememberScrollPosition -> state.value = state.value.copy(
                        scrollPositions = state.value.scrollPositions + (event.key to GalleryScrollPosition(event.index, event.offset)))
                    GalleryEvent.CloseOverlay -> shown.value = false
                    GalleryEvent.ToggleComicDirection -> state.value = state.value.copy(comicDirection = ComicDirection.NameDescending)
                    else -> Unit
                }
            } }
        } }
    }
}
