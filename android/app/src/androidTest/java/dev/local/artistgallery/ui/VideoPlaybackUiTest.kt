package dev.local.artistgallery.ui

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.data.preview.PreviewDecoder
import dev.local.artistgallery.data.preview.SyntheticVideoFixture
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.model.MediaAssetSummary
import dev.local.artistgallery.model.MediaType
import dev.local.artistgallery.ui.viewer.VideoPlayerScreen
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

/** Real player with generated files; no player injection, database, real gallery or global preferences. */
@androidx.annotation.OptIn(UnstableApi::class)
class VideoPlaybackUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private val fixture by lazy { SyntheticVideoFixture(compose.activity) }
    private lateinit var current: MutableState<GalleryUiState>
    private lateinit var visible: MutableState<Boolean>
    private lateinit var owner: MutableState<LifecycleOwner>

    @After fun cleanup() {
        compose.runOnIdle { if (::visible.isInitialized) visible.value = false }
        compose.waitForIdle()
        fixture.cleanup()
    }

    @Test fun defaultOpenStartsPlayingAndOnlyActualPlayingKeepsTheScreenOn() {
        show()
        waitForPlayer { it.isPlaying }
        compose.onNodeWithContentDescription("暂停视频").assertIsEnabled()
        compose.runOnIdle { assertTrue(keepsScreenOn(playerView())) }
        compose.onNodeWithContentDescription("暂停视频").performClick()
        waitForPlayer { !it.isPlaying }
        compose.runOnIdle { assertFalse(keepsScreenOn(playerView())) }
    }

    @Test fun disabledOpenWaitsForManualPlayAndAllowsPauseAndSeek() {
        show(autoPlay = false)
        waitForPlayer { it.playbackState == Player.STATE_READY }
        compose.runOnIdle { assertFalse(player().playWhenReady); assertFalse(keepsScreenOn(playerView())) }
        compose.onNodeWithContentDescription("播放视频").assertIsEnabled().performClick()
        waitForPlayer { it.isPlaying }
        compose.onNodeWithContentDescription("暂停视频").performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).performTouchInput { click(Offset(center.x * 1.5f, center.y)) }
        compose.runOnIdle { assertFalse(player().playWhenReady) }
    }

    @Test fun manualPauseAndUnrelatedRecompositionOrSettingRefreshDoNotReplaceOrRestartPlayer() {
        show()
        waitForPlayer { it.isPlaying }
        val original = capturePlayer()
        compose.onNodeWithContentDescription("暂停视频").performClick()
        compose.runOnIdle { current.value = current.value.copy(searchQuery = "refresh", browseOptions = current.value.browseOptions.copy(autoPlayVideos = false, playGifs = false)) }
        compose.runOnIdle { current.value = current.value.copy(browseOptions = current.value.browseOptions.copy(autoPlayVideos = true)) }
        compose.runOnIdle { assertSame(original, player()); assertFalse(player().playWhenReady) }
        compose.onNodeWithContentDescription("播放视频").assertIsEnabled()
    }

    @Test fun stopPausesAndReturningToForegroundDoesNotRestart() {
        show()
        waitForPlayer { it.isPlaying }
        val original = capturePlayer()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertSame(original, player()); assertFalse(player().playWhenReady); assertFalse(keepsScreenOn(playerView())) }
        compose.onNodeWithContentDescription("播放视频").assertIsEnabled()
    }

    @Test fun initialCreatedOwnerDefersAutoPlayUntilItsFirstStartOnly() {
        lateinit var lifecycle: FixtureOwner
        compose.runOnIdle { lifecycle = FixtureOwner().apply { registry.currentState = Lifecycle.State.CREATED } }
        show(customOwner = lifecycle)
        waitForPlayer { it.playbackState == Player.STATE_READY }
        val original = capturePlayer()
        compose.runOnIdle { assertFalse(player().playWhenReady); lifecycle.registry.currentState = Lifecycle.State.STARTED }
        waitForPlayer { it.isPlaying }
        compose.onNodeWithContentDescription("暂停视频").performClick()
        compose.runOnIdle { lifecycle.registry.currentState = Lifecycle.State.CREATED; lifecycle.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { assertSame(original, player()); assertFalse(player().playWhenReady) }
    }

    @Test fun lifecycleOwnerReplacementKeepsThePreparedPlayerAndManualPause() {
        show()
        waitForPlayer { it.isPlaying }
        val original = capturePlayer()
        compose.onNodeWithContentDescription("暂停视频").performClick()
        compose.runOnIdle { owner.value = FixtureOwner().apply { registry.currentState = Lifecycle.State.STARTED } }
        compose.runOnIdle { assertSame(original, player()); assertEquals(Player.STATE_READY, player().playbackState); assertFalse(player().playWhenReady) }
    }

    @Test fun pictureControlIsBottomLeftHas48DpTouchAreaAndIsDisabledUntilReady() {
        show(autoPlay = false, broken = true)
        val control = compose.onNodeWithContentDescription("播放视频").assertIsNotEnabled().fetchSemanticsNode()
        compose.runOnIdle {
            val bounds = IntArray(2).also { playerView().getLocationOnScreen(it) }
            val rootBounds = IntArray(2).also { compose.activity.window.decorView.getLocationOnScreen(it) }
            val density = compose.activity.resources.displayMetrics.density
            assertEquals(48f * density, control.boundsInRoot.width, 1f)
            assertEquals(48f * density, control.boundsInRoot.height, 1f)
            val screen = control.boundsInWindow
            assertEquals(bounds[0] - rootBounds[0] + 12f * density, screen.left, 2f)
            assertEquals(bounds[1] - rootBounds[1] + playerView().height - 12f * density, screen.bottom, 2f)
        }
    }

    @Test fun exitReleasesPlayerClearsScreenOnAndNextOpenReadsTheLatestSetting() {
        show()
        waitForPlayer { it.isPlaying }
        val original = capturePlayer()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(Player.STATE_IDLE, original.playbackState); assertFalse(original.isPlaying); assertNull(findPlayerView(compose.activity.window.decorView)) }
        compose.runOnIdle { current.value = current.value.copy(browseOptions = current.value.browseOptions.copy(autoPlayVideos = false)); visible.value = true }
        waitForPlayer { it.playbackState == Player.STATE_READY }
        compose.runOnIdle { assertNotSame(original, player()); assertFalse(player().playWhenReady); assertFalse(keepsScreenOn(playerView())) }
    }

    @Test fun endStopsThePlayingStateAndKeepsControlsDisabledWithoutRestarting() {
        show()
        waitForPlayer { it.isPlaying }
        compose.runOnIdle { player().seekTo(player().duration) }
        waitForPlayer { it.playbackState == Player.STATE_ENDED }
        compose.onNodeWithContentDescription("播放视频").assertIsNotEnabled()
        compose.runOnIdle {
            assertFalse(keepsScreenOn(playerView()))
            current.value = current.value.copy(searchQuery = "end refresh")
        }
        compose.runOnIdle { assertEquals(Player.STATE_ENDED, player().playbackState); assertFalse(player().isPlaying) }
    }

    @Test fun manageSwitchFollowsSwipeDeleteAndKeepsGifIndependent() {
        compose.activity.setContent {
            current = remember { mutableStateOf(GalleryUiState(browseOptions = BrowseOptions(storageNoticeDismissed = true))) }
            DTheme { ManageScreen(current.value, 18.dp, { event ->
                if (event is GalleryEvent.SetAutoPlayVideos) current.value = current.value.copy(browseOptions = current.value.browseOptions.copy(autoPlayVideos = event.enabled))
            }) }
        }
        val gif = compose.onNodeWithText("播放 GIF 动画").performScrollTo().fetchSemanticsNode().boundsInRoot.top
        val swipe = compose.onNodeWithText("开启滑动删除").fetchSemanticsNode().boundsInRoot.top
        val auto = compose.onNodeWithText("打开视频时自动播放").fetchSemanticsNode().boundsInRoot.top
        assertTrue(gif < swipe && swipe < auto)
        compose.onNodeWithText("打开视频时自动播放").performScrollTo().performClick().assertIsOff()
        compose.onNodeWithText("播放 GIF 动画").performScrollTo().assertIsOn()
    }

    @Test fun bitmapBackedGalleryVideoRetainsItsCenteredPlayBadge() {
        val key = fixture.video()
        val decoder = PreviewDecoder(compose.activity.contentResolver, fixture.reader)
        compose.activity.setContent { DTheme { Box { MediaThumbnail(key, MediaType.VIDEO, decoder, Modifier.size(128.dp).testTag("videoTile")) } } }
        compose.waitUntil(10_000) {
            val px = (128 * compose.activity.resources.displayMetrics.density).roundToInt()
            decoder.cachedThumbnail(key, px, px, MediaType.VIDEO) != null
        }
        // DIconImage has no content description; capture the center pixels to check the visible badge.
        val tile = compose.onNodeWithTag("videoTile").captureToImage()
        val pixels = tile.toPixelMap()
        val radius = (16 * compose.activity.resources.displayMetrics.density).roundToInt()
        assertTrue((pixels.height / 2 - radius..pixels.height / 2 + radius).any { y ->
            (pixels.width / 2 - radius..pixels.width / 2 + radius).any { x ->
                pixels[x, y].let { it.red > .7f && it.green > .7f && it.blue > .7f }
            }
        })
    }

    private fun show(autoPlay: Boolean = true, broken: Boolean = false, customOwner: LifecycleOwner? = null) {
        val key = if (broken) fixture.broken() else fixture.video(frames = 120)
        val item = MediaAssetSummary("fixture-video", "synthetic.mp4", MediaType.VIDEO, fixture.files.originalFile(key).length(), 0, "synthetic.mp4", key)
        compose.activity.setContent {
            current = remember { mutableStateOf(GalleryUiState( browseOptions = BrowseOptions(autoPlayVideos = autoPlay)).withPreparedMedia(listOf(item))) }
            visible = remember { mutableStateOf(true) }
            val activityOwner = LocalLifecycleOwner.current
            owner = remember { mutableStateOf(customOwner ?: activityOwner) }
            CompositionLocalProvider(LocalLifecycleOwner provides owner.value) {
                DTheme { if (visible.value) VideoPlayerScreen(current.value, item.id, fixture.reader) { if (it == GalleryEvent.CloseOverlay) visible.value = false } }
            }
        }
    }

    private fun waitForPlayer(condition: (ExoPlayer) -> Boolean) = compose.waitUntil(10_000) {
        var matches = false
        compose.runOnIdle { findPlayerView(compose.activity.window.decorView)?.player?.let { matches = condition(it as ExoPlayer) } }
        matches
    }
    private fun capturePlayer(): ExoPlayer { lateinit var result: ExoPlayer; compose.runOnIdle { result = player() }; return result }
    private fun player() = requireNotNull(playerView().player) as ExoPlayer
    private fun playerView() = requireNotNull(findPlayerView(compose.activity.window.decorView))
    private fun findPlayerView(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findPlayerView(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun keepsScreenOn(view: View): Boolean {
        var currentView: View? = view
        while (currentView != null) {
            if (currentView.keepScreenOn) return true
            currentView = currentView.parent as? View
        }
        return false
    }
    private class FixtureOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
