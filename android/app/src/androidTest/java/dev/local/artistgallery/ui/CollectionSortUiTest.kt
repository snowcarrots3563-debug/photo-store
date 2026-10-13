package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic state only; no gallery, files or device preferences are changed. */
class CollectionSortUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private lateinit var current: MutableState<GalleryUiState>
    private val events = mutableListOf<GalleryEvent>()
    private val media = List(20) { MediaAssetSummary("image$it", "$it.png", MediaType.IMAGE, 6000L + it, it, "$it.png", "key$it", it.toLong()) }
    private val collection = CollectionSummary("c", "a", "图集样本", false, media.size, 0)

    @Test fun entryOpensSevenChoicesAndSelectionClosesWithIndependentPreference() {
        show()
        compose.onNodeWithContentDescription("图集排序").performClick()
        assertTrue(events[events.lastIndex - 1] is GalleryEvent.RememberScrollPosition)
        compose.onNodeWithText("默认顺序（文件名称升序）").performScrollTo().assertIsSelected()
        MediaSort.entries.filter { it != MediaSort.Default }.forEach { compose.onNodeWithText(it.label).performScrollTo().assertExists() }
        compose.onNodeWithText(MediaSort.NameDescending.label).performClick()
        compose.onNodeWithText("图集排序").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(MediaSort.NameDescending, current.value.browseOptions.collectionMediaSorts["c"])
            assertTrue(current.value.browseOptions.artistImageSorts.isEmpty())
        }
    }

    @Test fun backCancelsWithoutChangingDefaultOrderAndKeepsDefaultScrollKey() {
        show()
        compose.onNodeWithContentDescription("图集排序").performClick()
        pressBack()
        compose.onNodeWithText("默认顺序（文件名称升序）").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(media, current.value.mediaItems)
            assertTrue(current.value.browseOptions.collectionMediaSorts.isEmpty())
            assertTrue(events.filterIsInstance<GalleryEvent.RememberScrollPosition>().any { it.key == "collection:c" })
            assertTrue(events.none { it is GalleryEvent.SetCollectionMediaSort })
        }
    }

    @Test fun selectionAndSavingDisableSorting() {
        show(selecting = true)
        compose.onNodeWithContentDescription("图集排序").assertIsNotEnabled()
        compose.runOnIdle { current.value = current.value.copy(mediaSelectionMode = false, isSaving = true) }
        compose.onNodeWithContentDescription("图集排序").assertIsNotEnabled()
    }

    @Test fun returningToDefaultKeepsItsVisitedPositionAndNaturalNameOrder() {
        show()
        compose.onNodeWithTag("collectionMediaList").performScrollToIndex(8)
        compose.onNodeWithContentDescription("图集排序").performClick()
        compose.runOnIdle { assertTrue(current.value.scrollPositions.getValue("collection:c").index >= 8) }
        compose.onNodeWithText(MediaSort.NameDescending.label).performScrollTo().performClick()
        compose.onNodeWithText("19.png").assertIsDisplayed()
        compose.onNodeWithTag("collectionMediaList").performScrollToIndex(5)
        compose.onNodeWithContentDescription("图集排序").performClick()
        compose.runOnIdle { assertTrue(current.value.scrollPositions.getValue("collection:c:NameDescending").index >= 5) }
        compose.onNodeWithText("默认顺序（文件名称升序）").performScrollTo().performClick()
        compose.onNodeWithText("6.png").assertIsDisplayed()
        compose.onNodeWithText("0.png").assertDoesNotExist()
        compose.runOnIdle { assertEquals(media, current.value.mediaItems) }
    }

    private fun show(selecting: Boolean = false) {
        current = mutableStateOf(GalleryUiState(route = GalleryRoute.Collection("c", "a"), collection = collection,
            loadState = LoadState.Ready, mediaLoadState = LoadState.Ready, mediaItems = sortCollectionMedia(media, MediaSort.Default), mediaSelectionMode = selecting))
        compose.activity.setContent {
            val event: (GalleryEvent) -> Unit = {
                events += it
                current.value = when (it) {
                    is GalleryEvent.OpenDialog -> current.value.copy(dialog = it.dialog)
                    GalleryEvent.DismissDialog -> current.value.copy(dialog = GalleryDialog.None)
                    is GalleryEvent.SetCollectionMediaSort -> current.value.copy(dialog = GalleryDialog.None,
                        mediaItems = sortCollectionMedia(media, it.sort),
                        browseOptions = BrowseOptions(collectionMediaSorts = mapOf("c" to it.sort)))
                    is GalleryEvent.RememberScrollPosition -> current.value.copy(scrollPositions = current.value.scrollPositions +
                        (it.key to GalleryScrollPosition(it.index, it.offset)))
                    else -> current.value
                }
            }
            DTheme {
                CollectionScreen(current.value, 18.dp, null, event)
                GalleryDialogs(current.value, null, event)
            }
        }
    }
}
