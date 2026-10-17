package dev.local.artistgallery.ui

import android.content.ContentResolver
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.data.MediaLibraryRepository
import dev.local.artistgallery.data.importing.MediaFileStore
import dev.local.artistgallery.data.preview.AssetReader
import dev.local.artistgallery.fixture.FixtureGalleryRepository
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.model.MediaAssetSummary
import dev.local.artistgallery.model.MediaType
import dev.local.artistgallery.ui.viewer.ImageViewerScreen
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic viewer state; no real share target, gallery database, or private media is used. */
class ImageShareUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private val shared = mutableListOf<MediaAssetSummary>()
    private val exported = mutableListOf<String>()
    private val productionHarnesses = mutableListOf<ShareHarness>()
    private val images = listOf(
        MediaAssetSummary("first", "1.png", MediaType.IMAGE, 100, 0, "1.png", "first"),
        MediaAssetSummary("video", "2.mp4", MediaType.VIDEO, 100, 1, "2.mp4", "video"),
        MediaAssetSummary("last", "3.gif", MediaType.GIF, 100, 2, "3.gif", "last"),
    )

    @Test fun shareUsesTheSettledPageEvenBeforeParentSelectionUpdates() {
        showViewer(updateSelection = false)
        compose.onNodeWithTag("imagePager").performTouchInput { swipeLeft() }
        compose.onNodeWithContentDescription("图片操作").performClick()
        compose.onNodeWithText("分享这张图片").assertIsEnabled().performClick()
        assertEquals(listOf(images.last()), shared)
        assertTrue(exported.isEmpty())
        compose.onNodeWithText("分享这张图片").assertDoesNotExist()
    }

    @Test fun missingShareCallbackIsDisabledAndExportStillWorks() {
        showViewer(canShare = false)
        compose.onNodeWithContentDescription("图片操作").performClick()
        compose.onNodeWithText("分享这张图片").assertIsNotEnabled()
        compose.onNodeWithText("导出这张图片").performClick()
        assertEquals(listOf("first"), exported)
        assertTrue(shared.isEmpty())
    }

    @Test fun closingTheActionsDoesNotShareOrExport() {
        showViewer()
        compose.onNodeWithContentDescription("图片操作").performClick()
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithText("分享这张图片").assertDoesNotExist()
        assertTrue(shared.isEmpty())
        assertTrue(exported.isEmpty())
    }

    @Test fun exportContinuesToUseTheCurrentImageAndDoesNotShare() {
        showViewer()
        compose.onNodeWithTag("imagePager").performTouchInput { swipeLeft() }
        compose.onNodeWithContentDescription("图片操作").performClick()
        compose.onNodeWithText("导出这张图片").performClick()
        assertEquals(listOf("last"), exported)
        assertTrue(shared.isEmpty())
    }

    @Test fun sharingIsDisabledWhileThePagerSettles() {
        showViewer()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("imagePager").performTouchInput { swipeLeft() }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("图片操作").performClick()
        compose.onNodeWithText("分享这张图片").assertIsNotEnabled()
        assertTrue(shared.isEmpty())
        compose.mainClock.autoAdvance = true
    }

    @Test fun cardNavigationSharesTheNewImageAndNeverTheVideo() {
        showViewer(cardEffects = true)
        compose.onNodeWithContentDescription("下一张").performClick()
        compose.onNodeWithContentDescription("图片操作").performClick()
        compose.onNodeWithText("分享这张图片").performClick()
        assertEquals(listOf(images.last()), shared)
    }

    @Test fun originalFourArgumentViewerKeepsItsMenuAndDisablesSharing() {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            DTheme { ImageViewerScreen(GalleryUiState().withPreparedMedia(images), "first", null) {} }
        } }
        compose.onNodeWithContentDescription("图片操作").performClick()
        compose.onNodeWithText("加入其他作品集").assertIsEnabled()
        compose.onNodeWithText("导出这张图片").assertIsEnabled()
        compose.onNodeWithText("分享这张图片").assertIsNotEnabled()
    }

    @Test fun productionShareKeepsSettledBWhenParentStillShowsAThenCatchesUpDuringIo() {
        val harness = showProductionViewer()
        compose.runOnIdle { harness.owner.registry.currentState = Lifecycle.State.CREATED }
        compose.onNodeWithTag("imagePager").performTouchInput { swipeLeft() }
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        assertEquals(GalleryOverlay.CardViewer("last"), harness.model.state.value.overlay)
        startGatedShare(harness)
        compose.runOnIdle { harness.owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
        harness.release.countDown()
        compose.waitUntil(5_000) { harness.launches.size == 1 }
        val send = requireNotNull(harness.launches.single().getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        val uri = requireNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals("image/gif", send.type)
        assertTrue(uri.path!!.endsWith(harness.media.last().storageKey))
        assertEquals("3.gif", uri.getQueryParameter("displayName"))
    }

    @Test fun productionShareReadsLiveVmPageAndSuppressesStaleRequestBeforeParentCatchesUp() {
        val harness = showProductionViewer()
        startGatedShare(harness)
        compose.runOnIdle {
            harness.owner.registry.currentState = Lifecycle.State.CREATED
            harness.model.onEvent(GalleryEvent.OpenMedia("last"))
        }
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        harness.release.countDown()
        assertTrue(harness.headerRead.await(5, TimeUnit.SECONDS))
        compose.onNodeWithContentDescription("图片操作").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("分享这张图片").fetchSemanticsNodes().any {
                !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
        }
        assertTrue(harness.launches.isEmpty())
        assertEquals(GalleryOverlay.CardViewer("last"), harness.model.state.value.overlay)
    }

    @Test fun productionShareSuppressesRequestAfterViewerClosesEvenWithFrozenParent() {
        val harness = showProductionViewer()
        startGatedShare(harness)
        compose.runOnIdle {
            harness.owner.registry.currentState = Lifecycle.State.CREATED
            harness.model.onEvent(GalleryEvent.CloseOverlay)
        }
        harness.release.countDown()
        assertTrue(harness.headerRead.await(5, TimeUnit.SECONDS))
        compose.onNodeWithContentDescription("图片操作").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("分享这张图片").fetchSemanticsNodes().any {
                !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
        }
        assertTrue(harness.launches.isEmpty())
        assertEquals(GalleryOverlay.None, harness.model.state.value.overlay)
    }

    @Test fun disposingProductionViewerCancelsPendingShare() {
        val harness = showProductionViewer()
        startGatedShare(harness)
        compose.runOnIdle { harness.model.onEvent(GalleryEvent.CloseOverlay) }
        compose.onNodeWithTag("imagePager").assertDoesNotExist()
        harness.release.countDown()
        assertTrue(harness.headerRead.await(5, TimeUnit.SECONDS))
        compose.waitForIdle()
        assertTrue(harness.launches.isEmpty())
    }

    @After fun cleanOwnedProductionOriginals() {
        productionHarnesses.forEach { harness ->
            harness.release.countDown()
            if (harness.ioEntered.count == 0L) harness.headerRead.await(5, TimeUnit.SECONDS)
            compose.activityRule.scenario.onActivity { activity ->
                harness.model.onEvent(GalleryEvent.CloseOverlay)
                harness.owner.registry.currentState = Lifecycle.State.DESTROYED
                activity.setContent { }
            }
            val root = requireNotNull(compose.activity.getExternalFilesDir(null)).canonicalFile
            harness.media.filter { it.type != MediaType.VIDEO }.forEach { item ->
                val file = harness.files.originalFile(item.storageKey)
                check(file.canonicalFile == File(root, "originals/${item.storageKey}"))
                assertTrue(!file.exists() || file.delete())
            }
        }
    }

    private fun startGatedShare(harness: ShareHarness) {
        compose.onNodeWithContentDescription("图片操作").performClick()
        compose.onNodeWithText("分享这张图片").performClick()
        assertTrue(harness.ioEntered.await(5, TimeUnit.SECONDS))
        assertTrue(harness.launches.isEmpty())
    }

    private fun showProductionViewer(): ShareHarness {
        val harness = ShareHarness().also(productionHarnesses::add)
        compose.activityRule.scenario.onActivity { activity ->
            harness.owner.registry.currentState = Lifecycle.State.RESUMED
            val shareContext = object : ContextWrapper(activity) {
                override fun startActivity(intent: Intent) { harness.launches.add(intent) }
                override fun getContentResolver(): ContentResolver {
                    if (Looper.myLooper() != Looper.getMainLooper()) {
                        if (harness.ioCalls.incrementAndGet() == 1) {
                            harness.ioEntered.countDown()
                            check(harness.release.await(10, TimeUnit.SECONDS))
                        } else harness.headerRead.countDown()
                    }
                    return super.getContentResolver()
                }
            }
            harness.files = MediaFileStore(shareContext)
            harness.media = images.map { item -> item.copy(storageKey = UUID.randomUUID().toString()) }
            harness.media.filter { it.type != MediaType.VIDEO }.forEach { item ->
                val header = if (item.type == MediaType.GIF) "GIF89a".toByteArray() + ByteArray(26)
                    else byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) + ByteArray(24)
                harness.files.originalFile(item.storageKey).apply { parentFile!!.mkdirs(); writeBytes(header) }
            }
            val repository = FixtureGalleryRepository()
            val media = Proxy.newProxyInstance(MediaLibraryRepository::class.java.classLoader, arrayOf(MediaLibraryRepository::class.java)) { _, method, _ ->
                when (method.name) {
                    "observeArtistImages", "observeCollectionMedia" -> flowOf(harness.media)
                    "observeUnassignedMedia", "observeLatestImportBatches" -> flowOf(emptyList<Any>())
                    else -> error("Unexpected synthetic repository call: ${method.name}")
                }
            } as MediaLibraryRepository
            activity.viewModelStore.clear()
            harness.model = ViewModelProvider(activity, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = GalleryViewModel(repository, SavedStateHandle(), media) as T
            })[GalleryViewModel::class.java]
            harness.model.onEvent(GalleryEvent.OpenArtist("a1"))
            activity.setContent {
                CompositionLocalProvider(LocalContext provides shareContext, LocalLifecycleOwner provides harness.owner) {
                    GalleryApp(repository, mediaRepository = media, assetReader = AssetReader(shareContext, harness.files))
                }
            }
        }
        compose.waitUntil(5_000) { harness.model.state.value.mediaLoadState == LoadState.Ready && harness.model.state.value.mediaItems.size == 3 }
        compose.runOnIdle { harness.model.onEvent(GalleryEvent.OpenMedia("first")) }
        compose.onNodeWithTag("imagePager").assertIsDisplayed()
        return harness
    }

    private class ShareHarness {
        val owner = TestLifecycleOwner()
        val ioEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val headerRead = CountDownLatch(1)
        val ioCalls = AtomicInteger()
        val launches = CopyOnWriteArrayList<Intent>()
        lateinit var files: MediaFileStore
        lateinit var model: GalleryViewModel
        lateinit var media: List<MediaAssetSummary>
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }

    private fun showViewer(canShare: Boolean = true, updateSelection: Boolean = true, cardEffects: Boolean = false) {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            var selected by remember { mutableStateOf("first") }
            DTheme { ImageViewerScreen(
                GalleryUiState( browseOptions = BrowseOptions(cardEffects = cardEffects)).withPreparedMedia(images), selected, null,
                onEvent = { event ->
                    if (updateSelection) when (event) {
                        is GalleryEvent.OpenMedia -> selected = event.assetId
                        is GalleryEvent.StepViewer -> selected = images.last().id
                        else -> Unit
                    }
                },
                onExport = exported::add,
                onShare = if (canShare) ({ item -> shared.add(item) }) else null,
            ) }
        } }
    }
}
