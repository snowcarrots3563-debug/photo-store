package dev.local.artistgallery

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.espresso.Espresso
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.model.VersionUpdate
import dev.local.artistgallery.ui.DTheme
import dev.local.artistgallery.ui.UpdateNotesDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Direct synthetic dialog content; no database, files, or preferences are read or written. */
class UpdateNotesDialogTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private var dismissals = 0
    private val updates = listOf(
        VersionUpdate(25L, "0.25", "2026-10-12", listOf("新增更新内容弹窗", "查看最近两个版本的变化")),
        VersionUpdate(24L, "0.24", "2026-10-11", listOf("新增图片平铺展示", "返回时保留浏览位置")),
    )

    @Test fun showsBothProvidedVersionsAndChangesThenAcknowledges() {
        show(updates)
        compose.onNodeWithText("更新内容", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("当前版本：0.25（版本代码 25） · 2026-10-12", useUnmergedTree = true).assertIsDisplayed()
        updates.forEach { update ->
            compose.onNodeWithText("${update.versionName} · ${update.date}", useUnmergedTree = true).assertIsDisplayed()
            update.changes.forEach { compose.onNodeWithText("• $it", useUnmergedTree = true).assertIsDisplayed() }
        }
        compose.onNodeWithText("知道了").performClick()
        assertDismissed()
    }

    @Test fun closeIconUsesTheSameDismissCallback() {
        show(updates)
        compose.onNodeWithContentDescription("关闭更新内容").performClick()
        assertDismissed()
    }

    @Test fun tappingOutsideThePanelUsesTheSameDismissCallback() {
        show(updates)
        compose.onNodeWithTag("updateNotesBackdrop", useUnmergedTree = true).performTouchInput { click(Offset(centerX, 4f)) }
        assertDismissed()
    }

    @Test fun systemBackUsesTheSameDismissCallback() {
        show(updates)
        Espresso.pressBack()
        assertDismissed()
    }

    @Test fun longChangesScrollWhileTitleCloseAndAcknowledgementStayVisible() {
        val longUpdates = updates.mapIndexed { index, update -> VersionUpdate(update.versionCode, update.versionName, update.date, List(30) { "版本 $index 的第 $it 项更新内容，用于检查长文本滚动。" }) }
        show(longUpdates, fontScale = 1.7f)
        val titleY = compose.onNodeWithText("更新内容", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y
        val buttonY = compose.onNodeWithText("知道了").fetchSemanticsNode().positionInRoot.y
        compose.onNodeWithText("• ${longUpdates.last().changes.last()}", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("更新内容", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭更新内容").assertIsDisplayed()
        compose.onNodeWithText("知道了").assertIsDisplayed()
        assertEquals(titleY, compose.onNodeWithText("更新内容", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y, 1f)
        assertEquals(buttonY, compose.onNodeWithText("知道了").fetchSemanticsNode().positionInRoot.y, 1f)
    }

    private fun assertDismissed() {
        compose.waitForIdle()
        assertEquals(1, dismissals)
        compose.onNodeWithText("更新内容", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun show(items: List<VersionUpdate>, fontScale: Float = 1f) {
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            var visible by remember { mutableStateOf(true) }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                DTheme {
                    if (visible) UpdateNotesDialog(items) { dismissals++; visible = false }
                }
            }
        } }
    }
}
