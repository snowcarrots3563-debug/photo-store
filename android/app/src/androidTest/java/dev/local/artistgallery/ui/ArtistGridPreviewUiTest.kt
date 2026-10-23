package dev.local.artistgallery.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import dev.local.artistgallery.data.ArtistImageLayout
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.data.importing.MediaFileStore
import dev.local.artistgallery.data.preview.AssetReader
import dev.local.artistgallery.data.preview.PreviewDecoder
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.model.ArtistDetail
import dev.local.artistgallery.model.ArtistSummary
import dev.local.artistgallery.model.MediaAssetSummary
import dev.local.artistgallery.model.MediaSort
import dev.local.artistgallery.model.MediaType
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Owned synthetic UUID files only; never opens a gallery database or device preferences. */
class ArtistGridPreviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private val files by lazy { MediaFileStore(compose.activity) }
    private val decoder by lazy { PreviewDecoder(compose.activity.contentResolver, AssetReader(compose.activity, files)) }
    private val keys = mutableListOf<String>()
    private lateinit var current: MutableState<GalleryUiState>
    private lateinit var visible: MutableState<Boolean>
    private lateinit var viewportHeight: MutableState<Dp>

    @After fun cleanup() = runBlocking {
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { if (::visible.isInitialized) visible.value = false }
        compose.waitForIdle()
        keys.forEach { assertTrue(files.deleteOriginal(it)) }
    }

    @Test fun firstExposedViewportAlreadyHasGaussianPreviewsWithoutWarmingWholeArtist() = runBlocking {
        val media = fixtures()
        show(media)
        waitForGrid("image0")
        media.forEach { item ->
            val nodes = compose.onAllNodesWithTag("artistTile:${item.id}").fetchSemanticsNodes()
            if (nodes.any { it.boundsInRoot.height > 0 }) {
                assertNotNull("Exposed tile ${item.id} must already have its preview", decoder.cachedGaussianPreview(item.storageKey))
            }
        }
        assertNull("Entry only prepares a bounded window", decoder.cachedGaussianPreview(media.last().storageKey))
        compose.onNodeWithTag("artistGridPreviewLoading").assertDoesNotExist()
        Unit
    }

    @Test fun restoredGridPreparesItsSavedRowWithoutClampingToTheBeginning() = runBlocking {
        val media = fixtures()
        show(media, savedIndex = 8)
        waitForGrid("image24")
        compose.onNodeWithTag("artistTile:image24").assertIsDisplayed()
        assertNotNull(decoder.cachedGaussianPreview(media[24].storageKey))
        assertNull("Restored entry must not first warm row zero", decoder.cachedGaussianPreview(media[0].storageKey))
        compose.onNodeWithTag("artistTile:image0").assertDoesNotExist()
        compose.onNodeWithTag("artistTile:image24").performClick()
        compose.runOnIdle {
            assertEquals(8, current.value.scrollPositions.getValue(scrollKey).index)
            visible.value = true
        }
        waitForGrid("image24")
        compose.onNodeWithTag("artistTile:image24").assertIsDisplayed()
        Unit
    }

    @Test fun restoringTheLastRowPreparesThePrecedingRowsNeededToFillTheViewport() = runBlocking {
        val media = fixtures().takeLast(30)
        show(media, savedIndex = 9)
        waitForGrid(media.last().id)
        assertNotNull("End restoration fills preceding rows too", decoder.cachedGaussianPreview(media[24].storageKey))
        media.forEach { item ->
            val nodes = compose.onAllNodesWithTag("artistTile:${item.id}").fetchSemanticsNodes()
            if (nodes.any { it.boundsInRoot.height > 0 }) {
                assertNotNull("Every exposed end tile needs its preview", decoder.cachedGaussianPreview(item.storageKey))
            }
        }
        assertNull("End preparation stays bounded", decoder.cachedGaussianPreview(media.first().storageKey))
        Unit
    }

    @Test fun growingTheViewportDoesNotDetachAnAlreadyRevealedListAfterCachePressure() = runBlocking {
        val media = fixtures()
        show(media, height = 400.dp)
        waitForGrid("image0")
        compose.mainClock.advanceTimeBy(800)
        // Evict derived previews with owned files, as can happen while other media is browsed.
        (fixtures() + fixtures()).forEach { decoder.decodeGaussianPreview(it.storageKey) }
        compose.runOnIdle { viewportHeight.value = 600.dp }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("artistMediaList").assertIsDisplayed()
        compose.onNodeWithTag("artistDetailLoading").assertDoesNotExist()
        compose.onNodeWithTag("artistTile:image0").assertIsDisplayed()
        Unit
    }

    private val scrollKey = "artist:artist:Images:Large:NameAscending"

    private fun show(media: List<MediaAssetSummary>, savedIndex: Int = 0, height: Dp = 640.dp) {
        val initial = GalleryUiState(loadState = LoadState.Ready, mediaLoadState = LoadState.Ready,
            route = GalleryRoute.Artist("artist"),
            artistDetail = ArtistDetail(ArtistSummary("artist", "预览样本", 0, media.size), emptyList()),
            browseOptions = BrowseOptions(imageLayout = ArtistImageLayout.Large,
                artistImageSorts = mapOf("artist" to MediaSort.NameAscending)),
            scrollPositions = mapOf(scrollKey to GalleryScrollPosition(savedIndex)),
        ).withPreparedMedia(media)
        compose.mainClock.autoAdvance = false
        compose.activity.setContent {
            current = remember { mutableStateOf(initial) }
            visible = remember { mutableStateOf(true) }
            viewportHeight = remember { mutableStateOf(height) }
            DTheme {
                Box(Modifier.size(360.dp, viewportHeight.value)) {
                    if (visible.value) ArtistDetailScreen(current.value, 18.dp, null, decoder) { event ->
                        when (event) {
                            is GalleryEvent.RememberScrollPosition -> current.value = current.value.copy(
                                scrollPositions = current.value.scrollPositions + (event.key to GalleryScrollPosition(event.index, event.offset)))
                            is GalleryEvent.OpenMedia -> visible.value = false
                            else -> Unit
                        }
                    }
                }
            }
        }
    }

    private fun waitForGrid(id: String) {
        compose.waitUntil(20_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithTag("artistTile:$id").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private suspend fun fixtures(): List<MediaAssetSummary> = List(90) { index ->
        val key = UUID.randomUUID().toString().also { keys.add(it) }
        val staging = UUID.randomUUID().toString()
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(220, 40 + index, 70)) }
        try {
            files.writeToStaging(staging) { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            files.promote(staging, key)
        } finally {
            bitmap.recycle()
            files.deleteStaging(staging)
        }
        MediaAssetSummary("image$index", "$index.png", MediaType.IMAGE, 100, index, "$index.png", key)
    }
}
