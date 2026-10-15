package dev.local.artistgallery.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.core.content.ContextCompat
import dev.local.artistgallery.data.GalleryRepository
import dev.local.artistgallery.data.MediaLibraryRepository
import dev.local.artistgallery.data.importing.MediaImportCoordinator
import dev.local.artistgallery.data.importing.MediaImportSession
import dev.local.artistgallery.data.importing.ImportNotifications
import dev.local.artistgallery.data.importing.ImportResultKind
import dev.local.artistgallery.data.importing.ImportResultTarget
import dev.local.artistgallery.data.preview.AssetReader
import dev.local.artistgallery.data.preview.PreviewDecoder
import dev.local.artistgallery.data.preview.buildImageShareChooser
import dev.local.artistgallery.model.SampleCoverKey
import dev.local.artistgallery.ui.comic.ComicReaderScreen
import dev.local.artistgallery.ui.viewer.ImageViewerScreen
import dev.local.artistgallery.ui.viewer.VideoPlayerScreen
import dev.local.artistgallery.data.archive.ArchiveImportCoordinator
import dev.local.artistgallery.data.archive.ArchiveImportState
import dev.local.artistgallery.data.archive.ArchiveStep
import dev.local.artistgallery.ui.archive.ArchiveImportDialog
import dev.local.artistgallery.data.exporting.ExportScope
import dev.local.artistgallery.data.exporting.LocalTransferCoordinator
import dev.local.artistgallery.data.exporting.TransferStep
import dev.local.artistgallery.ui.exporting.LocalTransferDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GalleryApp(
    repository: GalleryRepository,
    fixtureCover: (@Composable (SampleCoverKey) -> Unit)? = null,
    mediaRepository: MediaLibraryRepository? = null,
    mediaImporter: MediaImportCoordinator? = null,
    previewDecoder: PreviewDecoder? = null,
    assetReader: AssetReader? = null,
    archiveImporter: ArchiveImportCoordinator? = null,
    browsePreferences: dev.local.artistgallery.data.BrowsePreferences? = null,
    localTransfer: LocalTransferCoordinator? = null,
    importNotifications: ImportNotifications? = null,
    importResultTarget: ImportResultTarget? = null,
    onImportResultConsumed: () -> Unit = {},
    mediaImportSession: MediaImportSession? = null,
) {
    val factory = remember(repository, mediaRepository, mediaImporter, browsePreferences, importNotifications, mediaImportSession) {
        viewModelFactory { initializer { GalleryViewModel(repository, createSavedStateHandle(), mediaRepository, mediaImporter, browsePreferences,
            onImportCompleted = { completion -> importNotifications?.completed(completion) }, mediaImportSession = mediaImportSession) } }
    }
    val vm: GalleryViewModel = viewModel(factory = factory)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var supplement by rememberSaveable { mutableStateOf(false) }
    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> archiveImporter?.pick(uris, supplement) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) archiveImporter?.pickFolder(uri) }
    var pendingNotificationAction by rememberSaveable { mutableStateOf<List<String>?>(null) }
    fun performImportAction(action: NotificationImportAction, key: String) {
        when (action) {
            NotificationImportAction.MediaConfirm -> vm.continueImportAfterPermission(GalleryEvent.ConfirmImport, key)
            NotificationImportAction.MediaRetry -> vm.continueImportAfterPermission(GalleryEvent.RetryImport(key), key)
            else -> archiveImporter?.let { coordinator ->
                if (isArchiveNotificationActionCurrent(action, key, coordinator.state.value)) when (action) {
                    NotificationImportAction.ArchiveConfirm -> coordinator.confirm()
                    NotificationImportAction.ArchiveRetry -> coordinator.retry()
                    NotificationImportAction.ArchiveResume -> coordinator.resume(key)
                    NotificationImportAction.ArchivePick -> { supplement = false; archivePicker.launch(arrayOf("*/*")) }
                    NotificationImportAction.ArchiveSupplementPick -> { supplement = true; archivePicker.launch(arrayOf("*/*")) }
                    NotificationImportAction.FolderPick -> folderPicker.launch(null)
                    else -> Unit
                }
            }
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingNotificationAction
        pendingNotificationAction = null
        if (pending != null) {
            if (!granted) Toast.makeText(context, "未开启通知权限，导入仍会继续。", Toast.LENGTH_SHORT).show()
            val action = NotificationImportAction.entries.firstOrNull { it.name == pending.firstOrNull() }
            val key = pending.getOrNull(1)
            if (action != null && key != null) performImportAction(action, key)
        }
    }
    fun requestImportAction(action: NotificationImportAction, key: String) {
        if (pendingNotificationAction != null) return
        if (Build.VERSION.SDK_INT >= 33 && importNotifications?.needsPermissionRequest() == true) {
            pendingNotificationAction = listOf(action.name, key)
            importNotifications.markPermissionRequested()
            try { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            catch (_: RuntimeException) {
                pendingNotificationAction = null
                Toast.makeText(context, "通知权限暂不可用，导入仍会继续。", Toast.LENGTH_SHORT).show()
                performImportAction(action, key)
            }
        } else performImportAction(action, key)
    }
    val importEventHandler: (GalleryEvent) -> Unit = { event ->
        if (event == GalleryEvent.ConfirmImport || event is GalleryEvent.RetryImport) {
            vm.importPermissionActionKey(event)?.let { key ->
                requestImportAction(if (event == GalleryEvent.ConfirmImport) NotificationImportAction.MediaConfirm else NotificationImportAction.MediaRetry, key)
            }
        } else vm.onEvent(event)
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> localTransfer?.destinationPicked(uri) }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> localTransfer?.restorePicked(uri) }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        vm.onMediaPicked(uris)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.onMediaPicked(uris)
    }
    var albumPermissionPending by rememberSaveable { mutableStateOf(false) }
    val albumPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (albumPermissionPending) {
            albumPermissionPending = false
            if (grants.values.none { it }) {
                Toast.makeText(context, "未获得相册权限，仍可选择照片或视频导入。", Toast.LENGTH_LONG).show()
            }
            mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(importResultTarget, state.isSaving, vm, archiveImporter) {
        val target = importResultTarget ?: return@LaunchedEffect
        if (vm.state.value.isSaving) return@LaunchedEffect
        pendingNotificationAction = null
        when (target.kind) {
            ImportResultKind.Media -> { archiveImporter?.hide(); vm.onEvent(GalleryEvent.OpenImportResult(target.batchId)) }
            ImportResultKind.Archive -> {
                vm.onEvent(GalleryEvent.DismissDialog)
                vm.onEvent(GalleryEvent.CloseOverlay)
                archiveImporter?.openResult(target.batchId)
            }
        }
        onImportResultConsumed()
    }
    var toast by remember { mutableStateOf<String?>(null) }
    var scrollToArtist by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(vm) {
        vm.feedback.collectLatest { feedback ->
            if (feedback is GalleryFeedback.Saved) {
                toast = feedback.message
                scrollToArtist = feedback.scrollToArtistId
                delay(4500)
                toast = null
            }
        }
    }
    BackHandler(state.route != GalleryRoute.Home || state.dialog != GalleryDialog.None || state.isSaving || state.mediaSelectionMode || state.entitySelectionKind != null) { vm.onEvent(GalleryEvent.NavigateBack) }
    DTheme {
        Box(Modifier.fillMaxSize().background(DColors.Background).safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            BoxWithConstraints(Modifier.widthIn(max = 430.dp).fillMaxSize().background(DColors.Background)) {
                val padding = if (maxWidth <= 370.dp) 18.dp else 20.dp
                val holder = rememberSaveableStateHolder()
                val routeKey = when (val route = state.route) {
                    GalleryRoute.Home -> "home"
                    GalleryRoute.Manage -> "manage"
                is GalleryRoute.Artist -> "artist:${route.id}:${state.artistTab}"
                is GalleryRoute.Collection -> "collection:${route.id}"
                GalleryRoute.Unassigned -> "unassigned"
                }
                // Keep the browsing composition and its decoded thumbnails while a viewer is open.
                Box(Modifier.fillMaxSize().layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        if (state.overlay == GalleryOverlay.None) placeable.placeRelative(0, 0)
                    }
                }) {
                    holder.SaveableStateProvider(routeKey) {
                        when (state.route) {
                            GalleryRoute.Home -> ArtistHomeScreen(state, padding, scrollToArtist, { scrollToArtist = null }, fixtureCover, previewDecoder, vm::onEvent)
                            GalleryRoute.Manage -> ManageScreen(state, padding, vm::onEvent, archiveImporter?.let { it::history },
                                onExport = localTransfer?.let { it::chooseScope }, onBackup = localTransfer?.let { it::openBackup }, onRestore = localTransfer?.let { it::openRestore })
                            GalleryRoute.Unassigned -> UnassignedMediaScreen(state, padding, previewDecoder, vm::onEvent)
                            is GalleryRoute.Artist -> ArtistDetailScreen(state, padding, fixtureCover, previewDecoder, vm::onEvent)
                            is GalleryRoute.Collection -> CollectionScreen(state, padding, previewDecoder, vm::onEvent)
                        }
                    }
                }
                when (val overlay = state.overlay) {
                    GalleryOverlay.None -> Unit
                    is GalleryOverlay.CardViewer -> {
                        val shareScope = rememberCoroutineScope()
                        var preparingShare by remember { mutableStateOf(false) }
                        ImageViewerScreen(state, overlay.assetId, previewDecoder, vm::onEvent,
                            onExport = localTransfer?.let { coordinator -> { id -> coordinator.openExport(ExportScope.Assets(listOf(id))) } },
                            onShare = if (assetReader == null || preparingShare) null else { item ->
                                if (!preparingShare) {
                                    preparingShare = true
                                    val requestedViewer = GalleryOverlay.CardViewer(item.id)
                                    vm.onEvent(GalleryEvent.OpenMedia(item.id))
                                    shareScope.launch {
                                        try {
                                            val chooser = withContext(Dispatchers.IO) { buildImageShareChooser(context, assetReader, item) }
                                            if (vm.state.value.overlay == requestedViewer) context.startActivity(chooser)
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) {
                                            if (vm.state.value.overlay == requestedViewer) Toast.makeText(context, "无法分享这张图片，请确认原图仍存在且格式为 JPEG、PNG 或 GIF。", Toast.LENGTH_LONG).show()
                                        } finally { preparingShare = false }
                                    }
                                }
                            })
                    }
                    is GalleryOverlay.VideoPlayer -> VideoPlayerScreen(state, overlay.assetId, assetReader, vm::onEvent)
                    GalleryOverlay.ComicReader -> ComicReaderScreen(state, previewDecoder, vm::onEvent)
                }
                if (state.overlay == GalleryOverlay.None) Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 32.dp, vertical = 18.dp)) { DBottomNavigation(state.route == GalleryRoute.Manage || state.route == GalleryRoute.Unassigned, vm::onEvent) }
                toast?.takeIf { state.dialog == GalleryDialog.None }?.let { message ->
                    Text(message, color = Color.White, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = 112.dp)
                        .background(Color(0xFF252A33), RoundedCornerShape(18.dp)).padding(horizontal = 18.dp, vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
        GalleryDialogs(state, toast, importEventHandler, archiveImporter?.let { coordinator -> {
            vm.onEvent(GalleryEvent.DismissDialog)
            coordinator.open(when (val route = state.route) { is GalleryRoute.Artist -> route.id; is GalleryRoute.Collection -> route.artistId; else -> null })
        } }, previewDecoder, onFolder = archiveImporter?.let { coordinator -> {
            vm.onEvent(GalleryEvent.DismissDialog)
            coordinator.openFolder(when (val route = state.route) { is GalleryRoute.Artist -> route.id; is GalleryRoute.Collection -> route.artistId; else -> null })
        } }, onExport = localTransfer?.let { it::openExport })
        archiveImporter?.let { coordinator ->
            val archiveState by coordinator.state.collectAsStateWithLifecycle()
            val blocked = archiveState.visible && archiveState.format == "ZIP" && archiveState.step == ArchiveStep.Importing
            DisposableEffect(vm, blocked) {
                vm.setGalleryQueriesPaused(blocked)
                onDispose { vm.setGalleryQueriesPaused(false) }
            }
            ArchiveImportDialog(archiveState, state.artists, coordinator,
                pick = { add ->
                    val current = coordinator.state.value
                    val action = if (current.format == "FOLDER") NotificationImportAction.FolderPick
                        else if (add) NotificationImportAction.ArchiveSupplementPick else NotificationImportAction.ArchivePick
                    requestImportAction(action, archiveSourceActionKey(current))
                },
                addArtist = { coordinator.hide(); vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.AddArtist(fromArchiveImport = true))) },
                viewGallery = { id -> vm.onEvent(GalleryEvent.OpenArtist(id)); vm.onEvent(GalleryEvent.SwitchArtistTab(ArtistTab.Collections)) },
                confirm = { coordinator.state.value.taskId?.let { requestImportAction(NotificationImportAction.ArchiveConfirm, it) } },
                retry = { coordinator.state.value.taskId?.let { requestImportAction(NotificationImportAction.ArchiveRetry, it) } },
                resume = { id -> scope.launch {
                    try {
                        val unfinished = coordinator.hasUnfinishedItems(id)
                        if (isArchiveNotificationActionCurrent(NotificationImportAction.ArchiveResume, id, coordinator.state.value)) {
                            if (unfinished) requestImportAction(NotificationImportAction.ArchiveResume, id) else coordinator.openResult(id)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { Toast.makeText(context, error.message ?: "读取导入记录失败。", Toast.LENGTH_SHORT).show() }
                } },
                backToMenu = { vm.onEvent(GalleryEvent.OpenDialog(GalleryDialog.AddMenu)) })
        }
        localTransfer?.let { coordinator ->
            val transferState by coordinator.state.collectAsStateWithLifecycle()
            LocalTransferDialog(transferState, coordinator,
                save = {
                    if (coordinator.state.value.step == TransferStep.Preview) {
                        coordinator.waitForDestination()
                        if (coordinator.state.value.step == TransferStep.Destination) exportPicker.launch(coordinator.state.value.fileName)
                    }
                },
                pickRestore = {
                    if (coordinator.state.value.step == TransferStep.RestorePick) {
                        coordinator.waitForRestoreSource()
                        if (coordinator.state.value.step == TransferStep.Destination) restorePicker.launch(arrayOf("*/*"))
                    }
                })
        }
    }
    LaunchedEffect(vm, mediaPicker, filePicker, albumPermissionLauncher, archiveImporter) {
        vm.effects.collect { effect ->
            when (effect) {
                GalleryEffect.ResumeArchiveArtistSelection -> archiveImporter?.let { it.target(); it.showCurrent() }
                is GalleryEffect.LaunchMediaPicker -> if (effect.fromFiles) filePicker.launch(arrayOf("image/*", "video/*"))
                    else {
                        val permissions = albumReadPermissions(Build.VERSION.SDK_INT)
                        if (permissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
                            mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        } else {
                            albumPermissionPending = true
                            albumPermissionLauncher.launch(permissions)
                        }
                    }
            }
        }
    }
}

