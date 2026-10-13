package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.local.artistgallery.fixture.UiFixtureActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic confirmation state only; no gallery records or media files are touched. */
class EntityDeleteDialogUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private val events = mutableListOf<GalleryEvent>()

    @Test fun artistsRequireAnExplicitConfirmationAndWarnAboutSharedCopies() {
        show(GalleryDialog.DeleteArtists(listOf("a", "b")))
        compose.onNodeWithText("确认删除选中的 2 位画师？").assertExists()
        compose.onNodeWithText("共享副本也会彻底删除", substring = true).assertExists()
        assertEquals(emptyList<GalleryEvent>(), events)
        compose.onNodeWithText("确认删除").performClick()
        assertEquals(listOf(GalleryEvent.ConfirmDeleteSelectedEntities), events)
    }

    @Test fun cancelCollectionsDoesNotRequestDeletion() {
        show(GalleryDialog.DeleteCollections(listOf("c")))
        compose.onNodeWithText("确认删除选中的 1 个作品集？").assertExists()
        compose.onNodeWithText("取消").performClick()
        assertEquals(listOf(GalleryEvent.DismissDialog), events)
    }

    @Test fun savingDisablesDuplicateConfirmationAndCancellation() {
        show(GalleryDialog.DeleteCollections(listOf("c")), saving = true)
        compose.onNodeWithText("正在删除").assertIsNotEnabled()
        compose.onNodeWithText("取消").assertIsNotEnabled()
        compose.onNodeWithContentDescription("取消").assertIsNotEnabled()
        assertEquals(emptyList<GalleryEvent>(), events)
    }

    private fun show(dialog: GalleryDialog, saving: Boolean = false) {
        compose.activity.setContent {
            DTheme { GalleryDialogs(GalleryUiState(dialog = dialog, isSaving = saving), null, events::add) }
        }
    }
}
