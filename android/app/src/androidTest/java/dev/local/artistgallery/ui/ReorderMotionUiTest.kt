package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic state and rows only; controlled-clock Android assertions need runtime execution. */
@OptIn(ExperimentalFoundationApi::class)
class ReorderMotionUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private lateinit var reorder: ReorderRowsState
    private lateinit var enabled: MutableState<Boolean>
    private val commits = mutableListOf<List<String>>()
    private val events = mutableListOf<GalleryEvent>()

    @Test fun neighborsSpringWithoutPullingTheDraggedRowOrReversingItsOrder() {
        showRows()
        val a = top("visual:a")
        val b = top("visual:b")
        val delta = b - a + height("visual:b") * .7f
        compose.runOnIdle { reorder.start("a", a + 30f); reorder.move(delta) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(48)
        val moving = top("visual:b")
        assertTrue("The neighboring row must move through intermediate positions", moving > a + 1f && moving < b - 1f)
        assertEquals(a + delta, top("visual:a"), 2f)
        compose.mainClock.advanceTimeBy(800)
        compose.runOnIdle { assertEquals(listOf("b", "a", "c", "d"), reorder.order) }
        assertEquals(a + delta, top("visual:a"), 2f)
        compose.runOnIdle { reorder.move(-delta) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(48)
        assertTrue("Reversing the drag must animate the neighbor back", top("visual:b") > a + 1f && top("visual:b") < b - 1f)
        assertEquals(a, top("visual:a"), 2f)
        compose.runOnIdle { reorder.cancel() }
    }

    @Test fun releaseAndCancellationPreserveTheFirstFrameThenLandWithoutExtraCommits() {
        showRows()
        val a = top("visual:a")
        val delta = top("visual:b") - a + height("visual:b") * .7f
        compose.runOnIdle { reorder.start("a", a + 30f); reorder.move(delta); reorder.finish { commits.add(it) } }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(a + delta, top("visual:a"), 2f)
        compose.mainClock.advanceTimeBy(80)
        val moving = top("visual:a")
        val target = height("visual:b") + a
        assertTrue("Released row must land gradually", moving > target && moving < a + delta)
        compose.mainClock.advanceTimeBy(1600)
        assertEquals(target, top("visual:a"), 2f)
        compose.runOnIdle { assertNull(reorder.settlingId); assertEquals(1, commits.size) }
        compose.runOnIdle { reorder.start("a", target + 30f); reorder.move(a - target); reorder.cancel() }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(a, top("visual:a"), 2f)
        compose.mainClock.advanceTimeBy(1600)
        assertEquals(target, top("visual:a"), 2f)
        compose.runOnIdle { assertNull(reorder.settlingId); assertEquals(1, commits.size) }
    }

    @Test fun pickupDuringLandingAndLeavingSelectionDoNotLeaveAnOldAnimation() {
        showRows()
        val a = top("visual:a")
        compose.runOnIdle { reorder.start("a", a + 30f); reorder.move(25f); reorder.finish { commits.add(it) } }
        compose.mainClock.advanceTimeBy(80)
        val midway = top("visual:a")
        compose.runOnIdle { reorder.start("a", midway + 30f) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(midway, top("visual:a"), 2f)
        compose.runOnIdle { enabled.value = false }
        compose.mainClock.advanceTimeBy(1600)
        compose.runOnIdle { assertNull(reorder.draggedId); assertNull(reorder.settlingId); assertTrue(commits.isEmpty()) }
        assertEquals(a, top("visual:a"), 2f)
    }

    @Test fun artistListUsesNeighborMotionAndStillCommitsOnlyOnRelease() {
        showScreen(false)
        assertActualListMotion("artist", "artist0", "artist1")
        assertEquals(1, events.filterIsInstance<GalleryEvent.ReorderArtists>().size)
        assertTrue(events.none { it is GalleryEvent.OpenArtist || it is GalleryEvent.ToggleEntitySelection })
    }

    @Test fun collectionListUsesNeighborMotionAndStillCommitsOnlyOnRelease() {
        showScreen(true)
        assertActualListMotion("collection", "collection0", "collection1")
        assertEquals(1, events.filterIsInstance<GalleryEvent.ReorderCollections>().size)
        assertTrue(events.none { it is GalleryEvent.OpenCollection || it is GalleryEvent.ToggleEntitySelection })
    }

    private fun assertActualListMotion(kind: String, firstId: String, secondId: String) {
        val first = compose.onNodeWithTag("reorder:$firstId")
        val a = top("reorder:$firstId")
        val b = top("reorder:$secondId")
        first.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        first.performTouchInput { moveBy(Offset(0f, (b - a) * 1.2f), delayMillis = 16) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(48)
        val moving = top("reorder:$secondId")
        assertTrue("$kind neighbor must spring instead of snapping", moving > a + 1f && moving < b - 1f)
        assertEquals(a + (b - a) * 1.2f, top("reorder:$firstId"), 2f)
        assertTrue(events.none { it is GalleryEvent.ReorderArtists || it is GalleryEvent.ReorderCollections })
        first.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1600)
    }

    private fun showRows() {
        compose.mainClock.autoAdvance = false
        compose.activity.setContent {
            enabled = remember { mutableStateOf(true) }
            val list = rememberLazyListState()
            reorder = rememberReorderRows(listOf("a", "b", "c", "d"), enabled.value, list)
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) { DTheme {
                LazyColumn(Modifier.requiredSize(320.dp, 480.dp).onGloballyPositioned { reorder.viewport = it.boundsInRoot() }, state = list) {
                    itemsIndexed(reorder.order, key = { _, id -> id }) { _, id ->
                        ReorderRow(reorder, id, Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null,
                            placementSpec = if (reorder.draggedId == id || reorder.settlingId == id) null else spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = .85f))) {
                            Row(Modifier.fillMaxWidth().height(if (id == "b") 100.dp else 60.dp).testTag("visual:$id")) {
                                Text(id, Modifier.weight(1f))
                                ReorderHandle(reorder, id, id, enabled.value) { commits.add(it) }
                            }
                        }
                    }
                }
            } }
        }
        compose.waitForIdle()
    }

    private fun showScreen(collections: Boolean) {
        compose.mainClock.autoAdvance = false
        val artists = List(4) { ArtistSummary("artist$it", "画师 $it", 4, 2) }
        val albums = List(4) { CollectionSummary("collection$it", "artist0", "作品集 $it", false, 2, 0) }
        val state = GalleryUiState(loadState = LoadState.Ready, mediaLoadState = LoadState.Ready, artists = artists,
            artistDetail = ArtistDetail(artists.first(), albums), artistTab = ArtistTab.Collections,
            route = if (collections) GalleryRoute.Artist("artist0") else GalleryRoute.Home,
            entitySelectionKind = if (collections) EntitySelectionKind.Collections else EntitySelectionKind.Artists,
            selectedEntityIds = setOf(if (collections) "collection0" else "artist0")).withPreparedMedia(emptyList())
        compose.activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) { DTheme { Box(Modifier.requiredSize(360.dp, 900.dp)) {
                if (collections) ArtistDetailScreen(state, 18.dp, null, null, events::add)
                else ArtistHomeScreen(state, 18.dp, null, {}, null, null, events::add)
            } } }
        }
        compose.waitForIdle()
    }

    private fun top(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
    private fun height(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
}
