package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.model.ImportBatchHistorySummary
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic presentation state and controlled clocks; no preferences, files or gallery IO. */
class ManageMotionUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()

    private val keys = listOf("unassigned", "import", "history", "export", "backup", "restore", "card", "gif", "swipe", "auto", "about", "changelog")
    private fun row(key: String) = compose.onNodeWithTag("manageRow:$key")
    private fun icon(key: String) = compose.onNodeWithTag("manageIcon:$key", useUnmergedTree = true)
    private fun fraction(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().config[DMotionFraction]
    private fun assertThumbPosition(key: String, checked: Boolean) {
        val track = compose.onNodeWithTag("manageTrack:$key", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val thumb = compose.onNodeWithTag("manageThumb:$key", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(track.width * (if (checked) 20f else 3f) / 44f, thumb.left - track.left, 1f)
        assertEquals(track.height * 3f / 27f, thumb.top - track.top, 1f)
    }

    @Test fun allTwelveRowsKeepTheirOrderAndDispatchOriginalActionsOnce() {
        val events = mutableListOf<GalleryEvent>()
        val actions = mutableListOf<String>()
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { DTheme {
            ManageScreen(GalleryUiState(), 20.dp, { events += it }, { actions += "history" }, { actions += "export" }, { actions += "backup" }, { actions += "restore" })
        } } }
        val positions = keys.map { row(it).fetchSemanticsNode().positionInRoot.y }
        assertTrue(positions.zipWithNext().all { (a, b) -> b > a })
        keys.filter { it !in listOf("card", "gif", "swipe", "auto") }.forEach { row(it).performScrollTo().performClick() }
        compose.runOnIdle {
            assertEquals(listOf("history", "export", "backup", "restore"), actions)
            assertEquals(listOf(GalleryEvent.OpenUnassigned, GalleryEvent.OpenDialog(GalleryDialog.AddMenu), GalleryEvent.OpenDialog(GalleryDialog.About), GalleryEvent.OpenDialog(GalleryDialog.UpdateHistory)), events)
        }
    }

    @Test fun switchesHaveOneWholeRowActionAndKeepGeometryWhileAnimatingInBothDirections() {
        compose.mainClock.autoAdvance = false
        var state by mutableStateOf(GalleryUiState())
        val events = mutableListOf<GalleryEvent>()
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { DTheme {
            ManageScreen(state, 20.dp, { event ->
                events += event
                state = state.copy(browseOptions = when (event) {
                    is GalleryEvent.SetCardEffects -> state.browseOptions.copy(cardEffects = event.enabled)
                    is GalleryEvent.SetPlayGifs -> state.browseOptions.copy(playGifs = event.enabled)
                    is GalleryEvent.SetSwipeDelete -> state.browseOptions.copy(swipeDelete = event.enabled)
                    is GalleryEvent.SetAutoPlayVideos -> state.browseOptions.copy(autoPlayVideos = event.enabled)
                    else -> state.browseOptions
                })
            })
        } } }
        val initial = listOf(false, true, true, true)
        listOf("card", "gif", "swipe", "auto").forEachIndexed { index, key ->
            val target = row(key).performScrollTo()
            if (initial[index]) target.assertIsOn() else target.assertIsOff()
            assertThumbPosition(key, initial[index])
            val before = target.fetchSemanticsNode().boundsInRoot
            val sibling = row(if (key == "card") "gif" else "card").fetchSemanticsNode().positionInRoot
            target.assertHeightIsAtLeast(87.dp).performClick()
            compose.mainClock.advanceTimeBy(100)
            assertEquals(before, target.fetchSemanticsNode().boundsInRoot)
            assertEquals(sibling, row(if (key == "card") "gif" else "card").fetchSemanticsNode().positionInRoot)
            assertTrue(fraction(icon(key)) < 1.05f)
            compose.mainClock.advanceTimeBy(500)
            assertThumbPosition(key, !initial[index])
            target.performClick()
            compose.mainClock.advanceTimeBy(500)
            if (initial[index]) target.assertIsOn() else target.assertIsOff()
            assertEquals(1f, fraction(icon(key)), .001f)
            compose.onNodeWithTag("manageTrack:$key", useUnmergedTree = true).assertWidthIsEqualTo(44.dp).assertHeightIsEqualTo(27.dp)
            compose.onNodeWithTag("manageThumb:$key", useUnmergedTree = true).assertWidthIsEqualTo(21.dp).assertHeightIsEqualTo(21.dp)
        }
        compose.runOnIdle {
            assertEquals(listOf(GalleryEvent.SetCardEffects(true), GalleryEvent.SetCardEffects(false), GalleryEvent.SetPlayGifs(false), GalleryEvent.SetPlayGifs(true), GalleryEvent.SetSwipeDelete(false), GalleryEvent.SetSwipeDelete(true), GalleryEvent.SetAutoPlayVideos(false), GalleryEvent.SetAutoPlayVideos(true)), events)
        }
    }

    @Test fun incompleteImportAndUnavailableFallbacksKeepTheirOriginalEvents() {
        var state by mutableStateOf(GalleryUiState(recentImportBatches = listOf(ImportBatchHistorySummary("finished", "COMPLETED"), ImportBatchHistorySummary("recoverable", "FAILED"))))
        val events = mutableListOf<GalleryEvent>()
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { DTheme { ManageScreen(state, 20.dp, { events += it }) } } }
        row("history").assertDoesNotExist()
        listOf("export", "backup", "restore", "incomplete").forEach { row(it).performScrollTo().performClick() }
        compose.runOnIdle {
            assertEquals(listOf(GalleryEvent.OpenDialog(GalleryDialog.Unavailable("按范围导出", 5)), GalleryEvent.OpenDialog(GalleryDialog.Unavailable("完整备份", 5)), GalleryEvent.OpenDialog(GalleryDialog.Unavailable("从备份恢复", 5)), GalleryEvent.OpenImportResult("recoverable")), events)
            state = state.copy(recentImportBatches = listOf(ImportBatchHistorySummary("finished", "COMPLETED")))
        }
        row("incomplete").assertDoesNotExist()
    }

    @Test fun rapidReversalContinuesFromCurrentFrameAndUnrelatedUpdatesDoNotReplayIcons() {
        compose.mainClock.autoAdvance = false
        var state by mutableStateOf(GalleryUiState())
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { DTheme { ManageScreen(state, 20.dp, {}) } } }
        row("card").performScrollTo()
        assertEquals(1f, fraction(icon("card")), .001f)
        assertEquals(1f, fraction(icon("gif")), .001f)
        compose.runOnIdle { state = state.copy(browseOptions = state.browseOptions.copy(cardEffects = true)) }
        compose.mainClock.advanceTimeBy(96)
        val before = fraction(icon("card"))
        compose.runOnIdle { state = state.copy(browseOptions = state.browseOptions.copy(cardEffects = false)) }
        assertEquals(before, fraction(icon("card")), .001f)
        compose.mainClock.advanceTimeBy(100)
        assertTrue(fraction(icon("card")) < 1f)
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { state = state.copy(searchQuery = "unrelated") }
        compose.mainClock.advanceTimeBy(100)
        assertEquals(1f, fraction(icon("card")), .001f)
        assertEquals(1f, fraction(icon("gif")), .001f)
    }

    @Test fun wholeRowPressUsesDrawScaleAndRetainsItsHitBounds() {
        compose.mainClock.autoAdvance = false
        var calls = 0
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { DTheme { ManageScreen(GalleryUiState(), 20.dp, { calls++ }) } } }
        val target = row("unassigned").performScrollTo()
        val bounds = target.fetchSemanticsNode().boundsInRoot
        target.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(200)
        assertEquals(.97f, fraction(target), .001f)
        assertEquals(bounds, target.fetchSemanticsNode().boundsInRoot)
        target.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(200)
        compose.runOnIdle { assertEquals(1, calls) }
        assertEquals(1f, fraction(target), .001f)
    }

    @Test fun inactiveDialogScopeDisablesAllRowsAndNoticeClose() {
        var calls = 0
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { DTheme {
            CompositionLocalProvider(LocalDDialogActive provides false) { ManageScreen(GalleryUiState(), 20.dp, { calls++ }, { calls++ }, { calls++ }, { calls++ }, { calls++ }) }
        } } }
        keys.forEach { row(it).assertIsNotEnabled() }
        row("unassigned").performTouchInput { click(center) }
        compose.onNodeWithContentDescription("关闭保存提示").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun noticeCloseDispatchesOnceThenCollapsesNaturallyWithRetainedExitDisabled() {
        compose.mainClock.autoAdvance = false
        var state by mutableStateOf(GalleryUiState())
        var closes = 0
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { DTheme {
            ManageScreen(state, 20.dp, { if (it == GalleryEvent.DismissStorageNotice) { closes++; state = state.copy(browseOptions = state.browseOptions.copy(storageNoticeDismissed = true)) } })
        } } }
        val initial = row("unassigned").fetchSemanticsNode().positionInRoot.y
        compose.onNodeWithContentDescription("关闭保存提示").performClick()
        compose.mainClock.advanceTimeBy(100)
        val intermediate = row("unassigned").fetchSemanticsNode().positionInRoot.y
        compose.onNodeWithContentDescription("关闭保存提示").assertIsNotEnabled().performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(300)
        val final = row("unassigned").fetchSemanticsNode().positionInRoot.y
        assertTrue(initial > intermediate && intermediate > final)
        compose.onNodeWithTag("manageNotice").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, closes) }
    }

    @Test fun narrowWidthAndLargeFontKeepSwitchAndTextInsideWholeRows() {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { DTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Box(Modifier.width(280.dp)) { ManageScreen(GalleryUiState(), 12.dp, {}) }
            }
        } } }
        row("auto").performScrollTo().assertHeightIsAtLeast(87.dp)
        val bounds = row("auto").fetchSemanticsNode().boundsInRoot
        val track = compose.onNodeWithTag("manageTrack:auto", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val text = compose.onNodeWithText("打开视频时自动播放", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(track.left >= bounds.left && track.right <= bounds.right)
        assertTrue(text.left >= bounds.left && text.right <= track.left)
    }
}
