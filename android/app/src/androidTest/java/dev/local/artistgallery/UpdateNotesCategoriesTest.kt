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

    @Test fun groupsInCategoryOrderAndPreservesUnprefixedChanges() {
        show(listOf(VersionUpdate(6L, "0.1.5", "2026-10-11", listOf(
            "【新增】多选删除", "未分类的旧内容", "【优化】切换视图", "【修复】新建画师返回",
        ))))
        listOf("• 新建画师返回", "• 切换视图", "• 多选删除", "• 未分类的旧内容").forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed()
        }
        val categoryPositions = listOf("【修复】", "【优化】", "【新增】").map {
            compose.onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
        }
        assertTrue(categoryPositions.zipWithNext().all { (first, second) -> first < second })
        compose.onNodeWithText("• 【修复】新建画师返回", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun automaticNotesKeepTwoVersionsAndFullHistoryIncludesOlderChanges() {
        val updates = listOf(
            VersionUpdate(6L, "0.1.5", "2026-10-11", listOf("【修复】最新修复")),
            VersionUpdate(5L, "0.1.4", "2026-10-11", listOf("【优化】上次优化")),
            VersionUpdate(4L, "0.1.3", "2026-10-11", listOf("【新增】早期新增")),
        )
        show(updates)
        compose.onNodeWithText("• 最新修复", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("• 上次优化", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("• 早期新增", useUnmergedTree = true).assertDoesNotExist()
        show(updates, showAll = true)
        compose.onNodeWithText("更新日志", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("• 早期新增", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    private fun show(updates: List<VersionUpdate>, showAll: Boolean = false) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent { DTheme { UpdateNotesDialog(updates, showAll = showAll, onDismiss = {}) } }
        }
    }
}
