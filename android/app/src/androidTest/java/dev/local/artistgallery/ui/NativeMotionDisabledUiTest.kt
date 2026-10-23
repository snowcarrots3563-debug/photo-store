package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.local.artistgallery.fixture.UiFixtureActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Test-recomposer duration scale only; never changes the phone's animator settings. */
@OptIn(ExperimentalTestApi::class)
class NativeMotionDisabledUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>(effectContext = object : MotionDurationScale {
        override val scaleFactor = 0f
    })

    @Test fun navigationAndDialogsReachTheirRealTerminalStateWithoutTimedWaits() {
        compose.mainClock.autoAdvance = false
        var manage by mutableStateOf(false)
        var value by mutableStateOf<String?>(null)
        var actions = 0
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            DTheme {
                Column {
                    DRouteMotion(if (manage) GalleryRoute.Manage else GalleryRoute.Home, Modifier.testTag("route")) { Text("内容") }
                    DBottomNavigation(manage) { actions++; manage = true }
                }
                DAnimatedDialog(value, { value = null }) { model, panel, fraction ->
                    Text(model, panel.testTag("disabledMotionPanel").semantics { motionFraction = fraction })
                }
            }
        } }
        compose.onNodeWithText("管理").performClick()
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        compose.onNodeWithTag("route").assert(SemanticsMatcher.expectValue(DMotionFraction, 1f))
        compose.onNodeWithTag("bottomNavigationPill", useUnmergedTree = true).assert(SemanticsMatcher.expectValue(DMotionFraction, 1f))
        compose.runOnIdle { assertEquals(1, actions); value = "真实结果" }
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        compose.onNodeWithTag("disabledMotionPanel").assert(SemanticsMatcher.expectValue(DMotionFraction, 1f))
        compose.runOnIdle { value = null }
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("内容").assertIsDisplayed()
    }
}
