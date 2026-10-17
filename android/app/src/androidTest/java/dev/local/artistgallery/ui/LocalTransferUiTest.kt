package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.data.exporting.*
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.model.CollectionSummary
import dev.local.artistgallery.model.MediaAssetSummary
import dev.local.artistgallery.model.MediaType
import dev.local.artistgallery.ui.exporting.LocalTransferContent
import dev.local.artistgallery.ui.viewer.ImageViewerScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Synthetic UI state and callbacks only; no source files, gallery records or preferences are changed. */
class LocalTransferUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private val calls = mutableListOf<String>()

    @Test fun selectionRequiresAtLeastOneCheckedItem() {
        compose.activity.setContent {
            var state by remember { mutableStateOf(LocalTransferState(kind = TransferKind.Images, step = TransferStep.Selection,
                choices = listOf(TransferChoice("image", "样本.png", "图片 · 1 KB")))) }
            DTheme { Column(Modifier.padding(20.dp)) {
                LocalTransferContent(state, {}, { id -> state = state.copy(selected = if (id in state.selected) state.selected - id else state.selected + id) },
                    { calls.add("preview") }, {}, {}, {}, {}, {}, {}, {})
            } }
        }
        compose.onNodeWithText("继续预览").assertIsNotEnabled()
        compose.onNodeWithTag("transferChoice:image").assertIsOff().performClick()
        compose.onNodeWithTag("transferChoice:image").assertIsOn()
        compose.onNodeWithText("继续预览").assertIsEnabled().performClick()
        assertEquals(listOf("preview"), calls)
        compose.onNodeWithTag("transferChoice:image").performClick()
        compose.onNodeWithText("继续预览").assertIsNotEnabled()
    }

    @Test fun restorePreviewNeedsExplicitConfirmationAndExplainsMerge() {
        show(LocalTransferState(kind = TransferKind.Restore, step = TransferStep.RestorePreview, label = "样本备份.zip", assetCount = 2,
            artistCount = 1, collectionCount = 1, referenceCount = 2, totalBytes = 1024))
        compose.onNodeWithText("合并不会覆盖现有图库", substring = true).assertExists()
        compose.onNodeWithText("保留这台设备当前的全局浏览设置", substring = true).assertExists()
        assertTrue(calls.isEmpty())
        compose.onAllNodesWithText("确认合并恢复").filter(hasClickAction()).onFirst().performClick()
        assertEquals(listOf("restore"), calls)
    }

    @Test fun destinationShowsNoSecondSaveAction() {
        show(LocalTransferState(step = TransferStep.Destination, fileName = "样本.zip"))
        compose.onNodeWithText("选择保存位置").assertExists()
        compose.onAllNodesWithText("选择保存位置").filter(hasClickAction()).assertCountEquals(0)
        compose.onNodeWithText("关闭窗口").performClick()
        assertEquals(listOf("hide"), calls)
    }

    @Test fun finalRestoreCommitDisablesCancellationButAllowsBackground() {
        show(LocalTransferState(kind = TransferKind.Restore, step = TransferStep.Working, canCancel = false))
        compose.onNodeWithText("取消任务").assertIsNotEnabled()
        compose.onNodeWithText("正在提交恢复", substring = true).assertExists()
        compose.onNodeWithText("后台继续").performClick()
        assertEquals(listOf("hide"), calls)
    }

    @Test fun failedResultDoesNotClaimThatBackupWasSaved() {
        show(LocalTransferState(kind = TransferKind.Backup, step = TransferStep.Result, success = false, assetCount = 2, message = "存储空间不足。"))
        compose.onNodeWithText("完整备份已保存。").assertDoesNotExist()
        compose.onNodeWithText("本次备份未完成。").assertExists()
        compose.onNodeWithText("存储空间不足。").assertExists()
        compose.onNodeWithText("原文件 2 份", substring = true).assertDoesNotExist()
    }

    @Test fun committedRestoreWithWarningShowsSavedCountsWithoutClaimingFullCompletion() {
        val warning = "画师展示方式保存失败。"
        val message = "原有图库已保留。请重新启动应用完成收尾，无需再次导入同一备份。"
        show(LocalTransferState(kind = TransferKind.Restore, step = TransferStep.Result, success = true, assetCount = 2,
            artistCount = 1, collectionCount = 1, referenceCount = 2, totalBytes = 1024, message = message, warning = warning))
        compose.onNodeWithText("数据已恢复，收尾未完成。").assertExists()
        compose.onNodeWithText(warning).assertExists()
        compose.onNodeWithText(message).assertExists()
        compose.onNodeWithText("原文件 2 份", substring = true).assertExists()
        compose.onNodeWithText("1 位画师 · 1 个作品集 · 2 个引用").assertExists()
        compose.onNodeWithText("合并恢复完成，原有图库已保留。").assertDoesNotExist()
        compose.onNodeWithText("本次恢复未完成。").assertDoesNotExist()
    }

    @Test fun manageEntriesOpenRealCallbacks() {
        compose.activity.setContent { DTheme {
            ManageScreen(GalleryUiState(route = GalleryRoute.Manage, browseOptions = BrowseOptions(storageNoticeDismissed = true)), 18.dp, {},
                onExport = { calls.add("export") }, onBackup = { calls.add("backup") }, onRestore = { calls.add("restore") })
        } }
        compose.onNodeWithText("按范围导出").performScrollTo().performClick()
        compose.onNodeWithText("完整备份").performScrollTo().performClick()
        compose.onNodeWithText("从备份恢复").performScrollTo().performClick()
        assertEquals(listOf("export", "backup", "restore"), calls)
    }

    @Test fun collectionMenuDismissesBeforeExportingItsScope() {
        compose.activity.setContent { DTheme {
            GalleryDialogs(GalleryUiState(route = GalleryRoute.Collection("collection", "artist"), dialog = GalleryDialog.CollectionMenu,
                collection = CollectionSummary("collection", "artist", "作品集", false, 1, 0)), null,
                onEvent = { if (it == GalleryEvent.DismissDialog) calls.add("dismiss") },
                onExport = { calls.add("export:${(it as ExportScope.Collections).ids.single()}") })
        } }
        compose.onNodeWithText("导出这个作品集").performClick()
        assertEquals(listOf("dismiss", "export:collection"), calls)
    }

    @Test fun viewerExportsDisplayedPagerItemWhileParentSelectionIsStillOld() {
        val images = listOf(
            MediaAssetSummary("first", "第一张.png", MediaType.IMAGE, 1024, 0, "1.png", "first"),
            MediaAssetSummary("second", "第二张.png", MediaType.IMAGE, 1024, 1, "2.png", "second"),
        )
        compose.activity.setContent { DTheme {
            ImageViewerScreen(GalleryUiState( browseOptions = BrowseOptions(cardEffects = false)).withPreparedMedia(images), "first", null,
                onEvent = {}, onExport = { calls.add(it) })
        } }
        compose.onNodeWithTag("imagePager").performTouchInput { swipeLeft() }
        compose.onNodeWithContentDescription("图片操作").performClick()
        compose.onNodeWithText("导出这张图片").performClick()
        assertEquals(listOf("second"), calls)
    }

    private fun show(state: LocalTransferState) {
        compose.activity.setContent { DTheme { Column(Modifier.padding(20.dp)) {
            LocalTransferContent(state, { calls.add("choose:$it") }, { calls.add("toggle:$it") }, { calls.add("preview") },
                { calls.add("save") }, { calls.add("pick") }, { calls.add("restore") }, { calls.add("cancel") },
                { calls.add("hide") }, { calls.add("done") }, { calls.add("scope") })
        } } }
    }
}
