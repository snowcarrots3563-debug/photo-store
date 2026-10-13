package dev.local.artistgallery.ui

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.filters.SdkSuppress
import dev.local.artistgallery.data.GalleryDatabase
import dev.local.artistgallery.data.RoomGalleryRepository
import dev.local.artistgallery.data.archive.*
import dev.local.artistgallery.data.importing.ImportNotifications
import dev.local.artistgallery.data.importing.MediaFileStore
import dev.local.artistgallery.data.importing.MediaImportCoordinator
import dev.local.artistgallery.fixture.UiFixtureActivity
import dev.local.artistgallery.model.MediaType
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Isolated Room and synthetic selections; these sources are compiled, not run on the private gallery. */
class ImportNotificationUiTest {
    @get:Rule val compose = createAndroidComposeRule<UiFixtureActivity>()
    private lateinit var database: GalleryDatabase
    private lateinit var repository: RoomGalleryRepository
    private lateinit var coordinator: ArchiveImportCoordinator
    private lateinit var importer: MediaImportCoordinator
    private lateinit var fixtureContext: Context
    private lateinit var root: File
    private lateinit var scope: CoroutineScope
    private val calls = mutableListOf<String>()

    @Before fun setup() {
        root = File(compose.activity.cacheDir, "notification-ui-${UUID.randomUUID()}").apply { check(mkdir()) }
        val preferencePrefix = root.name
        fixtureContext = object : ContextWrapper(compose.activity) {
            override fun getExternalFilesDir(type: String?) = root
            override fun checkSelfPermission(permission: String) = if (permission == Manifest.permission.POST_NOTIFICATIONS) PackageManager.PERMISSION_DENIED else super.checkSelfPermission(permission)
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$preferencePrefix-$name", mode)
        }
        database = Room.inMemoryDatabaseBuilder(fixtureContext, GalleryDatabase::class.java).build()
        repository = RoomGalleryRepository(database)
        val files = MediaFileStore(fixtureContext)
        importer = MediaImportCoordinator(fixtureContext, repository, files)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        coordinator = ArchiveImportCoordinator(fixtureContext, database, repository, importer, files, scope)
    }

    @After fun cleanup() {
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        scope.cancel()
        database.close()
        assertEquals(compose.activity.cacheDir.canonicalFile, root.canonicalFile.parentFile)
        assertTrue(root.name.startsWith("notification-ui-"))
        root.deleteRecursively()
    }

    @Test fun archivePreviewUsesPermissionAwareConfirmCallbackOnly() {
        showArchive(ArchiveImportState(visible = true, step = ArchiveStep.Preview, taskId = "task",
            groups = listOf(ArchivePreviewGroup("", "synthetic", listOf(ArchivePreviewItem(ArchiveEntry(0, "a.png", 6000, false, false), MediaType.IMAGE, null))))))
        compose.onNodeWithText("开始导入").performClick()
        assertEquals(listOf("confirm"), calls)
        assertEquals(ArchiveStep.Source, coordinator.state.value.step)
    }

    @Test fun archiveErrorUsesPermissionAwareRetryCallbackOnly() {
        showArchive(ArchiveImportState(visible = true, step = ArchiveStep.Error, taskId = "task", format = "FOLDER"))
        compose.onNodeWithText("重试读取").performClick()
        assertEquals(listOf("retry"), calls)
        assertEquals(ArchiveStep.Source, coordinator.state.value.step)
    }

    @Test fun archiveResultAndHistoryUsePermissionAwareCallbacksWithoutResumingImmediately() {
        showArchive(ArchiveImportState(visible = true, step = ArchiveStep.History,
            history = listOf(ArchiveTaskEntity("old", "artist", "ZIP", "synthetic.zip", "[]", 0))))
        compose.onNodeWithText("synthetic.zip").performClick()
        assertEquals(listOf("resume:old"), calls)
        assertEquals(ArchiveStep.Source, coordinator.state.value.step)
    }

