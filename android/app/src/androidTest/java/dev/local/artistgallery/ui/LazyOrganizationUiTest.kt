package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Large synthetic lists only; these checks never open a real library or import media. */
class LazyOrganizationUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private val artists = List(2_000) { ArtistSummary("artist$it", "画师 $it", 1, 2) }
    private val collections = List(1_000) { CollectionSummary("collection$it", "artist0", "作品集 $it", false, 2, 0, isDefault = it == 0) }
    private lateinit var current: MutableState<GalleryUiState>
    private lateinit var target: MutableState<String?>
    private lateinit var visible: MutableState<Boolean>
    private val events = mutableListOf<GalleryEvent>()
    private var scrolledCount = 0

    @Test fun twoThousandArtistsComposeOnlyNearbyRowsAndFindAFarTarget() {
        showHome()
        assertBoundedRows("artistRow:")
        compose.onNodeWithTag("artistRow:artist1999").assertDoesNotExist()
        compose.runOnIdle { target.value = "artist1950" }
        compose.onNodeWithTag("artistRow:artist1950").assertIsDisplayed()
        compose.onNodeWithTag("artistRow:artist0").assertDoesNotExist()
        assertBoundedRows("artistRow:")
        compose.runOnIdle { assertEquals(1, scrolledCount) }
    }

    @Test fun homeLazyPositionSurvivesItsNavigationStateHolder() {
        showHome()
        compose.onNodeWithTag("artistHomeList").performScrollToIndex(1_502)
        compose.onNodeWithTag("artistRow:artist1500").assertIsDisplayed()
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithTag("artistRow:artist1500").assertIsDisplayed()
        assertBoundedRows("artistRow:")
    }

    @Test fun aNewFarArtistCanBeTargetedInTheSameStateUpdate() {
        showHome()
        compose.runOnIdle {
            current.value = current.value.copy(artists = artists + ArtistSummary("newArtist", "新画师", 0, 0))
            target.value = "newArtist"
        }
        compose.onNodeWithTag("artistRow:newArtist").assertIsDisplayed()
        assertBoundedRows("artistRow:")
        compose.runOnIdle { assertEquals(1, scrolledCount) }
    }

    @Test fun thousandCollectionsAreSeparateLazyRowsAndDefaultCollectionStaysUnselectable() {
        compose.activity.setContent {
            current = remember { mutableStateOf(GalleryUiState(loadState = LoadState.Ready, mediaLoadState = LoadState.Ready,
                route = GalleryRoute.Artist("artist0"), artistTab = ArtistTab.Collections,
                artistDetail = ArtistDetail(artists.first().copy(collectionCount = collections.size), collections)).withPreparedMedia(emptyList())) }
            DTheme { ArtistDetailScreen(current.value, 18.dp, null, null, ::event) }
        }
        assertBoundedRows("collectionRow:")
        compose.onNodeWithTag("collectionRow:collection999").assertDoesNotExist()
        compose.onNodeWithTag("collectionRow:collection1").performTouchInput { longClick() }
        compose.onNodeWithTag("collectionCheckbox:collection0").assertIsNotEnabled()
        compose.onNodeWithTag("reorder:collection0").assertIsDisplayed()
        compose.onNodeWithTag("artistMediaList").performScrollToIndex(990)
        compose.onNodeWithTag("collectionRow:collection990").assertIsDisplayed()
        compose.onNodeWithTag("collectionRow:collection0").assertDoesNotExist()
        assertBoundedRows("collectionRow:")
        compose.onNodeWithTag("collectionRow:collection990").performClick()
        compose.runOnIdle { assertEquals(setOf("collection1", "collection990"), current.value.selectedEntityIds) }
        assertTrue(events.none { it is GalleryEvent.OpenCollection })
    }

    @Test fun lazyRowsReleaseTheirBoundsAndMixedHeightDragKeepsTheHandleUnderTheFinger() {
        lateinit var reorder: ReorderRowsState
        val commits = mutableListOf<List<String>>()
        compose.activity.setContent {
            val ids = remember { List(1_000) { "row$it" } }
            val listState = rememberLazyListState()
            reorder = rememberReorderRows(ids, true, listState)
            DTheme {
                LazyColumn(Modifier.height(400.dp).fillMaxWidth().testTag("mixedRows")
                    .onGloballyPositioned { reorder.viewport = it.boundsInRoot() }, state = listState) {
                    itemsIndexed(reorder.order, key = { _, id -> id }) { _, id ->
                        val number = id.removePrefix("row").toInt()
                        ReorderRow(reorder, id) {
                            Row(Modifier.fillMaxWidth().height(if (number % 2 == 0) 70.dp else 110.dp)) {
                                Text(id, Modifier.weight(1f))
                                ReorderHandle(reorder, id, id, true) { commits.add(it) }
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("mixedRows").performScrollToIndex(500)
        compose.runOnIdle {
            assertFalse(reorder.rowBounds.containsKey("row0"))
            assertTrue(reorder.rowBounds.size < 40)
        }
        compose.onNodeWithTag("mixedRows").performScrollToIndex(0)
        val handle = compose.onNodeWithTag("reorder:row0")
        val before = handle.fetchSemanticsNode().positionInRoot.y
        val distance = compose.onNodeWithTag("reorder:row1").fetchSemanticsNode().positionInRoot.y - before
        compose.mainClock.autoAdvance = false
        handle.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        handle.performTouchInput { moveBy(Offset(0f, distance * 1.5f), delayMillis = 100) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(before + distance * 1.5f, handle.fetchSemanticsNode().positionInRoot.y, 2f)
        handle.performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, commits.size); assertEquals(listOf("row1", "row0"), commits.single().take(2)) }
        handle.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        handle.performTouchInput { moveBy(Offset(0f, -distance * 1.5f), delayMillis = 100) }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(listOf("row0", "row1"), reorder.order.take(2)) }
        handle.performTouchInput { cancel() }
        compose.mainClock.autoAdvance = true
        compose.runOnIdle {
            assertEquals(1, commits.size)
            assertNull(reorder.draggedId)
            assertEquals(listOf("row1", "row0"), reorder.order.take(2))
        }
    }

    private fun assertBoundedRows(prefix: String) {
        val rows = compose.onAllNodes(SemanticsMatcher("row tag starts with $prefix") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
        }).fetchSemanticsNodes()
        assertTrue("Expected a bounded set of composed rows, found ${rows.size}", rows.size in 1..40)
    }

    private fun showHome() {
        compose.activity.setContent {
            current = remember { mutableStateOf(GalleryUiState(loadState = LoadState.Ready, artists = artists)) }
            target = remember { mutableStateOf<String?>(null) }
            visible = remember { mutableStateOf(true) }
            val holder = rememberSaveableStateHolder()
            DTheme {
                if (visible.value) holder.SaveableStateProvider("home") {
                    ArtistHomeScreen(current.value, 18.dp, target.value, { scrolledCount++; target.value = null }, null, null, ::event)
                }
            }
        }
    }

    private fun event(change: GalleryEvent) {
        events += change
        current.value = when (change) {
            is GalleryEvent.StartEntitySelection -> current.value.copy(entitySelectionKind = change.kind, selectedEntityIds = setOf(change.id))
            is GalleryEvent.ToggleEntitySelection -> current.value.copy(selectedEntityIds =
                if (change.id in current.value.selectedEntityIds) current.value.selectedEntityIds - change.id else current.value.selectedEntityIds + change.id)
            else -> current.value
        }
    }
}
