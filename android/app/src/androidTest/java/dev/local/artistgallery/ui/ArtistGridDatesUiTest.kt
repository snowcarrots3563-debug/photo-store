package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.local.artistgallery.data.ArtistImageLayout
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.model.*
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic Compose state only; no gallery database, files, or persisted preferences are used. */
class ArtistGridDatesUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private val events = mutableListOf<GalleryEvent>()
    private lateinit var current: MutableState<GalleryUiState>
    private lateinit var visible: MutableState<Boolean>
    private val zone = ZoneId.systemDefault()
    private val firstDay = LocalDate.of(2026, 10, 12).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val nextDay = LocalDate.of(2026, 10, 13).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val collection = CollectionSummary("collection", "artist", "作品集样本", false, 8, 0)
    private fun media(index: Int, time: Long = firstDay) =
        MediaAssetSummary("image$index", "$index.png", MediaType.IMAGE, 6000L + index, index, "$index.png", "key$index", time)

    @Test fun bothGridSizesShowOneDateHeadingAndKeepSquareThumbnailColumns() {
        val images = List(7) { media(it) }
        show(images, ArtistImageLayout.Medium)
        compose.onNodeWithTag("artistDateHeader:image0").assertTextEquals("2026年10月12日")
        images.drop(1).forEach { compose.onNodeWithTag("artistDateHeader:${it.id}").assertDoesNotExist() }
        val small = tile("image0").fetchSemanticsNode()
        val fifth = tile("image4").fetchSemanticsNode()
        assertEquals(small.size.width, small.size.height)
        assertEquals(small.positionInRoot.y, fifth.positionInRoot.y, 1f)
        compose.onNodeWithText("0.png").assertDoesNotExist()
        compose.runOnIdle { current.value = current.value.copy(browseOptions = current.value.browseOptions.copy(imageLayout = ArtistImageLayout.Large)) }
        val large = tile("image0").fetchSemanticsNode()
        val fourth = tile("image3").fetchSemanticsNode()
        assertEquals(large.size.width, large.size.height)
        assertTrue(large.size.width > small.size.width)
        assertTrue(fourth.positionInRoot.y > large.positionInRoot.y)
        compose.onNodeWithTag("artistDateHeader:image0").assertTextEquals("2026年10月12日")
    }

    @Test fun nextDatesFirstImageStartsAtTheLeftWithoutExpandingThePreviousGap() {
        val images = listOf(media(0), media(1), media(2, nextDay))
        show(images, ArtistImageLayout.Large)
        listOf(ArtistImageLayout.Large, ArtistImageLayout.Medium).forEach { layout ->
            compose.runOnIdle { current.value = current.value.copy(browseOptions = current.value.browseOptions.copy(imageLayout = layout)) }
            val first = tile("image0").fetchSemanticsNode()
            val second = tile("image1").fetchSemanticsNode()
            val next = tile("image2").fetchSemanticsNode()
            assertEquals(first.size.width, next.size.width)
            assertEquals(first.positionInRoot.y, second.positionInRoot.y, 1f)
            assertEquals(first.positionInRoot.x, next.positionInRoot.x, 1f)
            assertTrue(next.positionInRoot.y > first.positionInRoot.y + first.size.height)
            compose.onNodeWithTag("artistDateHeader:image2").assertTextEquals("2026年10月13日")
        }
    }

    @Test fun sizeAndNameSortingShowsMatchingCaptionsWithoutMovingImagesByDate() {
        val images = listOf(media(2, nextDay), media(0), media(3, nextDay), media(1))
        show(images, ArtistImageLayout.Medium, MediaSort.SizeAscending)
        listOf(MediaSort.SizeAscending, MediaSort.SizeDescending, MediaSort.NameAscending, MediaSort.NameDescending).forEach { sort ->
            val sorted = sortArtistImages(images, sort)
            compose.runOnIdle {
                visible.value = true
                current.value = current.value.copy(
                    browseOptions = current.value.browseOptions.copy(artistImageSorts = mapOf("artist" to sort))).withPreparedMedia(sorted)
            }
            val positions = sorted.map { image ->
                compose.onNodeWithTag("artistDateHeader:${image.id}").assertDoesNotExist()
                compose.onNodeWithTag("artistDate:${image.id}").assertTextEquals(if (image.createdAt == firstDay) "2026.10.12" else "2026.10.13")
                tile(image.id).fetchSemanticsNode().positionInRoot.x
            }
            assertEquals(positions.sorted(), positions)
            tile(sorted.first().id).performClick()
            assertEquals(GalleryEvent.OpenMedia(sorted.first().id), events.last { it !is GalleryEvent.RememberScrollPosition })
        }
    }

    @Test fun legacyMissingTimeShowsUnknownBothAsAHeadingAndAsACaption() {
        val images = listOf(media(0, 0), media(1, -1), media(2))
        show(images)
        compose.onNodeWithTag("artistDateHeader:image0").assertTextEquals("日期未知")
        compose.onNodeWithTag("artistDateHeader:image1").assertDoesNotExist()
        compose.onNodeWithTag("artistDateHeader:image2").assertTextEquals("2026年10月12日")
        compose.runOnIdle { current.value = current.value.copy(browseOptions = current.value.browseOptions.copy(artistImageSorts = mapOf("artist" to MediaSort.NameAscending))) }
        compose.onNodeWithTag("artistDate:image0").assertTextEquals("日期未知")
        compose.onNodeWithTag("artistDate:image1").assertTextEquals("日期未知")
        compose.onNodeWithTag("artistDate:image2").assertTextEquals("2026.10.12")
    }

    @Test fun smallGridDateWrapsAtLargeFontWithoutClippingOrCoveringTheImage() {
        show(listOf(media(0)), ArtistImageLayout.Medium, MediaSort.NameAscending, fontScale = 1.8f, width = 320.dp)
        val caption = compose.onNodeWithTag("artistDate:image0").performScrollTo().assertTextEquals("2026.10.12")
        val layouts = mutableListOf<TextLayoutResult>()
        caption.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> assertTrue(action(layouts)) }
        assertEquals("2026.10.12", layouts.single().layoutInput.text.text)
        assertFalse(layouts.single().hasVisualOverflow)
        val image = tile("image0").fetchSemanticsNode()
        val date = caption.fetchSemanticsNode()
        assertEquals(image.size.width, image.size.height)
        assertTrue(date.positionInRoot.y >= image.positionInRoot.y + image.size.height)
        assertTrue(date.size.height >= layouts.single().size.height)
    }

    @Test fun returningFromAnImageRestoresADateSectionGridPosition() {
        verifyReturnPosition(ArtistImageLayout.Large, MediaSort.AddedDescending)
    }

    @Test fun returningFromAnImageRestoresACaptionGridPosition() {
        verifyReturnPosition(ArtistImageLayout.Medium, MediaSort.NameDescending)
    }

    @Test fun gridLongPressKeepsDateTextAndTapsToggleSelection() {
        val images = listOf(media(0), media(1))
        show(images, ArtistImageLayout.Medium, MediaSort.NameAscending)
        tile("image0").performTouchInput { longClick() }
        tile("image0").assertIsOn()
        compose.onNodeWithTag("artistDate:image0").assertTextEquals("2026.10.12")
        tile("image1").performClick().assertIsOn()
        compose.onNodeWithText("已选择 2 项").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("artistDate:image0").assertTextEquals("2026.10.12")
        assertTrue(events.none { it is GalleryEvent.OpenMedia || it is GalleryEvent.OpenDeleteMedia })
    }

    @Test fun listAndCollectionTabKeepTheirExistingPresentationWithoutDates() {
        val images = listOf(media(0))
        show(images, ArtistImageLayout.List)
        compose.onNodeWithText("0.png").assertIsDisplayed()
        compose.onNodeWithTag("artistDateHeader:image0").assertDoesNotExist()
        compose.onNodeWithTag("artistDate:image0").assertDoesNotExist()
        compose.runOnIdle { current.value = current.value.copy(artistTab = ArtistTab.Collections,
            browseOptions = current.value.browseOptions.copy(imageLayout = ArtistImageLayout.Large)) }
        compose.onNodeWithText(collection.name).assertIsDisplayed()
        compose.onNodeWithTag("artistDateHeader:image0").assertDoesNotExist()
        compose.onNodeWithTag("artistDate:image0").assertDoesNotExist()
    }

    @Test fun emptyGridStillShowsTheExistingImportPrompt() {
        show(emptyList())
        compose.onNodeWithText("还没有图片").assertIsDisplayed()
        compose.onNodeWithText("日期未知").assertDoesNotExist()
        compose.onNodeWithText("导入").performClick()
        assertEquals(GalleryEvent.OpenDialog(GalleryDialog.AddMenu), events.last())
    }

    private fun tile(id: String) = compose.onNodeWithTag("artistTile:$id")

    private fun verifyReturnPosition(layout: ArtistImageLayout, sort: MediaSort) {
        val images = sortArtistImages(List(90) { media(it, firstDay + (it / 12) * 86_400_000L) }, sort)
        show(images, layout, sort)
        val targetId = buildArtistGridRows(images, sort, layout.columns, zone)[7].tiles.first().media.id
        compose.onNodeWithTag("artistMediaList").performScrollToIndex(7)
        val before = tile(targetId).assertIsDisplayed().fetchSemanticsNode().positionInRoot.y
        tile(targetId).performClick()
        val openIndex = events.indexOfLast { it is GalleryEvent.OpenMedia }
        assertTrue(events[openIndex - 1] is GalleryEvent.RememberScrollPosition)
        compose.runOnIdle { assertTrue(current.value.scrollPositions.getValue("artist:artist:Images:${layout.name}:${sort.name}").index >= 7) }
        compose.runOnIdle {
            current.value = current.value.copy(loadState = LoadState.Loading, mediaLoadState = LoadState.Loading, ).withPreparedMedia(emptyList())
            visible.value = true
        }
        compose.onNodeWithTag("artistMediaList").assertDoesNotExist()
        compose.runOnIdle { current.value = current.value.copy(loadState = LoadState.Ready, mediaLoadState = LoadState.Ready, ).withPreparedMedia(images) }
        assertEquals(before, tile(targetId).assertIsDisplayed().fetchSemanticsNode().positionInRoot.y, 1f)
    }

    private fun show(
        images: List<MediaAssetSummary>,
        layout: ArtistImageLayout = ArtistImageLayout.Large,
        sort: MediaSort = MediaSort.Default,
        fontScale: Float = 1f,
        width: Dp = 360.dp,
    ) {
        val initial = GalleryUiState(loadState = LoadState.Ready, mediaLoadState = LoadState.Ready,
            route = GalleryRoute.Artist("artist"),
            artistDetail = ArtistDetail(ArtistSummary("artist", "画师样本", 1, images.size), listOf(collection)),
            browseOptions = BrowseOptions(imageLayout = layout, artistImageSorts = mapOf("artist" to sort))).withPreparedMedia(images)
        compose.activity.setContent {
            current = remember { mutableStateOf(initial) }
            visible = remember { mutableStateOf(true) }
            val holder = rememberSaveableStateHolder()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                DTheme {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.width(width).fillMaxHeight()) {
                            if (visible.value) holder.SaveableStateProvider("artist") {
                                ArtistDetailScreen(current.value, 18.dp, null, null) { event ->
                                    events.add(event)
                                    val old = current.value
                                    current.value = when (event) {
                                        is GalleryEvent.RememberScrollPosition -> old.copy(scrollPositions = old.scrollPositions +
                                            (event.key to GalleryScrollPosition(event.index, event.offset)))
                                        is GalleryEvent.StartMediaSelection -> old.copy(mediaSelectionMode = true, selectedMediaIds = setOf(event.assetId))
                                        is GalleryEvent.ToggleMediaSelection -> old.copy(selectedMediaIds = if (event.assetId in old.selectedMediaIds)
                                            old.selectedMediaIds - event.assetId else old.selectedMediaIds + event.assetId)
                                        GalleryEvent.ExitMediaSelection -> old.copy(mediaSelectionMode = false, selectedMediaIds = emptySet())
                                        else -> old
                                    }
                                    if (event is GalleryEvent.OpenMedia) visible.value = false
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
