package dev.local.artistgallery.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.Room
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.data.GalleryDatabase
import dev.local.artistgallery.data.RoomGalleryRepository
import dev.local.artistgallery.data.archive.*
import dev.local.artistgallery.data.importing.MediaFileStore
import dev.local.artistgallery.data.importing.MediaImportCoordinator
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.model.ArtistSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Synthetic UI state and an in-memory database only; no import jobs or source media are opened. */
class FolderImportUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private lateinit var database: GalleryDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var coordinator: ArchiveImportCoordinator
    private val artists = listOf(ArtistSummary("artist", "合成画师", 0, 0), ArtistSummary("new", "新建画师", 0, 0))
    private val calls = mutableListOf<String>()

    @Before fun setUp() {
        val context = compose.activity
        database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val repository = RoomGalleryRepository(database)
        val files = MediaFileStore(context)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        coordinator = ArchiveImportCoordinator(context, database, repository, MediaImportCoordinator(context, repository, files), files, scope)
    }

    @After fun tearDown() { scope.cancel(); database.close() }

    @Test fun unifiedSourceMenuCallsFolderAndKeepsArchiveAction() {
        compose.activity.setContent { DTheme {
            GalleryDialogs(GalleryUiState(dialog = GalleryDialog.AddMenu), null, {},
                onArchive = { calls.add("archive") }, onFolder = { calls.add("folder") })
        } }
        compose.onNodeWithText("从文件夹导入").performScrollTo().performClick()
        compose.onNodeWithText("从压缩包导入").performScrollTo().performClick()
        assertEquals(listOf("folder", "archive"), calls)
    }

    @Test fun cancellingFolderPickerKeepsSourceAndSelectedArtist() {
        coordinator.openFolder("artist")
        show()
        compose.onNodeWithText("从文件夹导入").assertExists()
        compose.onNodeWithText("递归读取子目录", substring = true).assertExists()
        compose.onNodeWithText("选择文件").assertDoesNotExist()
        compose.onNodeWithText("选择文件夹").assertIsEnabled().performClick()
        assertEquals(listOf("pick"), calls)
        assertEquals("FOLDER", coordinator.state.value.format)
        assertEquals("artist", coordinator.state.value.artistId)
        assertEquals(ArchiveStep.Source, coordinator.state.value.step)
        assertNoArchiveActions()
    }

    @Test fun folderUsesSharedArtistPickerBeforeSelectingSource() {
        coordinator.openFolder(null)
        show()
        compose.onNodeWithText("选择文件夹").assertIsNotEnabled()
        compose.onNodeWithText("选择画师").performClick()
        compose.onNode(hasText("合成画师") and hasClickAction()).performClick()
        assertEquals("artist", coordinator.state.value.artistId)
        assertEquals("FOLDER", coordinator.state.value.format)
        assertEquals(ArchiveStep.Source, coordinator.state.value.step)
        compose.onNodeWithText("选择文件夹").assertIsEnabled()
    }

    @Test fun addArtistReturnUsesSameFolderSelectionFlow() {
        coordinator.openFolder(null)
        show()
        compose.onNodeWithText("添加画师").performClick()
        assertEquals(listOf("artist"), calls)
        assertFalse(coordinator.state.value.visible)
        compose.runOnIdle { coordinator.target(); coordinator.showCurrent() }
        compose.onNode(hasText("新建画师") and hasClickAction()).performClick()
        assertEquals("new", coordinator.state.value.artistId)
        assertEquals("FOLDER", coordinator.state.value.format)
        compose.onNodeWithText("从文件夹导入").assertExists()
        compose.onNodeWithText("选择文件夹").assertIsEnabled()
    }

    @Test fun folderErrorAllowsRetryAndReselectWithoutArchiveActions() {
        show(ArchiveImportState(visible = true, step = ArchiveStep.Error, format = "FOLDER", artistId = "artist",
            taskId = "synthetic-task", files = listOf("合成文件夹"), reason = ArchiveError.MISSING_VOLUME))
        compose.onNodeWithText("文件夹无法导入").assertExists()
        compose.onNodeWithText("补齐缺少的分卷").assertDoesNotExist()
        compose.onNodeWithText("重试读取").assertExists()
        assertNoArchiveActions()
        compose.onNodeWithText("重新选择文件夹").performClick()
        assertEquals(listOf("pick"), calls)
    }

    @Test fun folderPreparationAndImportHaveSourceSpecificTitles() {
        show(ArchiveImportState(visible = true, step = ArchiveStep.Preparing, format = "FOLDER", progress = "正在扫描目录"))
        compose.onNodeWithText("正在准备文件夹").assertExists()
        compose.onNodeWithText("正在准备压缩包").assertDoesNotExist()
        show(ArchiveImportState(visible = true, step = ArchiveStep.Importing, format = "FOLDER", progress = "正在写入媒体"))
        compose.onNodeWithText("正在导入文件夹").assertExists()
        compose.onNodeWithText("正在解压导入").assertDoesNotExist()
        assertNoArchiveActions()
    }

    @Test fun archiveErrorRetainsExistingVolumeAndPasswordActions() {
        show(ArchiveImportState(visible = true, step = ArchiveStep.Error, format = "ZIP", artistId = "artist",
            files = listOf("synthetic.zip"), reason = ArchiveError.MISSING_VOLUME))
        compose.onNodeWithText("补齐缺少的分卷").assertExists()
        compose.onNodeWithText("补选分卷").assertExists()
        compose.onNodeWithText("重新输入密码").assertExists()
        compose.onNodeWithText("重新选择").assertExists()
        compose.onNodeWithText("重新选择文件夹").assertDoesNotExist()
    }

    @Test fun managementAndSharedHistoryIncludeFolder() {
        compose.activity.setContent { DTheme {
            ManageScreen(GalleryUiState(route = GalleryRoute.Manage, browseOptions = BrowseOptions(storageNoticeDismissed = true)), 18.dp,
                {}, archiveHistory = { calls.add("history") })
        } }
        compose.onNodeWithText("相册、文件、压缩包、文件夹").assertExists()
        compose.onNodeWithText("压缩包／文件夹导入记录").performScrollTo().performClick()
        assertEquals(listOf("history"), calls)
        show(ArchiveImportState(visible = true, step = ArchiveStep.History,
            history = listOf(ArchiveTaskEntity("task", "artist", "FOLDER", "合成文件夹", "[]", 0))))
        compose.onNodeWithText("压缩包／文件夹导入记录").assertExists()
        compose.onNodeWithText("文件夹 · 点击查看或继续").assertExists()
    }

    private fun show(state: ArchiveImportState? = null) {
        compose.activity.setContent {
            val current by coordinator.state.collectAsState()
            DTheme { dev.local.artistgallery.ui.archive.ArchiveImportDialog(state ?: current, artists, coordinator,
                pick = { calls.add(if (it) "volume" else "pick") },
                addArtist = { coordinator.hide(); calls.add("artist") },
                viewGallery = { calls.add("gallery:$it") }, backToMenu = { calls.add("menu") }) }
        }
    }

    private fun assertNoArchiveActions() {
        compose.onNodeWithText("补选分卷").assertDoesNotExist()
        compose.onNodeWithText("重新输入密码").assertDoesNotExist()
        compose.onNodeWithContentDescription("压缩包密码").assertDoesNotExist()
        compose.onNodeWithText("解锁预览").assertDoesNotExist()
    }
}
