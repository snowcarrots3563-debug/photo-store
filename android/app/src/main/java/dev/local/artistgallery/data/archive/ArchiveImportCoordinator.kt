package dev.local.artistgallery.data.archive

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.room.withTransaction
import dev.local.artistgallery.data.*
import dev.local.artistgallery.data.importing.MediaFileStore
import dev.local.artistgallery.data.importing.MediaImportCoordinator
import dev.local.artistgallery.data.importing.ImportCompletion
import dev.local.artistgallery.data.importing.ImportResultKind
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray

enum class ArchiveStep { Source, Target, Preparing, Password, Preview, Importing, Result, Error, History }

data class ArchiveImportState(
    val visible: Boolean = false,
    val step: ArchiveStep = ArchiveStep.Source,
    val format: String = "",
    val artistId: String? = null,
    val taskId: String? = null,
    val files: List<String> = emptyList(),
    val groups: List<ArchivePreviewGroup> = emptyList(),
    val items: List<ImportItemEntity> = emptyList(),
    val history: List<ArchiveTaskEntity> = emptyList(),
    val message: String? = null,
    val reason: ArchiveError? = null,
    val progress: String = "",
    val collectionNames: List<String> = emptyList(),
)

/** Application-owned job: closing a page never cancels a committed import. */
class ArchiveImportCoordinator(
    private val context: Context,
    private val database: GalleryDatabase,
    private val repository: RoomGalleryRepository,
    private val importer: MediaImportCoordinator,
    private val files: MediaFileStore,
    private val scope: CoroutineScope,
    private val onImportCompleted: (ImportCompletion) -> Unit = {},
) {
    private val dao = database.galleryDao()
    private val mutableState = MutableStateFlow(ArchiveImportState())
    val state = mutableState.asStateFlow()
    private val jobLock = Any()
    @Volatile private var job: Job? = null
    private var pendingResultId: String? = null
    private var sessionId: String? = null
    private var password: CharArray? = null
    private var mainName: String? = null
    private var readerEntries: List<ArchiveEntry> = emptyList()
    private var taskCommitted = false
    private var continueImport = false
    @Volatile private var cancelling = false
    private val recovered = CompletableDeferred<Unit>()
    private val root: File get() = File(context.getExternalFilesDir(null) ?: throw IOException("应用存储暂不可用。"), ".archive-imports")
    private fun directory(id: String): File {
        val name = UUID.fromString(id).toString()
        val folder = File(root, name).canonicalFile
        if (folder.parentFile != root.canonicalFile || folder.name != name) throw ArchiveReadException(ArchiveError.UNSAFE_PATH, "压缩暂存目录不安全。")
        return folder
    }
    private fun update(block: (ArchiveImportState) -> ArchiveImportState) { mutableState.update(block) }
    private val busy get() = job != null || cancelling

    fun open(artistId: String?) {
        if (busy || sessionId != null) { update { it.copy(visible = true) }; return }
        update { ArchiveImportState(visible = true, artistId = artistId) }
    }

    fun openFolder(artistId: String?) {
        if (busy || sessionId != null) { update { it.copy(visible = true) }; return }
        update { ArchiveImportState(visible = true, artistId = artistId, format = "FOLDER") }
    }

    fun hide() { update { it.copy(visible = false) } }
    fun selectArtist(id: String) { update { it.copy(artistId = id, message = null) } }
    fun target() { update { it.copy(step = ArchiveStep.Target) } }
    fun showCurrent() { update { it.copy(visible = true) } }
    val hasSelection: Boolean get() = sessionId != null && mainName != null
    fun previewSelection() { if (!busy) run { prepare() } }
    fun back() {
        if (busy) { hide(); return }
        update { it.copy(step = if (taskCommitted) ArchiveStep.Result else when (it.step) {
            ArchiveStep.Preview -> ArchiveStep.Target
            else -> ArchiveStep.Source
        }, message = null) }
    }

    fun history() {
        if (busy || sessionId != null) { showCurrent(); return }
        run {
            val tasks = dao.archiveTasks()
            update { it.copy(visible = true, step = ArchiveStep.History, history = tasks, message = null) }
        }
    }

    fun pick(uris: List<Uri>, supplement: Boolean) {
        if (uris.isEmpty() || busy || state.value.artistId == null) return
        run {
            update { it.copy(step = ArchiveStep.Preparing, message = null, reason = null) }
            if (!supplement) {
                releaseSession()
                sessionId = UUID.randomUUID().toString()
                val folder = directory(requireNotNull(sessionId))
                if (!folder.mkdirs()) throw IOException("无法创建压缩暂存目录。")
                File(folder, ".preparing").writeText("archive-import-v1")
                update { it.copy(taskId = sessionId, files = emptyList(), items = emptyList(), groups = emptyList()) }
            }
            val folder = directory(requireNotNull(sessionId))
            val selectedNames = uris.map(::describe)
            if (!supplement) update { it.copy(format = ArchiveBundle.detectFormat(selectedNames)) }
            val names = state.value.files.toMutableList()
            uris.zip(selectedNames).forEach { (uri, name) ->
                if (names.any { it.equals(name, true) }) throw ArchiveReadException(ArchiveError.CORRUPT, "已经选择 $name；请只补选缺少的分卷，或重新选择整个压缩包。")
                copyVolume(uri, File(folder, name))
                names.add(name)
                update { it.copy(files = names.toList(), progress = "已准备 ${names.size} 个文件") }
            }
            mainName = ArchiveBundle.mainName(state.value.format, names)
            // Zip4j locates .z01/.z02 by the main file stem; normalize only our private copies.
            if (state.value.format == "ZIP" && !requireNotNull(mainName).endsWith(".001", true)) {
                val originalMain = requireNotNull(mainName)
                val stem = originalMain.substringBeforeLast('.')
                val resolved = names.map { name -> if (name == originalMain) "$stem.zip" else "$stem.z${name.substringAfterLast('.').drop(1).toInt().toString().padStart(2, '0')}" }
                names.zip(resolved).filter { it.first != it.second }.forEach { (old, new) ->
                    val target = File(folder, new)
                    if (target.exists() || !File(folder, old).renameTo(target)) throw ArchiveReadException(ArchiveError.IO, "无法准备 ZIP 分卷文件。")
                }
                names.clear(); names.addAll(resolved)
                mainName = "$stem.zip"
                update { it.copy(files = names.toList()) }
            }
            if (taskCommitted) {
                dao.updateArchiveVolumes(requireNotNull(sessionId), JSONArray(names).toString())
            }
            prepare()
        }
    }

    fun pickFolder(uri: Uri) {
        if (busy || state.value.artistId == null) return
        run {
            releaseSession()
            sessionId = UUID.randomUUID().toString()
            update { it.copy(step = ArchiveStep.Preparing, format = "FOLDER", taskId = sessionId, files = emptyList(), items = emptyList(), groups = emptyList(), message = null, reason = null) }
            val folder = directory(requireNotNull(sessionId))
            if (!folder.mkdirs()) throw IOException("无法创建文件夹暂存目录。")
            File(folder, ".preparing").writeText("archive-import-v1")
            val coroutine = currentCoroutineContext()
            mainName = withContext(Dispatchers.IO) {
                FolderArchiveReader.snapshot(context, uri, folder, files::availableBytes, { coroutine.ensureActive() }) { progress ->
                    update { it.copy(progress = progress) }
                }
            }
            update { it.copy(files = listOf(requireNotNull(mainName))) }
            prepare()
        }
    }

    fun unlock(value: CharArray) {
        if (busy || state.value.format == "FOLDER") { value.fill('\u0000'); return }
        password?.fill('\u0000')
        password = value.copyOf()
        value.fill('\u0000')
        run { prepare() }
    }

    fun requestPassword() {
        if (busy || state.value.format == "FOLDER") return
        password?.fill('\u0000'); password = null
        update { it.copy(step = ArchiveStep.Password, message = null) }
    }

    private suspend fun openReader(): PdfArchiveReader {
        val folder = directory(requireNotNull(sessionId))
        val jobContext = currentCoroutineContext()
        // Committed batches keep their original skipped PDFs; only new selections add page items.
        val skippedPdfIndices = if (taskCommitted) repository.importItems(requireNotNull(sessionId))
            .filter { it.status == "SKIPPED" && it.sourceRelativePath?.endsWith(".pdf", true) == true }
            .mapNotNull { it.sourceUri.substringAfterLast('/').toIntOrNull() }.toSet() else emptySet()
        val delegate = if (state.value.format == "FOLDER") FolderArchiveReader(folder) else {
            val selected = state.value.files.map { File(folder, ArchiveBundle.safeName(it)) }
            if (selected.any { !it.isFile }) throw ArchiveReadException(ArchiveError.MISSING_VOLUME, "本地分卷不可用，请重新选择压缩包。")
            val main = File(folder, requireNotNull(mainName))
            if (state.value.format == "ZIP" && !main.name.endsWith(".001", true)) ZipArchiveReader(main, selected, files.availableBytes())
            else NativeArchiveReader(main, selected, files.availableBytes(), { jobContext.ensureActive() }, state.value.format)
        }
        return PdfArchiveReader(delegate, folder, files::availableBytes, { jobContext.ensureActive() }, skippedPdfIndices) { progress ->
            update { it.copy(progress = progress) }
        }.also { reader -> password?.let(reader::setPassword) }
    }

    private suspend fun prepare() {
        update { it.copy(step = ArchiveStep.Preparing, message = null, reason = null, progress = "正在读取目录…") }
        openReader().use { reader ->
            readerEntries = reader.entries()
            val encryptedMedia = ArchivePreview.build(readerEntries, "archive", emptyList()).flatMap { it.items }
                .firstOrNull { it.mediaType != null && it.entry.isEncrypted }?.entry
            if (encryptedMedia != null && password == null) {
                update { it.copy(step = ArchiveStep.Password) }
                return
            }
            // Verify an encrypted entry before showing a usable preview; output is discarded, never committed.
            encryptedMedia?.let { encrypted ->
                val coroutine = currentCoroutineContext()
                reader.extract(encrypted.index, object : java.io.OutputStream() {
                    override fun write(value: Int) {}
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {}
                }) { coroutine.ensureActive() }
            }
        }
        val artist = repository.observeArtist(requireNotNull(state.value.artistId)).first() ?: throw IOException("目标画师不存在。")
        val folderSource = state.value.format == "FOLDER"
        val title = if (folderSource) requireNotNull(mainName) + ".folder" else ArchiveBundle.title(requireNotNull(mainName)) + ".archive"
        var preview = ArchivePreview.build(readerEntries, title, artist.collections.map { it.name }, stripCommonContainer = !folderSource)
        preview.flatMap { it.items }.filter { it.mediaType != null }.forEach {
            if (it.entry.byteSize < 0 || it.entry.byteSize > ZipArchiveReader.MAX_ENTRY_BYTES) throw ArchiveReadException(ArchiveError.LIMIT, "媒体条目超过 2 GiB 限制。")
        }
        val oldItems = if (taskCommitted) repository.importItems(requireNotNull(sessionId)) else emptyList()
        if (taskCommitted) {
            val groups = dao.archiveGroups(requireNotNull(sessionId))
            preview = preview.map { group ->
                val stored = groups.firstOrNull { it.relativeDirectory == group.relativeDirectory }
                group.copy(name = stored?.let { dao.getCollection(it.id)?.name ?: it.name } ?: group.name)
            }
        }
        if (taskCommitted && oldItems.any { item -> readerEntries.none { "archive://$sessionId/${it.index}" == item.sourceUri && it.path == item.sourceRelativePath && it.byteSize == item.byteSize } }) {
            throw ArchiveReadException(ArchiveError.CORRUPT, "分卷目录与原任务不一致，请重新选择同一压缩包。")
        }
        update { it.copy(groups = preview, items = oldItems, step = ArchiveStep.Preview, message = null) }
        if (continueImport) { continueImport = false; importPending() }
    }

    fun confirm() {
        if (busy || state.value.groups.none { it.canImport }) return
        run {
            if (!taskCommitted) {
                val id = requireNotNull(sessionId)
                val now = System.currentTimeMillis()
                val groups = state.value.groups.map { ArchiveGroupEntity(UUID.randomUUID().toString(), id, requireNotNull(state.value.artistId), it.name, it.relativeDirectory) }
                val items = state.value.groups.flatMapIndexed { groupIndex, group ->
                    group.items.map { item ->
                        ImportItemEntity(UUID.randomUUID().toString(), id, null, "archive://$id/${item.entry.index}", false,
                            item.entry.path.substringAfterLast('/'), item.mediaType?.name ?: "UNKNOWN", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                            null, item.entry.byteSize, if (item.mediaType == null) "SKIPPED" else "QUEUED", item.skipReason, now + item.entry.index, now,
                            groups[groupIndex].id, item.entry.path)
                    }
                }
                database.withTransaction {
                    dao.insertImportBatch(ImportBatchEntity(id, null, "COPYING", now, now))
                    dao.insertImportItems(items)
                    dao.insertArchiveGroups(groups)
                    dao.insertArchiveTask(ArchiveTaskEntity(id, requireNotNull(state.value.artistId), state.value.format, requireNotNull(mainName), JSONArray(state.value.files).toString(), now))
                }
                taskCommitted = true
                File(directory(id), ".preparing").delete()
            }
            importPending()
        }
    }

    private suspend fun importPending() {
        try {
            importPendingItems()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            try {
                refreshResult(requireNotNull(sessionId))
                currentCoroutineContext().ensureActive()
                notifyImportCompleted()
            } catch (resultError: Exception) {
                if (resultError is CancellationException) throw resultError
                error.addSuppressed(resultError)
            }
            throw error
        }
        currentCoroutineContext().ensureActive()
        notifyImportCompleted()
    }

    private fun notifyImportCompleted() {
        val current = state.value
        if (cancelling || current.items.any { it.status == "CANCELLED" }) return
        val result = ImportCompletion(
            kind = ImportResultKind.Archive,
            batchId = requireNotNull(current.taskId),
            added = current.items.count { it.status == "COMPLETE" },
            reused = current.items.count { it.status == "REUSED" },
            skipped = current.items.count { it.status == "SKIPPED" },
            unresolved = current.items.count { it.status !in setOf("COMPLETE", "REUSED", "SKIPPED", "CANCELLED") },
        )
        try { onImportCompleted(result) } catch (_: RuntimeException) { }
    }

    private suspend fun importPendingItems() {
        val id = requireNotNull(sessionId)
        update { it.copy(step = ArchiveStep.Importing, message = null, reason = null) }
        repository.updateImportBatch(id, "COPYING")
        val entriesBySourceUri = readerEntries.associateBy { "archive://$id/${it.index}" }
        openReader().use { reader ->
            val items = repository.importItems(id)
            for ((position, item) in items.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (item.status in setOf("COMPLETE", "REUSED", "SKIPPED", "CANCELLED")) continue
                update { it.copy(progress = "${position + 1} / ${items.size} · ${item.sourceRelativePath}") }
                try {
                    val entry = entriesBySourceUri[item.sourceUri] ?: throw IOException("压缩条目不存在。")
                    if (entry.byteSize > files.availableBytes()) throw ArchiveReadException(ArchiveError.LIMIT, "可用空间不足，请释放空间后重试。")
                    val coroutine = currentCoroutineContext()
                    importer.importArchiveItem(item, hashWhileWriting = { item.status == "QUEUED" && reader.isOrdinaryZipEntry(entry.index) }) { output ->
                        reader.extract(entry.index, output) { coroutine.ensureActive() }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    val latest = repository.getImportItem(id, item.id) ?: throw IOException("导入条目不存在。", error)
                    if (latest.status !in setOf("COMPLETE", "REUSED", "SKIPPED")) {
                        if (latest.status !in setOf("STAGED", "FINALIZED")) files.deleteStaging(latest.stagingKey)
                        repository.updateImportItem(latest.copy(status = if (latest.status in setOf("STAGED", "FINALIZED")) latest.status else "FAILED", errorMessage = friendly(error), updatedAt = System.currentTimeMillis()))
                    }
                    if (error is ArchiveReadException && error.reason in setOf(ArchiveError.PASSWORD, ArchiveError.MISSING_VOLUME, ArchiveError.LIMIT)) {
                        continueImport = true
                        throw error
                    }
                }
            }
        }
        refreshResult(id)
        update { it.copy(step = ArchiveStep.Result) }
        password?.fill('\u0000'); password = null
        if (state.value.items.all { it.status in setOf("COMPLETE", "REUSED", "SKIPPED", "CANCELLED") }) cleanupVolumes()
    }

    fun retry() {
        if (busy) return
        run { continueImport = true; prepare() }
    }

    suspend fun hasUnfinishedItems(batchId: String): Boolean {
        if (dao.archiveTasks().none { it.id == batchId }) throw IOException("导入记录不存在。")
        return repository.importItems(batchId).any { it.status !in setOf("COMPLETE", "REUSED", "SKIPPED", "CANCELLED") }
    }

    fun openResult(batchId: String) {
        synchronized(jobLock) {
            pendingResultId = batchId
            if (busy) update { it.copy(visible = true, message = "当前导入结束后查看结果。") }
            drainPendingResult()
        }
    }

    private fun drainPendingResult() {
        synchronized(jobLock) {
            if (busy) return
            val id = pendingResultId ?: return
            pendingResultId = null
            run {
                // Read the target first: a missing record must not release a selected import.
                val task = dao.archiveTasks().firstOrNull { it.id == id }
                if (task == null) {
                    update { it.copy(visible = true, message = "导入记录不存在。") }
                    return@run
                }
                val names = JSONArray(task.selectedNames).let { array -> (0 until array.length()).map { array.getString(it) } }
                val items = repository.importItems(id)
                val collectionNames = dao.archiveGroups(id).mapNotNull { dao.getCollection(it.id)?.name }
                releaseSession()
                sessionId = id; mainName = task.mainName; taskCommitted = true
                update { ArchiveImportState(visible = true, step = ArchiveStep.Result, format = task.format,
                    artistId = task.artistId, taskId = id, files = names, items = items, collectionNames = collectionNames) }
            }
        }
    }

    fun resume(id: String) {
        if (busy) return
        run {
            releaseSession()
            val task = dao.archiveTasks().first { it.id == id }
            sessionId = id; mainName = task.mainName; taskCommitted = true
            val names = JSONArray(task.selectedNames).let { array -> (0 until array.length()).map { array.getString(it) } }
            val items = repository.importItems(id)
            update { ArchiveImportState(visible = true, step = ArchiveStep.Preparing, format = task.format, artistId = task.artistId, taskId = id, files = names, items = items) }
            refreshResult(id)
            if (state.value.items.all { it.status in setOf("COMPLETE", "REUSED", "SKIPPED", "CANCELLED") }) update { it.copy(step = ArchiveStep.Result) }
            else prepare()
        }
    }

    fun cancel() {
        if (cancelling) return
        cancelling = true
        update { it.copy(progress = "正在取消；已开始提交的媒体会先完成保存…") }
        val active = job
        active?.cancel()
        scope.launch {
            try {
                active?.join()
                withContext(NonCancellable) {
                    val id = sessionId
                    if (id != null && taskCommitted) {
                        repository.importItems(id).filter { it.status in setOf("STAGED", "FINALIZED") || it.status == "FAILED" && it.sha256 != null && files.hasOriginal(it.storageKey) }.forEach { item ->
                            try { importer.importArchiveItem(item) { error("已核验文件无需再次解压") } }
                            catch (_: Exception) {
                                if (files.hasOriginal(item.storageKey)) repository.updateImportItem(item.copy(status = "FINALIZED", errorMessage = "已核验文件仍待提交，可在下次启动后恢复。"))
                            }
                        }
                        repository.importItems(id).filter { it.status !in setOf("COMPLETE", "REUSED", "SKIPPED", "STAGED", "FINALIZED") }.forEach { item ->
                            files.deleteStaging(item.stagingKey)
                            repository.updateImportItem(item.copy(status = "CANCELLED", errorMessage = "用户已取消；成功项已保留。", updatedAt = System.currentTimeMillis()))
                        }
                        refreshResult(id)
                        if (repository.importItems(id).none { it.status in setOf("STAGED", "FINALIZED") }) cleanupVolumes()
                        update { it.copy(step = ArchiveStep.Result, message = "导入已取消，已经成功的媒体保留。") }
                    } else {
                        releaseSession()
                        update { ArchiveImportState() }
                    }
                }
            } catch (_: Exception) {
                update { it.copy(step = ArchiveStep.Error, message = "取消后的记录保存失败；已经核验的文件保留，重新打开应用后恢复。") }
            } finally {
                password?.fill('\u0000'); password = null
                continueImport = false; cancelling = false
                drainPendingResult()
            }
        }
    }

    fun done() {
        if (busy) { hide(); return }
        run { releaseSession(); update { ArchiveImportState() } }
    }

    suspend fun recover() {
        try {
            importer.recoverPending()
            val tasks = dao.archiveTasks()
            // Only UUID folders with our explicit preparation marker belong to interrupted selections.
            root.listFiles()?.filter { it.isDirectory && File(it, ".preparing").isFile && tasks.none { task -> task.id == it.name } }
                ?.forEach { entry -> runCatching { directory(entry.name) }.getOrNull()?.deleteRecursively() }
            tasks.forEach { task ->
                refreshResult(task.id)
                if (repository.importItems(task.id).all { it.status in setOf("COMPLETE", "REUSED", "SKIPPED", "CANCELLED") }) directory(task.id).deleteRecursively()
            }
            recovered.complete(Unit)
        } catch (error: Exception) {
            recovered.completeExceptionally(error)
            update { it.copy(step = ArchiveStep.Error, message = "导入记录恢复失败，请重新打开应用后重试。") }
        }
    }

    private suspend fun refreshResult(id: String) {
        val items = repository.importItems(id)
        val success = items.any { it.status in setOf("COMPLETE", "REUSED") }
        val status = when {
            items.any { it.status == "CANCELLED" } -> if (success) "PARTIAL_CANCELLED" else "CANCELLED"
            items.any { it.status !in setOf("COMPLETE", "REUSED", "SKIPPED") } -> if (success) "PARTIAL" else "FAILED"
            items.any { it.status == "SKIPPED" } -> "PARTIAL"
            else -> "COMPLETED"
        }
        repository.updateImportBatch(id, status)
        if (sessionId == id) {
            val names = dao.archiveGroups(id).mapNotNull { dao.getCollection(it.id)?.name }
            update { it.copy(items = items, collectionNames = names) }
        }
    }

    private suspend fun releaseSession() {
        if (!taskCommitted) cleanupVolumes()
        password?.fill('\u0000'); password = null
        sessionId = null; mainName = null; taskCommitted = false; continueImport = false; readerEntries = emptyList()
    }

    private fun cleanupVolumes() {
        sessionId?.let { id ->
            val folder = directory(id)
            if (folder.exists() && !folder.deleteRecursively()) throw ArchiveReadException(ArchiveError.IO, "导入结果已保留，但分卷暂存清理失败；下次启动会重试。")
        }
    }

    private fun run(block: suspend () -> Unit) {
        val scheduled = synchronized(jobLock) {
            if (busy) return
            scope.launch(start = CoroutineStart.LAZY) {
                try { recovered.await(); block() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    val reason = (error as? ArchiveReadException)?.reason
                    if (reason == ArchiveError.PASSWORD) { password?.fill('\u0000'); password = null }
                    update { it.copy(step = if (reason == ArchiveError.PASSWORD) ArchiveStep.Password else ArchiveStep.Error, message = friendly(error), reason = reason) }
                } finally {
                    synchronized(jobLock) { job = null }
                    drainPendingResult()
                }
            }.also { job = it }
        }
        scheduled.start()
    }

    private fun friendly(error: Exception): String = if (error is ArchiveReadException) error.message ?: "压缩读取失败。"
        else if (state.value.format == "FOLDER") "无法读取或保存文件夹任务，请检查目录授权、文件变化和可用空间后重试。"
        else "无法读取或保存压缩任务，请检查文件授权、可用空间后重试。"

    private fun describe(uri: Uri): String {
        val name = if (uri.scheme == "file") File(requireNotNull(uri.path)).name else
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        return ArchiveBundle.safeName(name ?: throw IOException("无法读取文件名称。"))
    }

    private suspend fun copyVolume(uri: Uri, target: File) {
        val part = File(target.parentFile, target.name + ".copying")
        var count = 0L
        var cachedAvailable = -1L
        var lastSpaceCount = 0L
        var lastProgressTime = SystemClock.elapsedRealtime()
        if (!cancelling) update { it.copy(progress = "${target.name} · 0 MB") }
        try {
            val expected = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val length = input.read(buffer)
                        if (length < 0) break
                        if (cachedAvailable < 0 || count - lastSpaceCount >= 8L * 1024 * 1024 || cachedAvailable < length + 16L * 1024 * 1024) {
                            cachedAvailable = StatFs(root.absolutePath).availableBytes
                            lastSpaceCount = count
                        }
                        if (cachedAvailable < length + 16L * 1024 * 1024) throw ArchiveReadException(ArchiveError.LIMIT, "可用空间不足，无法暂存分卷。")
                        output.write(buffer, 0, length)
                        count += length
                        cachedAvailable -= length
                        val now = SystemClock.elapsedRealtime()
                        if (!cancelling && now - lastProgressTime >= 200) {
                            update { it.copy(progress = "${target.name} · ${count / (1024 * 1024)} MB") }
                            lastProgressTime = now
                        }
                    }
                    output.fd.sync()
                }
            } ?: throw IOException("来源文件不可用。")
            if (expected >= 0 && expected != count) throw ArchiveReadException(ArchiveError.IO, "所选分卷复制不完整，请重新选择。")
            if (!part.renameTo(target)) throw IOException("无法保存所选分卷。")
        } finally {
            part.delete()
            if (!cancelling) update { it.copy(progress = "${target.name} · ${count / (1024 * 1024)} MB") }
        }
    }
}
