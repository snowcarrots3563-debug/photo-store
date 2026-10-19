package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import dev.local.artistgallery.fixture.UiFixtureActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic UI states only; no user media or device gallery operations. */
class DeletionFeedbackUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private val events = mutableListOf<GalleryEvent>()

    @Test fun deletingUsesCenteredSpinnerEvenAfterItsConfirmationWasCleared() {
        show(GalleryUiState(isSaving = true, isDeleting = true))
        compose.onNodeWithText("正在删除…").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertExists()
        val window = compose.onNodeWithTag("deletion-window").fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("deletion-progress").fetchSemanticsNode().boundsInRoot
        assertEquals(window.center.x, card.center.x, 2f)
        assertEquals(window.center.y, card.center.y, 2f)
        assertTrue(card.width < window.width)
        assertTrue(card.height < window.height / 2)
    }

    @Test fun deletingReplacesConfirmationAndBlocksOutsideAndBackDismissal() {
        show(GalleryUiState(dialog = GalleryDialog.DeleteSelectedMedia(listOf("a", "b")), isSaving = true, isDeleting = true))
        compose.onNodeWithText("确认删除").assertDoesNotExist()
        compose.onNodeWithText("取消").assertDoesNotExist()
        compose.onNodeWithContentDescription("取消").assertDoesNotExist()
        compose.onNodeWithTag("deletion-window").performTouchInput { click(Offset(1f, 1f)) }
        pressBack()
        compose.onNodeWithText("正在删除…").assertExists()
        assertTrue(events.isEmpty())
    }

    @Test fun ordinarySaveDoesNotShowDeletingProgress() {
        show(GalleryUiState(dialog = GalleryDialog.RenameArtist("a", "画师"), isSaving = true))
        compose.onNodeWithTag("deletion-progress").assertDoesNotExist()
        compose.onNodeWithText("正在删除…").assertDoesNotExist()
        compose.onNodeWithText("保存中…").assertExists()
    }

    @Test fun completedNoticeContainsAppIconAndCountAboveBottomNavigation() {
        compose.activity.setContent {
            DTheme {
                Box(Modifier.fillMaxSize().testTag("gallery-body")) {
                    Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 32.dp, vertical = 18.dp).testTag("bottom-navigation")) {
                        DBottomNavigation(false, events::add)
                    }
                    DeletionCompleteNotice(2)
                }
            }
        }
        compose.onNodeWithText("2项已删除").assertIsDisplayed()
        compose.onNodeWithTag("deletion-app-icon", useUnmergedTree = true).assertExists()
        val body = compose.onNodeWithTag("gallery-body").fetchSemanticsNode().boundsInRoot
        val notice = compose.onNodeWithTag("deletion-complete").fetchSemanticsNode().boundsInRoot
        val navigation = compose.onNodeWithTag("bottom-navigation").fetchSemanticsNode().boundsInRoot
        assertEquals(body.center.x, notice.center.x, 2f)
        assertTrue(notice.bottom < navigation.top)
        assertTrue(notice.top > body.center.y)
        assertTrue(notice.width < body.width)
        assertTrue(events.isEmpty())
    }

    private fun show(state: GalleryUiState) {
        compose.activity.setContent { DTheme { GalleryDialogs(state, null, events::add) } }
    }
}
