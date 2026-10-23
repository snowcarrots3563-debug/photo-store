package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.model.VersionUpdate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic state only: no coordinator, system picker or private media is opened. */
class PopupIntegrationUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()

    @Test fun sourceOptionsUseOneFloatingCardAndDispatchEachRealCallbackOnce() {
        val events = mutableListOf<GalleryEvent>()
        var archives = 0
        var folders = 0
        compose.activity.setContent {
            DTheme { GalleryDialogs(GalleryUiState(dialog = GalleryDialog.AddMenu), null, events::add,
                onArchive = { archives++ }, onFolder = { folders++ }) }
        }
        compose.onNodeWithTag("dPopupCard").assertIsDisplayed()
        compose.onNodeWithText("从相册导入").performScrollTo().performClick()
        compose.onNodeWithText("从文件导入").performScrollTo().performClick()
        compose.onNodeWithText("从压缩包导入").performScrollTo().performClick()
        compose.onNodeWithText("从文件夹导入").performScrollTo().performClick()
        assertEquals(listOf(GalleryEvent.OpenImportTarget(), GalleryEvent.OpenImportTarget(fromFiles = true)), events)
        assertEquals(1, archives)
        assertEquals(1, folders)
    }

    @Test fun aboutRetainsRealApplicationDescriptionInsideFloatingCard() {
        compose.activity.setContent { DTheme { GalleryDialogs(GalleryUiState(dialog = GalleryDialog.About), null, {}) } }
        compose.onNodeWithTag("dPopupCard").assertIsDisplayed()
        compose.onNodeWithText("私人画师图库，收藏内容保存在本机，不上传。").assertIsDisplayed()
        compose.onNodeWithText("当前可用").assertIsDisplayed()
    }

    @Test fun notesRetainCompleteHistoryAndCloseCallback() {
        var closes = 0
        val notes = listOf(
            VersionUpdate(2, "0.0.2", "2026-10-22", listOf("【优化】中性样例二")),
            VersionUpdate(1, "0.0.1", "2026-10-21", listOf("【新增】中性样例一")),
        )
        compose.activity.setContent { DTheme { UpdateNotesDialog(notes, showAll = true) { closes++ } } }
        compose.onNodeWithTag("dPopupCard").assertIsDisplayed()
        compose.onNodeWithText("• 【优化】中性样例二").assertIsDisplayed()
        compose.onNodeWithText("• 【新增】中性样例一").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭应用更新日志").performClick()
        assertEquals(1, closes)
    }

    @Test fun draftSurvivesUpdatesAndTransitionsToAnotherRealForm() {
        lateinit var current: MutableState<GalleryUiState>
        compose.activity.setContent {
            current = remember { mutableStateOf(GalleryUiState(dialog = GalleryDialog.AddArtist("草稿"))) }
            DTheme { GalleryDialogs(current.value, null, { event ->
                if (event is GalleryEvent.DraftChanged) current.value = current.value.copy(dialog = GalleryDialog.AddArtist(event.draft))
            }) }
        }
        compose.onNodeWithTag("nameInput").performTextReplacement("保留输入")
        compose.runOnIdle { current.value = current.value.copy(fieldError = "中性校验提示") }
        compose.onNodeWithTag("nameInput").assertTextEquals("保留输入")
        compose.runOnIdle { current.value = current.value.copy(dialog = GalleryDialog.RenameArtist("sample", "改名草稿"), fieldError = null) }
        compose.onNodeWithTag("nameInput").assertTextEquals("改名草稿")
        compose.onNodeWithText("保存名称").performScrollTo().assertIsEnabled()
    }

    @Test fun savingKeepsCloseAndSubmitDisabled() {
        compose.activity.setContent { DTheme { GalleryDialogs(GalleryUiState(dialog = GalleryDialog.RenameArtist("sample", "样例"), isSaving = true), null, {}) } }
        compose.onNodeWithContentDescription("取消").assertIsNotEnabled()
        compose.onNodeWithTag("nameInput").assertIsNotEnabled()
        compose.onNodeWithText("保存中…").performScrollTo().assertIsNotEnabled()
    }

    @Test fun realLongHistoryKeepsItsLastChangeAndCloseReachableInShortViewport() {
        var closes = 0
        val notes = listOf(VersionUpdate(1, "0.0.1", "2026-10-22", (1..20).map { "【优化】中性历史 $it" }))
        compose.activity.setContent { DTheme {
            Box(Modifier.size(320.dp, 260.dp)) {
                UpdateNotesContent(notes, true, { closes++ }, Modifier, 1f)
            }
        } }
        compose.onNodeWithText("• 【优化】中性历史 20").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("关闭").assertIsDisplayed().performClick()
        assertEquals(1, closes)
    }
}
