package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic state and event inspection only; never opens a picker or reads user media. */
class DefaultImportDialogTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private val events = mutableListOf<GalleryEvent>()
    private lateinit var current: MutableState<GalleryUiState>
    private val artist = ArtistSummary("artist", "画师样本", 2, 0)
    private val default = CollectionSummary("default", artist.id, "改名默认样本", false, 0, 0, isDefault = true)
    private val ordinary = CollectionSummary("ordinary", artist.id, "普通样本", false, 0, 0)
    private fun state(target: GalleryDialog.ImportTarget) = GalleryUiState(artists = listOf(artist), route = GalleryRoute.Artist(artist.id), dialog = target)
    private fun show(state: GalleryUiState, screen: Boolean = false) {
        compose.activity.setContent {
            current = remember { mutableStateOf(state) }
            DTheme {
                if (screen) ArtistDetailScreen(current.value, 20.dp, null, null, events::add)
                else GalleryDialogs(current.value, null, events::add)
            }
        }
    }

    @Test fun emptyArtistImportOpensExistingSourceMenuAndUsesActualDefaultName() {
        show(GalleryUiState(loadState = LoadState.Ready, mediaLoadState = LoadState.Ready,
            route = GalleryRoute.Artist(artist.id), artistDetail = ArtistDetail(artist, listOf(default, ordinary))), screen = true)
        compose.onNodeWithText("导入到改名默认样本，也可以选择其他作品集。").assertIsDisplayed()
        compose.onNodeWithText("导入").performClick()
        assertEquals(GalleryEvent.OpenDialog(GalleryDialog.AddMenu), events.last())
    }

    @Test fun artistMenuProvidesAlbumAndFileSourcesWithCurrentArtist() {
        show(GalleryUiState(route = GalleryRoute.Artist(artist.id), artistDetail = ArtistDetail(artist, listOf(default)), dialog = GalleryDialog.ArtistMenu))
        compose.onNodeWithText("从相册导入").performClick()
        assertEquals(GalleryEvent.OpenImportTarget(artistId = artist.id), events.last())
        compose.onNodeWithText("从文件导入").performClick()
        assertEquals(GalleryEvent.OpenImportTarget(artistId = artist.id, fromFiles = true), events.last())
    }

    @Test fun lazyDefaultEnablesFilePickerWithoutCreatingOrSelectingACollection() {
        show(state(GalleryDialog.ImportTarget(artist.id, null, fromFiles = true, allowDefault = true)))
        compose.onNodeWithText("默认图集").assertIsDisplayed()
        compose.onNodeWithText("选择文件").assertIsEnabled().performClick()
        assertEquals(GalleryEvent.ConfirmImportTarget, events.last())
        assertTrue(events.none { it is GalleryEvent.ImportCollectionSelected })
    }

    @Test fun renamedFlaggedDefaultIsOptionalAndCanBeChosenAgainAfterAnotherTarget() {
        show(state(GalleryDialog.ImportTarget(artist.id, null)).copy(importTargetCollections = listOf(default, ordinary)))
        compose.onNodeWithText("改名默认样本").assertIsDisplayed()
        compose.onNodeWithText("选择媒体").assertIsEnabled()
        compose.onNodeWithText("改名默认样本").performClick()
        compose.onNodeWithText("普通样本").performClick()
        assertEquals(GalleryEvent.ImportCollectionSelected(ordinary.id), events.last())
        compose.runOnIdle { current.value = current.value.copy(dialog = GalleryDialog.ImportTarget(artist.id, ordinary.id)) }
        compose.onNodeWithText("使用改名默认样本").assertIsEnabled().performClick()
        assertEquals(GalleryEvent.ImportCollectionSelected(null), events.last())
        compose.runOnIdle { current.value = current.value.copy(dialog = GalleryDialog.ImportTarget(artist.id, null, toUnassigned = true)) }
        compose.onNodeWithText("使用改名默认样本").performClick()
        assertEquals(GalleryEvent.ImportCollectionSelected(null), events.last())
    }

    @Test fun oldManagementTargetAndExistingReferencesStillRequireExplicitCollection() {
        show(state(GalleryDialog.ImportTarget(artist.id, null)))
        compose.onNodeWithText("选择媒体").assertIsNotEnabled()
        compose.runOnIdle {
            current.value = current.value.copy(dialog = GalleryDialog.ImportTarget(artist.id, null, allowDefault = true),
                importTargetCollections = listOf(default, ordinary), pendingAddAssetIds = listOf("neutral-image"))
        }
        compose.onNodeWithText("加入所选").assertIsNotEnabled()
        compose.onNodeWithText("使用改名默认样本").assertDoesNotExist()
        compose.runOnIdle { current.value = current.value.copy(dialog = GalleryDialog.ImportTarget(artist.id, ordinary.id, allowDefault = true)) }
        compose.onNodeWithText("加入所选").assertIsEnabled()
    }

    @Test fun defaultCollectionLongPressCannotSelectItWhileOrdinarySelectionKeepsItsDragAndNavigation() {
        show(GalleryUiState(loadState = LoadState.Ready, mediaLoadState = LoadState.Ready, route = GalleryRoute.Artist(artist.id),
            artistTab = ArtistTab.Collections, artistDetail = ArtistDetail(artist, listOf(default, ordinary))), screen = true)
        compose.onNodeWithText(default.name).performTouchInput { longClick() }
        assertTrue(events.none { it is GalleryEvent.StartEntitySelection })
        compose.onNodeWithText(ordinary.name).performTouchInput { longClick() }
        assertEquals(GalleryEvent.StartEntitySelection(EntitySelectionKind.Collections, ordinary.id), events.last())
        compose.runOnIdle { current.value = current.value.copy(entitySelectionKind = EntitySelectionKind.Collections, selectedEntityIds = setOf(ordinary.id)) }
        compose.onNodeWithTag("collectionCheckbox:${default.id}", useUnmergedTree = true).assertIsNotEnabled()
        compose.onNodeWithContentDescription("长按拖动排序：${default.name}").assertIsEnabled()
        compose.onNodeWithText(default.name).performClick()
        assertEquals(GalleryEvent.OpenCollection(default.id, artist.id), events.last())
    }
}
