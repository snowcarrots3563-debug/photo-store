package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.local.artistgallery.fixture.UiFixtureActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Controlled synthetic presentation states only; no files, database or phone preferences. */
class NativeMotionUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()

    @Test fun routeEntryUsesOppositeDirectionOnReturnAndDoesNotReplayForData() {
        compose.mainClock.autoAdvance = false
        var route by mutableStateOf<GalleryRoute>(GalleryRoute.Home)
        var label by mutableStateOf("初始")
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { DTheme { DRouteMotion(route, Modifier.testTag("route")) { Text(label) } } } }
        compose.onNodeWithTag("route").assert(SemanticsMatcher.expectValue(DMotionFraction, 1f))
        compose.runOnIdle { route = GalleryRoute.Artist("sample") }
        compose.mainClock.advanceTimeByFrame()
        assertTrue(compose.onNodeWithTag("route").fetchSemanticsNode().config[DMotionFraction] < 1f)
        compose.onNodeWithTag("route").assert(SemanticsMatcher.expectValue(DMotionDirection, 1))
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { label = "真实内容就绪" }
        compose.onNodeWithTag("route").assert(SemanticsMatcher.expectValue(DMotionFraction, 1f))
        compose.runOnIdle { route = GalleryRoute.Home }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("route").assert(SemanticsMatcher.expectValue(DMotionDirection, -1))
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("真实内容就绪").assertIsDisplayed()
    }

    @Test fun bottomPillMovesWhileTheTabActionRemainsImmediateAndSingle() {
        compose.mainClock.autoAdvance = false
        var manage by mutableStateOf(false)
        val events = mutableListOf<GalleryEvent>()
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { DTheme { DBottomNavigation(manage) { events += it; manage = it == GalleryEvent.OpenManage } } } }
        compose.onNodeWithText("管理").performClick()
        compose.runOnIdle { assertEquals(listOf(GalleryEvent.OpenManage), events) }
        compose.mainClock.advanceTimeBy(160)
        val moving = compose.onNodeWithTag("bottomNavigationPill", useUnmergedTree = true).fetchSemanticsNode().config[DMotionFraction]
        assertTrue(moving > 0f && moving < 1f)
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("bottomNavigationPill", useUnmergedTree = true).assert(SemanticsMatcher.expectValue(DMotionFraction, 1f))
        compose.onNodeWithText("管理").assertIsSelected()
    }

    @Test fun pressedIconKeepsItsHitBoundsAndDisabledActionDoesNotFire() {
        compose.mainClock.autoAdvance = false
        var enabled by mutableStateOf(true)
        var actions = 0
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { DTheme { DIconButton(DIcon.Plus, "添加样本", enabled) { actions++ } } } }
        val button = compose.onNodeWithContentDescription("添加样本")
        val bounds = button.fetchSemanticsNode().boundsInRoot
        button.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(200)
        assertEquals(bounds, button.fetchSemanticsNode().boundsInRoot)
        button.performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, actions); enabled = false }
        button.assertIsNotEnabled().performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, actions) }
    }

    @Test fun dialogCloseRetainsLastPresentationDisablesActionsAndReversesForLatestModel() {
        compose.mainClock.autoAdvance = false
        var value by mutableStateOf<String?>(null)
        var actions = 0
        var drawnModel: String? = null
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            DTheme {
                DAnimatedDialog(value, { value = null }, DialogProperties(decorFitsSystemWindows = false), centered = true) { model, panel, _ ->
                    SideEffect { drawnModel = model }
                    Box(panel.size(240.dp).testTag("panel")) {
                        DButton(model) { actions++; value = null }
                    }
                }
            }
        } }
        compose.runOnIdle { value = "完成结果" }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("完成结果").performClick()
        compose.runOnIdle { assertEquals(1, actions) }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertEquals("完成结果", drawnModel) }
        // The old result is still drawn during exit, but is absent from accessibility/actions.
        compose.onNodeWithText("完成结果").assertDoesNotExist()
        compose.runOnIdle { value = "新窗口" }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("新窗口").assertIsEnabled().assertIsDisplayed()
        compose.onNodeWithText("完成结果").assertDoesNotExist()
        compose.runOnIdle { value = null }
        compose.mainClock.advanceTimeBy(500)
        compose.onNode(isDialog()).assertDoesNotExist()
        assertEquals(1, actions)
    }

    @Test fun dialogDraftAndProgressUpdatesDoNotRestartTheEntrance() {
        compose.mainClock.autoAdvance = false
        var value by mutableStateOf<String?>("准备")
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            DTheme {
                DAnimatedDialog(value, {}) { model, panel, fraction ->
                    Text(model, panel.testTag("content").semantics { motionFraction = fraction })
                }
            }
        } }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("content").assert(SemanticsMatcher.expectValue(DMotionFraction, 1f))
        compose.runOnIdle { value = "读取中 50%" }
        compose.onNodeWithText("读取中 50%").assert(SemanticsMatcher.expectValue(DMotionFraction, 1f))
        compose.runOnIdle { value = "真实结果" }
        compose.onNodeWithText("真实结果").assert(SemanticsMatcher.expectValue(DMotionFraction, 1f))
    }

    @Test fun savingFormMaskCannotDismiss() {
        val events = mutableListOf<GalleryEvent>()
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { DTheme { GalleryDialogs(GalleryUiState(dialog = GalleryDialog.AddArtist(), isSaving = true), null, { events += it }) } } }
        compose.onNodeWithTag("galleryDialogBackdrop").performTouchInput { click(androidx.compose.ui.geometry.Offset(2f, 2f)) }
        compose.runOnIdle { assertTrue(events.isEmpty()) }
        compose.onNodeWithContentDescription("取消").assertIsNotEnabled()
    }
}
