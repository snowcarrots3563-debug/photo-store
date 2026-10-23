package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.local.artistgallery.fixture.UiFixtureActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic cards and clocks only. No gallery, task, preference or device-setting mutation. */
class PopupCardMotionUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()

    @Test fun floatingCardHasExternalSafeInsetsAndFourRoundedCorners() {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { DTheme {
            CompositionLocalProvider(LocalDensity provides Density(1.75f)) {
                Box(Modifier.size(320.dp, 560.dp).background(Color(0xFF123456)).testTag("viewport")) {
                    DPopupCard(Modifier, 1f, WindowInsets(10.dp, 20.dp, 12.dp, 30.dp), {}) { card ->
                        Column(card) { Spacer(Modifier.height(140.dp)) }
                    }
                }
            }
        } } }
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val node = compose.onNodeWithTag("dPopupCard")
        val card = node.fetchSemanticsNode().boundsInRoot
        assertEquals(25f * 1.75f, card.left - viewport.left, 1f)
        assertEquals(27f * 1.75f, viewport.right - card.right, 1f)
        assertEquals(45f * 1.75f, viewport.bottom - card.bottom, 1f)
        val pixels = node.captureToImage().toPixelMap()
        listOf(0 to 0, pixels.width - 1 to 0, 0 to pixels.height - 1, pixels.width - 1 to pixels.height - 1).forEach { (x, y) ->
            assertNotEquals("Every corner must expose the backdrop", Color.White.toArgb(), pixels[x, y].toArgb())
        }
        assertEquals(Color.White.toArgb(), pixels[pixels.width / 2, pixels.height / 2].toArgb())
    }

    @Test fun tallCardIsBoundedAndScrollableActionsStayAboveChangedKeyboardInset() {
        var bottom by mutableStateOf(30.dp)
        var actions = 0
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { DTheme {
            CompositionLocalProvider(LocalDensity provides Density(1.75f)) {
                Box(Modifier.size(320.dp, 560.dp).testTag("viewport")) {
                    DPopupCard(Modifier, 1f, WindowInsets(0.dp, 20.dp, 0.dp, bottom), {}) { card ->
                        Column(card.verticalScroll(rememberScrollState())) {
                            Spacer(Modifier.height(900.dp))
                            DButton("完成", onClick = { actions++ })
                        }
                    }
                }
            }
        } } }
        compose.runOnIdle { bottom = 96.dp }
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("dPopupCard").fetchSemanticsNode().boundsInRoot
        val safeHeight = viewport.height - (20f + 96f + 30f) * 1.75f
        assertTrue(panel.height <= safeHeight * .94f + 2f)
        assertEquals(111f * 1.75f, viewport.bottom - panel.bottom, 1f)
        val button = compose.onNodeWithText("完成").performScrollTo().assertIsDisplayed()
        assertTrue(button.fetchSemanticsNode().boundsInRoot.bottom <= panel.bottom)
        button.performClick()
        compose.runOnIdle { assertEquals(1, actions) }
    }

    @Test fun firstEntryComesFromBelowWindowAndSettlesQuicklyWithoutFadingOrReplayingProgress() {
        compose.mainClock.autoAdvance = false
        var value by mutableStateOf<String?>(null)
        var step by mutableIntStateOf(0)
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { DTheme {
            DAnimatedDialog(value, { value = null }, contentKey = step) { model, panel, scrim ->
                Box(Modifier.fillMaxSize().testTag("mask").semantics { motionFraction = scrim }) {
                    Text(model, panel.size(200.dp, 100.dp).testTag("animatedPanel"))
                }
            }
        } } }
        compose.runOnIdle { value = "首次" }
        compose.mainClock.advanceTimeByFrame()
        val panel = compose.onNodeWithTag("animatedPanel")
        val windowHeight = compose.onNodeWithTag("mask").fetchSemanticsNode().boundsInRoot.height / compose.density.density
        val start = panel.fetchSemanticsNode().config[DPopupSlide]
        assertTrue("A whole card must enter from below the window", start > windowHeight * .65f)
        compose.mainClock.advanceTimeBy(80)
        panel.assert(SemanticsMatcher.expectValue(DPopupOpacity, 1f))
        val moving = panel.fetchSemanticsNode().config[DPopupSlide]
        assertTrue(moving > 0f && moving < start)
        compose.mainClock.advanceTimeBy(160)
        panel.assert(SemanticsMatcher.expectValue(DPopupSlide, 0f))
        panel.assert(SemanticsMatcher.expectValue(DPopupOpacity, 1f))
        compose.onNodeWithTag("mask").assert(SemanticsMatcher.expectValue(DMotionFraction, 1f))
        compose.runOnIdle { value = "下一步"; step = 1 }
        compose.mainClock.advanceTimeByFrame()
        val slide = panel.fetchSemanticsNode().config[DPopupSlide]
        assertTrue(slide > 0f && slide <= 12f)
        compose.onNodeWithTag("mask").assert(SemanticsMatcher.expectValue(DMotionFraction, 1f))
        compose.mainClock.advanceTimeBy(280)
        compose.runOnIdle { value = "真实进度50%" }
        panel.assert(SemanticsMatcher.expectValue(DPopupSlide, 0f))
        panel.assert(SemanticsMatcher.expectValue(DPopupOpacity, 1f))
    }

    @Test fun exitKeepsOnlyDisabledPresentationAndRemovesWindowAfterShortExit() {
        compose.mainClock.autoAdvance = false
        var value by mutableStateOf<String?>("内容")
        var dismisses = 0
        var retained: String? = null
        var lastScrim = 0f
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { DTheme {
            DAnimatedDialog(value, { dismisses++; value = null }) { model, panel, scrim ->
                SideEffect { retained = model; lastScrim = scrim }
                Column(panel) { DButton(model) { dismisses++; value = null } }
            }
        } } }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("内容").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("内容").assertDoesNotExist()
        compose.onNode(isDialog()).assertExists()
        compose.runOnIdle { assertEquals("内容", retained); assertEquals(1, dismisses) }
        compose.mainClock.advanceTimeBy(120)
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0f, lastScrim, .001f); assertEquals(1, dismisses) }
    }

    @Test fun reopeningAfterACompletedExitUsesTheFullEntryAgain() {
        compose.mainClock.autoAdvance = false
        var value by mutableStateOf<String?>(null)
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { DTheme {
            DAnimatedDialog(value, { value = null }) { model, panel, _ ->
                Box(Modifier.fillMaxSize().testTag("reopenedWindow")) { Text(model, panel.testTag("reopenedPanel")) }
            }
        } } }
        repeat(2) { index ->
            compose.runOnIdle { value = "第${index + 1}次" }
            compose.mainClock.advanceTimeByFrame()
            val windowHeight = compose.onNodeWithTag("reopenedWindow").fetchSemanticsNode().boundsInRoot.height / compose.density.density
            assertTrue(compose.onNodeWithTag("reopenedPanel").fetchSemanticsNode().config[DPopupSlide] > windowHeight * .65f)
            compose.mainClock.advanceTimeBy(500)
            compose.runOnIdle { value = null }
            compose.mainClock.advanceTimeBy(250)
            compose.onNode(isDialog()).assertDoesNotExist()
        }
    }

    @Test fun centeredDialogKeepsItsExistingGradualEntryAndDoesNotTravelFromTheBottom() {
        compose.mainClock.autoAdvance = false
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { DTheme {
            DAnimatedDialog("居中选择", {}, centered = true) { model, panel, _ ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(model, panel.testTag("centeredPanel")) }
            }
        } } }
        compose.mainClock.advanceTimeBy(220)
        val panel = compose.onNodeWithTag("centeredPanel")
        panel.assert(SemanticsMatcher.expectValue(DPopupSlide, 0f))
        val opacity = panel.fetchSemanticsNode().config[DPopupOpacity]
        assertTrue(opacity > 0f && opacity < 1f)
        compose.mainClock.advanceTimeBy(300)
        panel.assert(SemanticsMatcher.expectValue(DPopupOpacity, 1f))
    }
}
