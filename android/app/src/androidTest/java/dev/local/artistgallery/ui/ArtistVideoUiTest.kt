package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.local.artistgallery.data.ArtistImageLayout
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.model.*
import dev.local.artistgallery.ui.viewer.VideoPlayerScreen
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic Compose state only; no private files, database or device preferences are changed. */
class ArtistVideoUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private lateinit var current: MutableState<GalleryUiState>
    private val events = mutableListOf<GalleryEvent>()
    private val zone = ZoneId.systemDefault()
    private val early = LocalDate.of(2026, 10, 12).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val late = LocalDate.of(2026, 10, 13).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val image = MediaAssetSummary("image", "1.png", MediaType.IMAGE, 2048, 0, "1.png", "image", early)
    private val gif = MediaAssetSummary("gif", "2.gif", MediaType.GIF, 3072, 1, "2.gif", "gif", early)
    private val video = MediaAssetSummary("video", "10.mp4", MediaType.VIDEO, 4096, 2, "10.mp4", "video", late)
    private val mixed = listOf(image, gif, video)

    @Test fun mixedListShowsSeparateCountsAndVideoClickUsesTheExistingPlayerScreen() {
        show()
        compose.onNodeWithTag("artistSummary").assertTextEquals("1 个作品集 · 2 张图片 · 1 个视频")
        compose.onNodeWithText("全部媒体").assertIsDisplayed()
        compose.onNodeWithText("全部媒体 · 3").assertIsDisplayed()
        compose.onNodeWithText("视频 · 4 KB").assertIsDisplayed()
        compose.onNodeWithText(video.name).performClick()
        assertEquals(GalleryEvent.OpenMedia(video.id), events.last { it !is GalleryEvent.RememberScrollPosition })
        compose.onNodeWithText("视频文件暂时无法读取").assertIsDisplayed()
        compose.onNodeWithContentDescription("暂停视频").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(GalleryEvent.CloseOverlay, events.last())
        compose.onNodeWithText(video.name).assertIsDisplayed()
    }

    @Test fun bothGridSizesKeepVideoTilesSquareAndInTheChosenNameOrder() {
        val items = mixed + (3..5).map { number -> image.copy(id = "extra$number", name = "$number.png") }
        show(initial(items, ArtistImageLayout.Medium, MediaSort.NameAscending))
        val small = tile(video.id).fetchSemanticsNode()
        val first = tile(image.id).fetchSemanticsNode()
        assertEquals(small.size.width, small.size.height)
        assertEquals(first.positionInRoot.y, tile("extra5").fetchSemanticsNode().positionInRoot.y, 1f)
        assertTrue(small.positionInRoot.y > first.positionInRoot.y)
        assertEquals(first.positionInRoot.x, small.positionInRoot.x, 1f)
        compose.runOnIdle { current.value = current.value.copy(browseOptions = current.value.browseOptions.copy(imageLayout = ArtistImageLayout.Large)) }
        val large = tile(video.id).fetchSemanticsNode()
        assertTrue(large.size.width > small.size.width)
        assertEquals(large.size.width, large.size.height)
        assertEquals(tile(image.id).fetchSemanticsNode().positionInRoot.y, tile("extra3").fetchSemanticsNode().positionInRoot.y, 1f)
        assertTrue(tile("extra4").fetchSemanticsNode().positionInRoot.y > tile(image.id).fetchSemanticsNode().positionInRoot.y)
        assertTrue(large.positionInRoot.x > tile("extra5").fetchSemanticsNode().positionInRoot.x)
        tile(video.id).performClick()
        assertEquals(GalleryEvent.OpenMedia(video.id), events.last { it !is GalleryEvent.RememberScrollPosition })
    }

    @Test fun videoDatesUseAddedDaySectionsForTimeSortingAndCaptionsForOtherSorting() {
        show(initial(layout = ArtistImageLayout.Medium))
        compose.onNodeWithTag("artistDateHeader:video").assertTextEquals("2026年10月13日")
        compose.onNodeWithTag("artistDateHeader:gif").assertTextEquals("2026年10月12日")
        compose.runOnIdle { current.value = initial(layout = ArtistImageLayout.Medium, sort = MediaSort.SizeDescending) }
        compose.onNodeWithTag("artistDateHeader:video").assertDoesNotExist()
        compose.onNodeWithTag("artistDate:video").assertTextEquals("2026.10.13")
        compose.onNodeWithTag("artistDate:image").assertTextEquals("2026.10.12")
        assertTrue(tile(video.id).fetchSemanticsNode().positionInRoot.x < tile(gif.id).fetchSemanticsNode().positionInRoot.x)
    }

    @Test fun videoOnlyArtistIsNotShownAsAnEmptyImageCollection() {
        show(initial(items = listOf(video)))
        compose.onNodeWithTag("artistSummary").assertTextEquals("1 个作品集 · 0 张图片 · 1 个视频")
        compose.onNodeWithText("全部媒体 · 1").assertIsDisplayed()
        compose.onNodeWithText("还没有图片").assertDoesNotExist()
        compose.onNodeWithText(video.name).assertIsDisplayed()
    }

    @Test fun imageOnlyArtistKeepsExistingImageLabelsAndCounts() {
        show(initial(items = listOf(image, gif)))
        compose.onNodeWithTag("artistSummary").assertTextEquals("1 个作品集 · 2 张图片")
        compose.onNodeWithText("全部图片").assertIsDisplayed()
        compose.onNodeWithText("全部图片 · 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("图片排序").assertIsDisplayed()
        compose.onNodeWithContentDescription("媒体排序").assertDoesNotExist()
    }

    @Test fun homeShowsVideoCountAlongsideImagesWithoutCountingItAsAnImage() {
        val state = initial()
        show(state.copy(route = GalleryRoute.Home, artists = listOf(requireNotNull(state.artistDetail).artist)))
        compose.onNodeWithText("1 个作品集 · 2 张图片 · 1 个视频").assertIsDisplayed()
        compose.onNodeWithText("画师样本").performClick()
        assertEquals(GalleryEvent.OpenArtist("artist"), events.last())
    }

    @Test fun mediaSortDialogShowsTheSameChoicesAndSortsTheVideoWithImages() {
        show()
        compose.onNodeWithContentDescription("媒体排序").performClick()
        compose.onNodeWithText("媒体排序").assertIsDisplayed()
        compose.onNodeWithText(MediaSort.SizeAscending.label).performScrollTo().performClick()
        assertEquals(listOf(GalleryEvent.SetArtistImageSort(MediaSort.SizeAscending)), events.filterIsInstance<GalleryEvent.SetArtistImageSort>())
        compose.runOnIdle { assertEquals(listOf("image", "gif", "video"), current.value.mediaItems.map { it.id }) }
    }

    @Test fun videoLongPressSelectsAndBatchDeleteRequiresConfirmation() {
        show(initial(layout = ArtistImageLayout.Medium))
        tile(video.id).performTouchInput { longClick() }
        tile(video.id).assertIsOn()
        compose.onNodeWithContentDescription("媒体排序").assertIsNotEnabled()
        tile(image.id).performClick().assertIsOn()
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("批量删除媒体").assertIsDisplayed()
        compose.onNode(hasText("已选择 2 项") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        assertTrue(events.none { it is GalleryEvent.OpenMedia || it == GalleryEvent.ConfirmDeleteSelectedMedia })
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("批量删除媒体").assertDoesNotExist()
        tile(video.id).assertIsOn()
    }

    @Test fun collectionVideoTrashUsesVideoLabelAndTypeNeutralDeleteConfirmation() {
        val state = initial()
        show(state.copy(route = GalleryRoute.Collection("collection", "artist"), collection = state.artistDetail?.collections?.single()))
        compose.onNodeWithContentDescription("删除视频").performClick()
        compose.onNodeWithText("删除媒体").assertIsDisplayed()
        compose.onNodeWithText("删除这个图库副本，并移除所有画师及作品集中的引用。因此变空的作品集也会自动删除。相册、文件管理和压缩包里的源文件不受影响。").assertIsDisplayed()
        assertTrue(events.none { it == GalleryEvent.ConfirmDeleteMedia })
        compose.onNodeWithText("取消").performClick()
        compose.onAllNodesWithContentDescription("删除图片").assertCountEquals(2)
    }

    private fun tile(id: String) = compose.onNodeWithTag("artistTile:$id")

    private fun initial(items: List<MediaAssetSummary> = mixed, layout: ArtistImageLayout = ArtistImageLayout.List, sort: MediaSort = MediaSort.Default): GalleryUiState {
        val imageCount = items.count { it.type != MediaType.VIDEO }
        val videoCount = items.count { it.type == MediaType.VIDEO }
        val collection = CollectionSummary("collection", "artist", "作品集样本", false, imageCount, videoCount)
        return GalleryUiState(loadState = LoadState.Ready, mediaLoadState = LoadState.Ready,
            route = GalleryRoute.Artist("artist"), artistDetail = ArtistDetail(ArtistSummary("artist", "画师样本", 1, imageCount, videoCount = videoCount), listOf(collection)),
             browseOptions = BrowseOptions(imageLayout = layout, swipeDelete = false, artistImageSorts = mapOf("artist" to sort))).withPreparedMedia(sortArtistImages(items, sort))
    }

    private fun show(state: GalleryUiState = initial()) {
        compose.activity.setContent {
            current = remember { mutableStateOf(state) }
            val onEvent: (GalleryEvent) -> Unit = { event ->
                events.add(event)
                val old = current.value
                current.value = when (event) {
                    is GalleryEvent.OpenDialog -> old.copy(dialog = event.dialog)
                    GalleryEvent.DismissDialog -> old.copy(dialog = GalleryDialog.None)
                    is GalleryEvent.SetArtistImageSort -> old.copy(dialog = GalleryDialog.None,
                        browseOptions = old.browseOptions.copy(artistImageSorts = mapOf("artist" to event.sort))).withPreparedMedia(sortArtistImages(old.mediaItems, event.sort))
                    is GalleryEvent.OpenMedia -> if (old.mediaSelectionMode) old else old.copy(overlay = GalleryOverlay.VideoPlayer(event.assetId))
                    GalleryEvent.CloseOverlay -> old.copy(overlay = GalleryOverlay.None)
                    is GalleryEvent.StartMediaSelection -> old.copy(mediaSelectionMode = true, selectedMediaIds = setOf(event.assetId))
                    is GalleryEvent.ToggleMediaSelection -> old.copy(selectedMediaIds = if (event.assetId in old.selectedMediaIds) old.selectedMediaIds - event.assetId else old.selectedMediaIds + event.assetId)
                    GalleryEvent.OpenDeleteSelectedMedia -> old.copy(dialog = GalleryDialog.DeleteSelectedMedia(old.mediaItems.filter { it.id in old.selectedMediaIds }.map { it.id }))
                    is GalleryEvent.OpenDeleteMedia -> old.copy(dialog = GalleryDialog.DeleteMedia(event.assetId, old.mediaItems.single { it.id == event.assetId }.name))
                    else -> old
                }
            }
            DTheme {
                when {
                    current.value.overlay is GalleryOverlay.VideoPlayer -> VideoPlayerScreen(current.value, (current.value.overlay as GalleryOverlay.VideoPlayer).assetId, null, onEvent)
                    current.value.route == GalleryRoute.Home -> ArtistHomeScreen(current.value, 18.dp, null, {}, null, null, onEvent)
                    current.value.route is GalleryRoute.Collection -> CollectionScreen(current.value, 18.dp, null, onEvent)
                    else -> ArtistDetailScreen(current.value, 18.dp, null, null, onEvent)
                }
                GalleryDialogs(current.value, null, onEvent)
            }
        }
    }
}
