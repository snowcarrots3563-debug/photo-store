package dev.local.artistgallery.ui

import android.graphics.Bitmap
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowInsetsCompat
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.artistgallery.fixture.UiFixtureActivity
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** No persistent gallery writes: the Activity and all name-form events use an in-memory fixture. */
@SdkSuppress(minSdkVersion = 30)
class WindowInsetsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun addArtistButtonsStayAboveRealKeyboardAndReturnAboveGestureHandle() {
        compose.onNodeWithContentDescription("添加").performClick()
        compose.onNodeWithText("添加画师").performClick()
        compose.waitUntil(15_000) { focusedInsets()?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        compose.onNodeWithTag("nameInput").performTextInput("边距检查")
        compose.onNodeWithText("添加").assertIsDisplayed()
        assertAboveSystemUi(compose.onNodeWithText("添加"), true)
        assertAboveSystemUi(compose.onNodeWithTag("nameInput"), true)
        screenshot("add-artist-ime")
        compose.onNode(hasText("取消") and hasClickAction()).performClick()
        compose.waitUntil(10_000) { focusedInsets()?.isVisible(WindowInsetsCompat.Type.ime()) == false }
        compose.onNodeWithContentDescription("添加").performClick()
        compose.onNodeWithText("ZIP 压缩包").assertIsDisplayed()
        assertAboveSystemUi(compose.onNodeWithText("添加画师"), false)
        screenshot("add-menu-gesture")
    }

    @Test fun importLocationButtonsStayAboveGestureHandle() {
        compose.onNodeWithContentDescription("添加").performClick()
        compose.onNodeWithText("从相册导入").performClick()
        compose.onNodeWithText("选择导入位置").assertIsDisplayed()
        assertAboveSystemUi(compose.onNodeWithText("选择媒体"), false)
        screenshot("import-target-gesture")
    }

    @Test fun renameFormHandlesKeyboardWithoutCreatingPersistentData() {
        // Render the production form with local state so no ordinary ViewModel/repository is involved.
        compose.activityRule.scenario.onActivity { activity -> activity.setContentForInsetsTest() }
        compose.onNodeWithTag("nameInput").performClick()
        compose.waitUntil(15_000) { focusedInsets()?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        assertAboveSystemUi(compose.onNodeWithText("保存名称"), true)
        screenshot("rename-ime")
    }

    private fun UiFixtureActivity.setContentForInsetsTest() {
        setContent {
            var state by remember { mutableStateOf(GalleryUiState(dialog = GalleryDialog.RenameCollection("fixture", "作品集"))) }
            DTheme { GalleryDialogs(state, null, { event ->
                if (event is GalleryEvent.DraftChanged) state = state.copy(dialog = GalleryDialog.RenameCollection("fixture", event.draft))
            }) }
        }
    }

    private fun focusedInsets(): WindowInsetsCompat? {
        var result: WindowInsetsCompat? = null
        instrumentation.runOnMainSync {
            WindowInspector.getGlobalWindowViews().firstOrNull { it.hasWindowFocus() }?.rootWindowInsets?.let {
                result = WindowInsetsCompat.toWindowInsetsCompat(it)
            }
        }
        return result
    }

    private fun assertAboveSystemUi(node: SemanticsNodeInteraction, keyboard: Boolean) {
        val bounds = node.fetchSemanticsNode().boundsInWindow
        instrumentation.runOnMainSync {
            val view = requireNotNull(WindowInspector.getGlobalWindowViews().firstOrNull { it.hasWindowFocus() })
            val insets = WindowInsetsCompat.toWindowInsetsCompat(requireNotNull(view.rootWindowInsets))
            val bottom = insets.getInsets(if (keyboard) WindowInsetsCompat.Type.ime() else WindowInsetsCompat.Type.navigationBars()).bottom
            assertTrue("Expected nonzero system inset", bottom > 0)
            assertTrue("Control bottom ${bounds.bottom} must fit above system area ${view.height - bottom}", bounds.bottom <= view.height - bottom)
        }
    }

    private fun screenshot(name: String) {
        val folder = File(compose.activity.getExternalFilesDir(null), "ui-insets-20261010").apply { check(exists() || mkdir()) }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
}
