package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.local.artistgallery.data.ArtistImageLayout
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.model.ArtistDetail
import dev.local.artistgallery.model.ArtistSummary
import dev.local.artistgallery.model.MediaAssetSummary
import dev.local.artistgallery.model.MediaSort
import dev.local.artistgallery.model.MediaType
import dev.local.artistgallery.model.CollectionSummary
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Controlled route snapshots only; no gallery, files, or preferences. */
class ArtistDetailLoadingUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private lateinit var current: MutableState<GalleryUiState>
    private val events = mutableListOf<GalleryEvent>()
    private val artist = ArtistSummary("artist", "加载样本", 3, 12)

    @Test fun loadingKeepsKnownChromeInPlaceUntilTheRealListIsReady() {
        show(loading(ArtistImageLayout.List))
        val tags = listOf("artistHeader", "artistSummary", "artistTabs", "artistSection")
        val before = tags.associateWith { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        compose.onNodeWithText(artist.name).assertIsDisplayed()
        compose.onNodeWithText("3 个作品集 · 12 张图片").assertIsDisplayed()
        compose.onNodeWithTag("artistDetailLoading").assertIsDisplayed()
        compose.onNodeWithTag("artistMediaList").assertDoesNotExist()
        ready()
        tags.forEach { assertEquals(before.getValue(it), compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot) }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("artistDetailLoading").assertDoesNotExist()
        compose.onNodeWithTag("artistMediaList").assertIsDisplayed()
    }

    @Test fun threeAndFiveColumnLoadingUseTheSameCellWidthsAsTheirRealRows() {
        show(loading(ArtistImageLayout.Large))
        for (layout in listOf(ArtistImageLayout.Large, ArtistImageLayout.Medium)) {
            compose.runOnIdle { current.value = loading(layout) }
            compose.mainClock.advanceTimeByFrame()
            val before = compose.onNodeWithTag("artistLoadingTile:0:0").fetchSemanticsNode().boundsInRoot
            ready()
            val after = compose.onNodeWithTag("artistTile:image0").fetchSemanticsNode().boundsInRoot
            assertEquals(before.left, after.left, .5f)
            assertEquals(before.width, after.width, .5f)
            assertEquals(before.height, after.height, .5f)
            compose.mainClock.advanceTimeBy(800)
        }
    }

    @Test fun failureStopsLoadingAndRetryDispatchesOnceWithoutLosingTheHeader() {
        show(loading(ArtistImageLayout.Large))
        compose.runOnIdle { current.value = current.value.copy(mediaLoadState = LoadState.Failed) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("artistDetailLoading").assertDoesNotExist()
        compose.onNodeWithText("暂时无法读取图库").assertIsDisplayed()
        compose.onNodeWithText(artist.name).assertIsDisplayed()
        compose.onNodeWithText("重试").performClick()
        compose.runOnIdle { assertEquals(1, events.count { it == GalleryEvent.RetryLoad }) }
        compose.runOnIdle { current.value = loading(ArtistImageLayout.Large) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("artistDetailLoading").assertIsDisplayed()
        ready()
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("artistDetailLoading").assertDoesNotExist()
    }

    @Test fun switchingToAnUnknownArtistNeverReusesThePreviousNameOrInventsCounts() {
        show(loading(ArtistImageLayout.List))
        compose.runOnIdle { current.value = current.value.copy(route = GalleryRoute.Artist("unknown")) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(artist.name).assertDoesNotExist()
        compose.onNodeWithText("0 个作品集 · 0 张图片").assertDoesNotExist()
        compose.onNodeWithTag("artistHeader").assertIsDisplayed()
        compose.onNodeWithTag("artistDetailLoading").assertIsDisplayed()
        compose.runOnIdle { assertTrue(events.none { it is GalleryEvent.OpenMedia }) }
    }

    @Test fun aReadFailureAfterScrollingStillRemembersTheLastRealListPosition() {
        val media = List(80) { MediaAssetSummary("image$it", "$it.png", MediaType.IMAGE, 100, it, "$it.png", "fixture$it") }
        show(loading(ArtistImageLayout.List).copy(artistDetail = ArtistDetail(artist, emptyList())).withPreparedMedia(media)
            .copy(mediaLoadState = LoadState.Ready))
        compose.onNodeWithTag("artistMediaList").performScrollToIndex(30)
        compose.runOnIdle { current.value = current.value.copy(mediaLoadState = LoadState.Failed) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("暂时无法读取图库").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle {
            val saved = events.filterIsInstance<GalleryEvent.RememberScrollPosition>().last()
            assertEquals(30, saved.index)
            assertEquals(GalleryEvent.NavigateBack, events.last())
        }
    }

    @Test fun measuredLoadingRowsMatchLongNamesAtFractionalDensityAndLargeFont() {
        val media = List(3) { MediaAssetSummary("image$it", "很长的两行文件名称用于验证放大字体下的行高$it.png", MediaType.IMAGE, 100, it, "$it.png", "fixture$it") }
        val initial = loading(ArtistImageLayout.List).copy(artistDetail = ArtistDetail(artist, emptyList())).withPreparedMedia(media)
        show(initial.copy(mediaLoadState = LoadState.Loading), Density(2.625f, 1.5f))
        val before = compose.onNodeWithTag("artistLoadingRow:0").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { current.value = current.value.copy(mediaLoadState = LoadState.Ready) }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val after = compose.onNodeWithTag("artistSwipe:image0").fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
    }

    @Test fun collectionLoadingUsesTheRealGroupedRowGeometry() {
        val collections = List(3) { CollectionSummary("collection$it", artist.id, "作品集$it", false, 2, 0) }
        val initial = loading(ArtistImageLayout.Large).copy(artistTab = ArtistTab.Collections,
            artistDetail = ArtistDetail(artist, collections)).withPreparedMedia(emptyList())
        show(initial.copy(mediaLoadState = LoadState.Loading))
        val before = compose.onNodeWithTag("artistLoadingCollection:0").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { current.value = current.value.copy(mediaLoadState = LoadState.Ready) }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val after = compose.onNodeWithTag("collectionRow:collection0").fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
        compose.onNodeWithTag("artistMediaList").assertIsDisplayed()
    }

    @Test fun datePlaceholdersMatchTheRealTimeAndNameSortRowsWithLargeFont() {
        show(loading(ArtistImageLayout.Medium), Density(2.625f, 1.5f))
        for (sort in listOf(MediaSort.NameAscending, MediaSort.AddedDescending)) {
            compose.runOnIdle { current.value = loading(ArtistImageLayout.Medium).copy(
                browseOptions = BrowseOptions(imageLayout = ArtistImageLayout.Medium,
                    artistImageSorts = mapOf(artist.id to sort))) }
            compose.mainClock.advanceTimeByFrame()
            val before = compose.onNodeWithTag("artistLoadingTile:0:0").fetchSemanticsNode().boundsInRoot
            ready()
            val after = compose.onNodeWithTag("artistTile:image0").fetchSemanticsNode().boundsInRoot
            assertEquals(before, after)
            compose.mainClock.advanceTimeBy(800)
        }
    }

    @Test fun selectedMediaAndCollectionLoadingKeepTheirNarrowTextGeometry() {
        val media = listOf(MediaAssetSummary("image0", "非常长的多选文件名称验证大字体换行后的行高.png", MediaType.IMAGE, 100, 0, "0.png", "fixture"))
        val collection = CollectionSummary("collection0", artist.id, "非常长的多选作品集名称验证有勾选框与排序手柄时的换行", false, 1, 0)
        val initial = loading(ArtistImageLayout.List).copy(artistDetail = ArtistDetail(artist, listOf(collection)), mediaSelectionMode = true).withPreparedMedia(media)
        show(initial, Density(2.625f, 1.5f))
        val mediaBefore = compose.onNodeWithTag("artistLoadingRow:0").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { current.value = current.value.copy(mediaLoadState = LoadState.Ready) }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        assertEquals(mediaBefore, compose.onNodeWithTag("artistSelectedRow:image0").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { current.value = current.value.copy(mediaLoadState = LoadState.Loading,
            artistTab = ArtistTab.Collections, mediaSelectionMode = false, entitySelectionKind = EntitySelectionKind.Collections) }
        compose.mainClock.advanceTimeByFrame()
        val collectionBefore = compose.onNodeWithTag("artistLoadingCollection:0").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { current.value = current.value.copy(mediaLoadState = LoadState.Ready) }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        assertEquals(collectionBefore, compose.onNodeWithTag("collectionRow:collection0").fetchSemanticsNode().boundsInRoot)
    }

    private fun loading(layout: ArtistImageLayout) = GalleryUiState(
        route = GalleryRoute.Artist(artist.id), loadState = LoadState.Ready,
        mediaLoadState = LoadState.Loading, artists = listOf(artist),
        browseOptions = BrowseOptions(imageLayout = layout,
            artistImageSorts = mapOf(artist.id to MediaSort.NameAscending)))

    private fun ready() {
        compose.runOnIdle {
            current.value = current.value.copy(mediaLoadState = LoadState.Ready,
                artistDetail = ArtistDetail(artist, emptyList())).withPreparedMedia(List(12) { index ->
                    MediaAssetSummary("image$index", "$index.png", MediaType.IMAGE, 100, index, "$index.png", "fixture$index")
                })
        }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
    }

    private fun show(initial: GalleryUiState, density: Density? = null) {
        compose.mainClock.autoAdvance = false
        current = mutableStateOf(initial)
        compose.activity.setContent { CompositionLocalProvider(LocalDensity provides (density ?: LocalDensity.current)) { DTheme {
            Box(Modifier.size(360.dp, 640.dp)) {
                ArtistDetailScreen(current.value, 18.dp, null, null, events::add)
            }
        } } }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
    }
}
