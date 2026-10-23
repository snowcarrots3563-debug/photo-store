package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import dev.local.artistgallery.data.ArtistImageLayout
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
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

    @Test fun viewLayoutReachesItsDestinationAndReleasesOverlayWithAnimationScaleZero() {
        compose.mainClock.autoAdvance = false
        val motion = ArtistLayoutMotionState()
        val image = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
        motion.viewport = Rect(0f, 0f, 1000f, 1000f)
        motion.register("sample", ArtistLayoutMotionEntry(ArtistImageLayout.List, Rect(20f, 40f, 80f, 100f), image, 18f, false))
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            Box(Modifier.fillMaxSize()) { ArtistLayoutMotionOverlay(motion, Modifier.fillMaxSize()) }
        } }
        compose.runOnIdle {
            motion.begin(ArtistImageLayout.Large)
            motion.position("sample", ArtistLayoutMotionEntry(ArtistImageLayout.Large, Rect(180f, 200f, 300f, 320f), radius = 0f, video = false))
            motion.bind(ArtistImageLayout.Large)
        }
        repeat(4) { compose.mainClock.advanceTimeByFrame() }
        compose.onNodeWithTag("artistLayoutOverlay").assertDoesNotExist()
        compose.runOnIdle { assertEquals(null, motion.session) }
    }

    @Test fun settingsSwitchAndNoticeReachTerminalStatesWhenMotionIsDisabled() {
        compose.mainClock.autoAdvance = false
        var state by mutableStateOf(GalleryUiState())
        var toggles = 0
        var closes = 0
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { DTheme {
            ManageScreen(state, 20.dp, { event ->
                when (event) {
                    is GalleryEvent.SetCardEffects -> { toggles++; state = state.copy(browseOptions = state.browseOptions.copy(cardEffects = event.enabled)) }
                    GalleryEvent.DismissStorageNotice -> { closes++; state = state.copy(browseOptions = state.browseOptions.copy(storageNoticeDismissed = true)) }
                    else -> Unit
                }
            })
        } } }
        compose.onNodeWithTag("manageRow:card").performScrollTo().performClick()
        repeat(4) { compose.mainClock.advanceTimeByFrame() }
        compose.onNodeWithTag("manageRow:card").assertIsOn()
        compose.onNodeWithTag("manageIcon:card", useUnmergedTree = true).assert(SemanticsMatcher.expectValue(DMotionFraction, 1f))
        compose.onNodeWithTag("manageTrack:card", useUnmergedTree = true).assert(SemanticsMatcher.expectValue(DMotionFraction, 1f))
        compose.onNodeWithContentDescription("关闭保存提示").performScrollTo().performClick()
        repeat(4) { compose.mainClock.advanceTimeByFrame() }
        compose.onNodeWithTag("manageNotice").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, toggles); assertEquals(1, closes) }
    }
}
