package dev.local.artistgallery.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.artistgallery.GalleryApplication
import dev.local.artistgallery.model.SaveResult
import dev.local.artistgallery.ui.GalleryApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase2UiAcceptanceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun importTargetSelectorsChooseThenCancelWithoutStartingPicker() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as GalleryApplication
        val repository = app.repository
        val existingBatches = app.mediaRepository.latestImportBatches().map { it.id }.toSet()
        val suffix = UUID.randomUUID().toString().take(8)
        val artistName = "阶段二测试画师 $suffix"
        val collectionName = "阶段二测试作品集 $suffix"
        val artistId = savedId(repository.createArtist(artistName))
        savedId(repository.createCollection(artistId, collectionName))
        compose.setContent {
            GalleryApp(repository, mediaRepository = app.mediaRepository, mediaImporter = app.mediaImportCoordinator, previewDecoder = app.previewDecoder, assetReader = app.assetReader)
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(artistName).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithContentDescription("添加").performClick()
        compose.onNodeWithText("从相册导入").performClick()
        compose.onNodeWithText("选择导入位置").assertIsDisplayed()

        compose.onNodeWithText("选择画师").performClick()
        compose.onNode(hasText(artistName) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("选择作品集").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("选择作品集").performClick()
        compose.onNode(hasText(collectionName) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText(collectionName).assertIsDisplayed()

        compose.onNodeWithText(collectionName).performClick()
        compose.onNode(hasText(collectionName) and isSelected() and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText(collectionName) and isSelected() and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("取消").performClick()

        compose.waitUntil(5_000) { compose.onAllNodesWithText("选择导入位置").fetchSemanticsNodes().isEmpty() }
        assertTrue(app.mediaRepository.latestImportBatches().map { it.id }.toSet() == existingBatches)
    }

    private fun savedId(result: SaveResult): String {
        assertTrue(result is SaveResult.Saved)
        return (result as SaveResult.Saved).id
    }
}
