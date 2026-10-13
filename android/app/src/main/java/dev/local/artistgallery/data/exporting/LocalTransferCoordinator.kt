package dev.local.artistgallery.data.exporting

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dev.local.artistgallery.data.BrowsePreferences
import dev.local.artistgallery.data.GalleryDatabase
import dev.local.artistgallery.data.backup.BackupManifestCodec
import dev.local.artistgallery.data.backup.BackupRestorer
import dev.local.artistgallery.data.backup.PreparedRestore
import dev.local.artistgallery.data.importing.MediaFileStore
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LocalTransferCoordinator(
    private val context: Context,
    database: GalleryDatabase,
    private val files: MediaFileStore,
    preferences: BrowsePreferences,
    private val scope: CoroutineScope,
) {
    private val snapshotReader = GallerySnapshotReader(database, preferences)
    private val restorer = BackupRestorer(context, database, files, preferences)
    private val current = MutableStateFlow(LocalTransferState())
    val state = current.asStateFlow()
    private var job: Job? = null
    private var plan: ExportPlan? = null
    private var prepared: PreparedRestore? = null
    private val outputJournal = context.getSharedPreferences("local-output", Context.MODE_PRIVATE)
    private val recovery = scope.async(Dispatchers.IO) {
        restorer.recover()
        outputJournal.getString("pendingUri", null)?.let { saved ->
            val uri = Uri.parse(saved)
            if (!outputJournal.getBoolean("completed", false)) {
                val removed = deleteDocument(uri)
                current.update { it.copy(visible = true, step = TransferStep.Result, message = if (removed) "上次输出被中断，已清理未完成文件，请重新导出。" else "上次输出被中断，所选位置可能留有不完整 ZIP，请删除后重新导出。") }
                if (removed) clearOutputJournal(uri)
            } else clearOutputJournal(uri)
        }
    }

    suspend fun recover() {
        try { recovery.await() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            current.update { it.copy(visible = true, step = TransferStep.Result, success = false,
                message = error.message ?: "上次传输记录无法读取，现有图库保留。") }
        }
    }
    fun hide() { current.update { it.copy(visible = false) } }
    fun showCurrent() { current.update { it.copy(visible = true) } }
    private fun busy(): Boolean = job?.isActive == true || current.value.step == TransferStep.Destination

    fun chooseScope() = openSession(TransferKind.Library, TransferStep.Scope)
    fun openRestore() = openSession(TransferKind.Restore, TransferStep.RestorePick)
    fun openBackup() = openSession(TransferKind.Backup, TransferStep.Preparing) { preview(ExportScope.Library, backup = true) }
    fun openExport(scope: ExportScope) {
        val kind = when (scope) { is ExportScope.Assets -> TransferKind.Images; is ExportScope.Collections -> TransferKind.Collections; is ExportScope.Artists -> TransferKind.Artists; ExportScope.Library -> TransferKind.Library }
        openSession(kind, TransferStep.Preparing) { preview(scope) }
    }

    private fun openSession(kind: TransferKind, step: TransferStep, action: (suspend () -> Unit)? = null) {
        if (busy()) { showCurrent(); return }
        current.value = LocalTransferState(visible = true, kind = kind, step = TransferStep.Preparing, progress = "正在读取图库…")
        runOperation {
            discardPrepared(); plan = null
            if (action != null) action() else current.update { it.copy(step = step, progress = "") }
        }
    }

    fun choose(kind: TransferKind) {
        if (busy()) return
        if (kind == TransferKind.Backup) { openBackup(); return }
        if (kind == TransferKind.Restore) { openRestore(); return }
        current.update { it.copy(kind = kind, step = TransferStep.Preparing, selected = emptySet(), message = null) }
        runOperation {
            val snapshot = snapshotReader.read()
            if (kind == TransferKind.Library) { preview(ExportScope.Library); return@runOperation }
            val source = snapshot.manifest
            val artistNames = source.artists.associate { it.id to it.name }
            val counts = source.references.groupingBy { it.collectionId }.eachCount()
            val collections = source.collections.groupingBy { it.artistId }.eachCount()
            val choices = when (kind) {
                TransferKind.Images -> source.assets.map { TransferChoice(it.id, it.originalName, "${when (it.mediaType) { "VIDEO" -> "视频"; "GIF" -> "GIF"; else -> "图片" }} · ${sizeLabel(it.byteSize)}") }
                TransferKind.Collections -> source.collections.map { c -> TransferChoice(c.id, c.name, "${artistNames.getValue(c.artistId)} · ${counts[c.id] ?: 0} 项") }
                TransferKind.Artists -> source.artists.map { a -> TransferChoice(a.id, a.name, "${collections[a.id] ?: 0} 个作品集") }
                else -> emptyList()
            }
            current.update { it.copy(step = TransferStep.Selection, choices = choices) }
        }
    }

    fun toggle(id: String) {
        if (current.value.step != TransferStep.Selection || current.value.choices.none { it.id == id }) return
        current.update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }
    }
    fun previewSelection() {
        val state = current.value
        if (busy() || state.step != TransferStep.Selection || state.selected.isEmpty()) return
        val ids = state.choices.filter { it.id in state.selected }.map { it.id }
        val selection = when (state.kind) { TransferKind.Images -> ExportScope.Assets(ids); TransferKind.Collections -> ExportScope.Collections(ids); TransferKind.Artists -> ExportScope.Artists(ids); else -> return }
        current.update { it.copy(step = TransferStep.Preparing) }
        runOperation { preview(selection) }
    }

    private suspend fun preview(scope: ExportScope, backup: Boolean = false) {
        val source = snapshotReader.read()
        val output = if (backup) ExportPlanBuilder.backup(source) else ExportPlanBuilder.build(source, scope)
        if (!backup && output.fileCount == 0) throw IOException("所选范围没有可导出的媒体。")
        plan = output
        val manifest = source.manifest
        val fileName = (if (backup) "图库备份" else "图库导出") + "-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".zip"
        current.update { it.copy(step = TransferStep.Preview, label = output.label, fileName = fileName,
            assetCount = output.assets.size, fileCount = output.fileCount, totalBytes = output.totalBytes,
            artistCount = manifest.artists.size, collectionCount = manifest.collections.size, referenceCount = manifest.references.size,
            choices = output.entries.map { entry -> TransferChoice(entry.path, entry.path, "") }, message = null) }
    }

    fun waitForDestination() {
        if (busy() || current.value.step != TransferStep.Preview || plan == null) return
        current.update { it.copy(step = TransferStep.Destination) }
    }
    fun waitForRestoreSource() {
        if (busy() || current.value.step != TransferStep.RestorePick) return
        current.update { it.copy(step = TransferStep.Destination) }
    }
    fun destinationPicked(uri: Uri?) {
        if (current.value.step != TransferStep.Destination || current.value.kind == TransferKind.Restore) return
        if (uri == null) { current.update { it.copy(step = TransferStep.Preview) }; return }
        val output = plan ?: return
        current.update { it.copy(step = TransferStep.Working, progress = "正在生成 ZIP…", success = false) }
        runOperation {
            var complete = false
            try {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                if (!outputJournal.edit().putString("pendingUri", uri.toString()).putBoolean("completed", false).commit()) throw IOException("无法记录输出任务，请重试。")
                val manifestBytes = output.manifest?.let(BackupManifestCodec::encode)
                val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("无法写入所选位置。")
                val outcome = stream.use { ZipExportWriter.write(output, it, manifestBytes, files::originalFile) { message -> current.update { it.copy(progress = message) } } }
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    if (!outputJournal.edit().putBoolean("completed", true).commit()) throw IOException("无法记录输出完成状态，请重试。")
                    clearOutputJournal(uri); complete = true; plan = null
                    current.update { it.copy(step = TransferStep.Result, success = true, message = "已保存到所选位置，ZIP 大小 ${sizeLabel(outcome.byteSize)}。") }
                }
            } finally {
                if (!complete) withContext(NonCancellable) {
                    val removed = deleteDocument(uri)
                    if (removed) clearOutputJournal(uri)
                    if (!removed) current.update { it.copy(message = "所选位置可能留有不完整 ZIP，请删除后重试。") }
                }
            }
        }
    }

    fun restorePicked(uri: Uri?) {
        if (current.value.step != TransferStep.Destination || current.value.kind != TransferKind.Restore) return
        if (uri == null) { current.update { it.copy(step = TransferStep.RestorePick) }; return }
        current.update { it.copy(step = TransferStep.Preparing, progress = "正在校验备份…", message = null) }
        runOperation {
            val staged = restorer.prepare(uri) { message -> current.update { it.copy(progress = message) } }
            prepared = staged
            val manifest = staged.manifest
            current.update { it.copy(step = TransferStep.RestorePreview, assetCount = manifest.assets.size, fileCount = manifest.assets.size,
                artistCount = manifest.artists.size, collectionCount = manifest.collections.size, referenceCount = manifest.references.size, totalBytes = manifest.totalBytes,
                progress = "", message = null) }
        }
    }
    fun confirmRestore() {
        if (busy() || current.value.step != TransferStep.RestorePreview) return
        val source = prepared ?: return
        current.update { it.copy(step = TransferStep.Working, progress = "正在提交恢复…", success = false, canCancel = false) }
        runOperation {
            withContext(NonCancellable) {
                val outcome = restorer.confirm(source)
                prepared = null
                current.update { it.copy(step = TransferStep.Result, success = true, artistCount = outcome.artistCount, collectionCount = outcome.collectionCount,
                    assetCount = outcome.assetCount, referenceCount = outcome.referenceCount, canCancel = true, warning = outcome.warning,
                    message = if (outcome.warning == null) "恢复完成，原有图库已保留。" else "原有图库已保留。请重新启动应用完成收尾，无需再次导入同一备份。") }
            }
        }
    }

    fun cancel() {
        if (!current.value.canCancel || current.value.step == TransferStep.Destination) { hide(); return }
        if (job?.isActive == true) { job?.cancel(); return }
        current.update { it.copy(step = TransferStep.Preparing) }
        runOperation {
            discardPrepared(); plan = null
            current.update { it.copy(step = TransferStep.Result, success = false, message = "任务已取消。") }
        }
    }
    fun done() {
        if (busy()) { hide(); return }
        current.value = LocalTransferState()
        runOperation { discardPrepared(); plan = null }
    }
    private fun runOperation(action: suspend () -> Unit) {
        job = scope.launch(Dispatchers.IO) {
            try { recovery.await(); action() }
            catch (error: CancellationException) {
                val cleanupError = withContext(NonCancellable) { runCatching { discardPrepared() }.exceptionOrNull()?.message }
                current.update { if (it.success) it else it.copy(step = TransferStep.Result, success = false, canCancel = true, message = listOfNotNull(it.message ?: "任务已取消。", cleanupError).joinToString("\n")) }
            } catch (error: Exception) {
                val cleanupError = withContext(NonCancellable) { runCatching { discardPrepared() }.exceptionOrNull()?.message }
                current.update { it.copy(step = TransferStep.Result, success = false, canCancel = true, message = listOfNotNull(error.message ?: "处理失败，请重试。", it.message, cleanupError).distinct().joinToString("\n")) }
            }
        }
    }
    private suspend fun discardPrepared() { val source = prepared; prepared = null; source?.let { restorer.discard(it) } }
    private fun deleteDocument(uri: Uri): Boolean = runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
    private fun clearOutputJournal(uri: Uri) {
        outputJournal.edit().clear().commit()
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    }
}

internal fun sizeLabel(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    else -> "${bytes / 1024} KB"
}