internal enum class NotificationImportAction { MediaConfirm, MediaRetry, ArchiveConfirm, ArchiveRetry, ArchiveResume, ArchivePick, ArchiveSupplementPick, FolderPick }

internal fun archiveSourceActionKey(state: ArchiveImportState): String =
    "${state.artistId.orEmpty()}|${state.format}|${state.step.name}|${state.taskId.orEmpty()}"

internal fun isArchiveNotificationActionCurrent(action: NotificationImportAction, key: String, state: ArchiveImportState): Boolean =
    state.visible && when (action) {
        NotificationImportAction.ArchiveConfirm -> state.step == ArchiveStep.Preview && state.taskId == key && state.groups.any { it.canImport }
        NotificationImportAction.ArchiveRetry -> state.step in setOf(ArchiveStep.Result, ArchiveStep.Error) && state.taskId == key
        NotificationImportAction.ArchiveResume -> state.step == ArchiveStep.History && state.history.any { it.id == key }
        NotificationImportAction.ArchivePick, NotificationImportAction.FolderPick, NotificationImportAction.ArchiveSupplementPick ->
            state.artistId != null && archiveSourceActionKey(state) == key &&
                if (action == NotificationImportAction.ArchiveSupplementPick) state.step == ArchiveStep.Error && state.files.isNotEmpty() && state.format != "FOLDER"
                else state.step in setOf(ArchiveStep.Source, ArchiveStep.Target, ArchiveStep.Error) && (state.format == "FOLDER") == (action == NotificationImportAction.FolderPick)
        else -> false
    }