    @Test @SdkSuppress(minSdkVersion = 33) fun deniedNotificationPermissionStillRunsImportAndIsNotRequestedAgain() {
        val registry = PermissionRegistry(autoReply = true)
        showGallery(registry)
        selectUnsupportedMedia()
        compose.onNodeWithText("开始导入").performClick()
        compose.waitUntil(5_000) { viewModel().state.value.dialog is GalleryDialog.ImportResult }
        assertEquals(1, registry.requests)
        assertEquals("FAILED", viewModel().state.value.importResult?.status)
        assertEquals(1, viewModel().state.value.importResult?.items?.size)
        compose.runOnIdle { viewModel().onEvent(GalleryEvent.DismissDialog) }
        selectUnsupportedMedia()
        compose.onNodeWithText("开始导入").performClick()
        compose.waitUntil(5_000) { viewModel().state.value.dialog is GalleryDialog.ImportResult }
        assertEquals(1, registry.requests)
        assertEquals(2, runBlocking { repository.latestImportBatches() }.size)
    }

    @Test @SdkSuppress(minSdkVersion = 33) fun pendingPermissionSurvivesCompositionRestoreAndStartsOnlyOnceAfterDenial() {
        val registry = PermissionRegistry(autoReply = false)
        val restoration = StateRestorationTester(compose)
        showGallery(registry, restoration)
        selectUnsupportedMedia()
        compose.onNodeWithText("开始导入").performClick()
        assertEquals(1, registry.requests)
        assertEquals(GalleryDialog.ImportPreview, viewModel().state.value.dialog)
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { registry.reply(false) }
        compose.waitUntil(5_000) { viewModel().state.value.dialog is GalleryDialog.ImportResult }
        assertEquals(1, registry.requests)
        assertEquals(1, runBlocking { repository.latestImportBatches() }.size)
        compose.runOnIdle { registry.reply(false) }
        compose.waitForIdle()
        assertEquals(1, runBlocking { repository.latestImportBatches() }.size)
    }

    @Test @SdkSuppress(minSdkVersion = 33) fun stalePermissionReplyCannotStartAnImportAfterPreviewDismissal() {
        val registry = PermissionRegistry(autoReply = false)
        showGallery(registry)
        selectUnsupportedMedia()
        compose.onNodeWithText("开始导入").performClick()
        compose.runOnIdle { viewModel().onEvent(GalleryEvent.DismissDialog); registry.reply(true) }
        compose.waitForIdle()
        assertEquals(GalleryDialog.None, viewModel().state.value.dialog)
        assertTrue(runBlocking { repository.latestImportBatches() }.isEmpty())
    }

    private fun viewModel() = ViewModelProvider(compose.activity)[GalleryViewModel::class.java]
    private fun selectUnsupportedMedia() {
        compose.runOnIdle { viewModel().onMediaPicked(listOf(android.net.Uri.parse("content://synthetic-notification/a.txt"))) }
        compose.waitUntil(5_000) { viewModel().state.value.dialog == GalleryDialog.ImportPreview }
    }

    private fun showGallery(registry: PermissionRegistry, restoration: StateRestorationTester? = null) {
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        val notifications = ImportNotifications(fixtureContext)
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                GalleryApp(repository, mediaRepository = repository, mediaImporter = importer, importNotifications = notifications)
            }
        }
        if (restoration == null) compose.activity.setContent(content = content) else restoration.setContent(content)
        compose.waitForIdle()
    }

    private fun showArchive(state: ArchiveImportState) {
        compose.activity.setContent { DTheme {
            dev.local.artistgallery.ui.archive.ArchiveImportDialog(state, emptyList(), coordinator, {}, {}, {},
                confirm = { calls.add("confirm") }, retry = { calls.add("retry") }, resume = { calls.add("resume:$it") }, backToMenu = {})
        } }
    }

    private class PermissionRegistry(private val autoReply: Boolean) : ActivityResultRegistry() {
        var requests = 0
        private var pendingCode: Int? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            check(contract is ActivityResultContracts.RequestPermission)
            check(input == Manifest.permission.POST_NOTIFICATIONS)
            requests++
            pendingCode = requestCode
            if (autoReply) reply(false)
        }
        fun reply(granted: Boolean) { pendingCode?.let { dispatchResult(it, granted) } }
    }
}
