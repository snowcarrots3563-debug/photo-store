package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.model.ArtistSummary
import dev.local.artistgallery.model.CoverRef
import dev.local.artistgallery.model.SampleCoverKey
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** In-memory states and solid sample covers only; Android assertions are not device acceptance. */
class ArtistHomeLoadingUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private lateinit var current: MutableState<GalleryUiState>
    private val events = mutableListOf<GalleryEvent>()
    private val artists = List(5) { ArtistSummary("artist$it", "画师 $it", 2, 3, CoverRef.Sample(SampleCoverKey.Scenery1)) }

    @Test fun skeletonSweepsWithoutMovingTheExistingFrameOrChangingRowSize() {
        show(LoadState.Loading)
        val row = compose.onNodeWithTag("artistHomeLoadingRow:0", useUnmergedTree = true)
        val loadingBounds = row.fetchSemanticsNode().boundsInRoot
        val headingBounds = compose.onNodeWithText("画师").fetchSemanticsNode().boundsInRoot
        val searchBounds = compose.onNodeWithTag("artistSearch").fetchSemanticsNode().boundsInRoot
        assertFalse(row.fetchSemanticsNode().config.contains(SemanticsActions.OnClick))
        compose.onNodeWithText("画师 0").assertDoesNotExist()
        val before = row.captureToImage()
        compose.mainClock.advanceTimeBy(350)
        val after = row.captureToImage()
        assertTrue("The light band must visibly move", changedSamples(before, after) > 20)
        assertEquals(loadingBounds, row.fetchSemanticsNode().boundsInRoot)

        ready()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("artistHomeLoading").assertDoesNotExist()
        assertEquals(loadingBounds, compose.onNodeWithTag("artistRow:artist0").fetchSemanticsNode().boundsInRoot)
        assertEquals(headingBounds, compose.onNodeWithText("画师").fetchSemanticsNode().boundsInRoot)
        assertEquals(searchBounds, compose.onNodeWithTag("artistSearch").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun actualCompletionReplacesTheSkeletonImmediatelyAndThenRevealsTheCard() {
        show(LoadState.Loading)
        ready()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("artistHomeLoading").assertDoesNotExist()
        val row = compose.onNodeWithTag("artistRow:artist0")
        row.assertExists()
        val initial = coverContrast(row.captureToImage())
        compose.mainClock.advanceTimeBy(400)
        val settled = coverContrast(row.captureToImage())
        assertTrue("The real cover must become fully visible", settled > .9f)
        assertTrue("An actual loading transition must fade the card in", settled > initial + .3f)
    }

    @Test fun readyEntryAndOrdinaryUpdatesKeepTheCardVisibleWithoutReplayingLoading() {
        show(LoadState.Ready)
        compose.onNodeWithTag("artistHomeLoading").assertDoesNotExist()
        val row = compose.onNodeWithTag("artistRow:artist0")
        assertTrue(coverContrast(row.captureToImage()) > .9f)
        compose.runOnIdle { current.value = current.value.copy(artists = artists.map { it.copy(imageCount = 4) }) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("artistHomeLoading").assertDoesNotExist()
        assertTrue(coverContrast(row.captureToImage()) > .9f)
        compose.onNodeWithText("画师 0").performClick()
        assertEquals(GalleryEvent.OpenArtist("artist0"), events.last())
    }

    @Test fun failedLoadKeepsTheExistingRetryActionWithoutAnAnimatedSkeleton() {
        show(LoadState.Failed)
        compose.onNodeWithTag("artistHomeLoading").assertDoesNotExist()
        compose.onNodeWithText("暂时无法读取图库").assertIsDisplayed()
        compose.onNodeWithText("重试").performClick()
        assertEquals(listOf(GalleryEvent.RetryLoad), events)
    }

    private fun show(loadState: LoadState) {
        compose.mainClock.autoAdvance = false
        val initial = GalleryUiState(loadState = loadState, artists = if (loadState == LoadState.Ready) artists else emptyList())
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
                current = remember { mutableStateOf(initial) }
                DTheme {
                    Box(Modifier.size(360.dp, 640.dp).background(DColors.Background)) {
                        ArtistHomeScreen(current.value, 18.dp, null, {},
                            { Box(Modifier.fillMaxSize().background(Color.Red)) }, null, { events.add(it) })
                    }
                }
        } }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
    }

    private fun ready() = compose.runOnIdle { current.value = current.value.copy(loadState = LoadState.Ready, artists = artists) }

    private fun coverContrast(image: ImageBitmap): Float {
        val x = (46 * compose.activity.resources.displayMetrics.density).roundToInt().coerceAtMost(image.width - 1)
        val color = image.toPixelMap()[x, image.height / 2]
        return color.red - color.green
    }

    private fun changedSamples(before: ImageBitmap, after: ImageBitmap): Int {
        val a = before.toPixelMap()
        val b = after.toPixelMap()
        var changed = 0
        for (y in 0 until before.height step 4) for (x in 0 until before.width step 4) {
            if (abs(a[x, y].red - b[x, y].red) > .005f) changed++
        }
        return changed
    }
}
