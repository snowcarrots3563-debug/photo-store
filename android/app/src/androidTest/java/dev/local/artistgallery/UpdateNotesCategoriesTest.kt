package dev.local.artistgallery

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.model.VersionUpdate
import dev.local.artistgallery.ui.DTheme
import dev.local.artistgallery.ui.UpdateNotesDialog
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Synthetic update history; no gallery files or preferences are changed. */
class UpdateNotesCategoriesTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()

    @Test fun keepsDocumentOrderAndPrefixesAndPreservesUnprefixedChanges() {
        show(listOf(VersionUpdate(6L, "0.1.5", "2026-10-11", listOf(
            "【新增】多选删除", "未分类的旧内容", "【优化】切换视图", "【修复】新建画师返回",
        ))))
        val changes = listOf("• 【新增】多选删除", "• 未分类的旧内容", "• 【优化】切换视图", "• 【修复】新建画师返回")
        changes.forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed()
        }
        val changePositions = changes.map {
            compose.onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
        }
        assertTrue(changePositions.zipWithNext().all { (first, second) -> first < second })
        listOf("【修复】", "【优化】", "【新增】").forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test fun automaticNotesKeepTwoVersionsAndFullHistoryIncludesOlderChanges() {
        val updates = listOf(
            VersionUpdate(6L, "0.1.5", "2026-10-11", listOf("【修复】最新修复")),
            VersionUpdate(5L, "0.1.4", "2026-10-11", listOf("【优化】上次优化")),
            VersionUpdate(4L, "0.1.3", "2026-10-11", listOf("【新增】早期新增")),
        )
        show(updates)
        compose.onNodeWithText("• 【修复】最新修复", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("• 【优化】上次优化", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("• 【新增】早期新增", useUnmergedTree = true).assertDoesNotExist()
        show(updates, showAll = true)
        compose.onNodeWithText("应用更新日志", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("• 【新增】早期新增", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun fullHistoryShowsCurrentVersionCodeAndDateFromItsData() {
        show(listOf(VersionUpdate(13L, "0.2.2", "2026-10-15", listOf("【新增】视频预览"))), showAll = true)
        compose.onNodeWithText("应用更新日志", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("当前版本：0.2.2（版本代码 13） · 2026-10-15", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("0.2.2 · 2026-10-15", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("• 【新增】视频预览", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun show(updates: List<VersionUpdate>, showAll: Boolean = false) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent { DTheme { UpdateNotesDialog(updates, showAll = showAll, onDismiss = {}) } }
        }
    }
}
