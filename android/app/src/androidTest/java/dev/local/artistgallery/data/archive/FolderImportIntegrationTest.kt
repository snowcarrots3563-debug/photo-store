package dev.local.artistgallery.data.archive

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.artistgallery.data.*
import dev.local.artistgallery.data.importing.MediaFileStore
import dev.local.artistgallery.data.importing.MediaImportCoordinator
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** An isolated provider, random task files and in-memory databases; no system picker or user media. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class FolderImportIntegrationTest {
    private val baseContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var provider: TreeProvider
    private lateinit var context: Context
    private val tree get() = DocumentsContract.buildTreeDocumentUri("folder.fixture", "root:id")

    @Before fun prepare() {
        root = File(baseContext.cacheDir, "folder-fixture-${UUID.randomUUID()}")
        assertTrue(root.mkdir())
        provider = TreeProvider(root)
        provider.attachInfo(baseContext, ProviderInfo().apply { authority = "folder.fixture" })
        val resolver = ContentResolver.wrap(provider)
        context = object : ContextWrapper(baseContext) { override fun getContentResolver(): ContentResolver = resolver }
    }

    @After fun cleanUp() {
        assertEquals(baseContext.cacheDir.canonicalFile, root.canonicalFile.parentFile)
        assertTrue(root.name.startsWith("folder-fixture-"))
        root.deleteRecursively()
    }

    @Test fun recursiveTreeKeepsRootTitleAndDeepGroupsAndQueriesEachDirectoryOnce() {
        provider.directory("series:id", "系列", "root:id")
        provider.directory("chapter:id", "第一章", "series:id")
        provider.directory("empty:id", "空目录", "root:id")
        provider.file("root:image", "散图.png", "root:id", ByteArray(6000) { 1 }, size = null)
        provider.file("deep:gif", "a.gif", "chapter:id", ByteArray(6000) { 2 })
        provider.file("only:video", "a.mp4", "series:id", ByteArray(6000) { 3 })
        provider.file("txt:skip", "说明.txt", "root:id", ByteArray(50_000))
        provider.file("tiny:skip", "small.jpg", "root:id", ByteArray(5119))
        provider.file("index:skip", "folder.index", "root:id", ByteArray(50_000))
        val session = session()
        assertEquals("收藏.v2.zip", snapshot(session))
        FolderArchiveReader(session).use { reader ->
            val entries = reader.entries()
            assertEquals((entries.indices).toList(), entries.map { it.index })
            assertEquals(6000L, entries.single { it.path == "散图.png" }.byteSize)
            val preview = ArchivePreview.build(entries, "收藏.v2.zip.folder", listOf("收藏.v2.zip"), stripCommonContainer = false)
            assertEquals(setOf("收藏.v2.zip (2)", "系列", "系列/第一章"), preview.filter { it.canImport }.map { it.name }.toSet())
            assertFalse(preview.single { it.name == "空目录" }.canImport)
            assertTrue(preview.flatMap { it.items }.filter { it.entry.path in setOf("说明.txt", "small.jpg", "folder.index") }.all { it.skipReason != null })
            val image = entries.single { it.path == "散图.png" }
            assertArrayEquals(ByteArray(6000) { 1 }, extract(reader, image.index))
        }
        assertEquals(setOf("root:image", "deep:gif", "only:video"), provider.opened.toSet())
        assertEquals(setOf("root:id", "series:id", "chapter:id", "empty:id"), provider.childQueries.keys)
        assertTrue(provider.childQueries.values.all { it == 1 })
    }

    @Test fun duplicateDirectoryDisplayNamesRemainSeparateCollections() {
        provider.directory("a:id", "同名", "root:id")
        provider.directory("b:id", "同名", "root:id")
        provider.directory("c:id", "同名 (2)", "root:id")
        listOf("a:id", "b:id", "c:id").forEach { provider.file("$it:image", "a.png", it, ByteArray(6000)) }
        val session = session(); snapshot(session)
        val preview = ArchivePreview.build(FolderArchiveReader(session).entries(), "收藏.folder", emptyList(), stripCommonContainer = false)
        assertEquals(setOf("同名", "同名 (2)", "同名 (3)"), preview.filter { it.canImport }.map { it.name }.toSet())
    }

    @Test fun directoryWithMultipleParentsPreservesBothRelativeGroupsAndQueriesItOnce() {
        provider.directory("left:id", "左", "root:id")
        provider.directory("right:id", "右", "root:id")
        provider.directory("shared:id", "共享", "left:id")
        provider.alias("shared:id", "right:id")
        provider.file("image:id", "a.png", "shared:id", ByteArray(6000))
        val session = session(); snapshot(session)
        val preview = ArchivePreview.build(FolderArchiveReader(session).entries(), "收藏.folder", emptyList(), stripCommonContainer = false)
        assertEquals(setOf("左/共享", "右/共享"), preview.filter { it.canImport }.map { it.name }.toSet())
        assertEquals(1, provider.childQueries["shared:id"])
        assertTrue(provider.childQueries.values.all { it == 1 })
    }

    @Test fun actualDirectoryAncestryLoopIsRejectedWithoutPublishingAnIndex() {
        provider.directory("cycle:id", "循环", "root:id")
        provider.alias("cycle:id", "cycle:id")
        val session = session()
        try { snapshot(session); fail("Directory loop accepted") }
        catch (error: ArchiveReadException) { assertEquals(ArchiveError.CORRUPT, error.reason) }
        assertFalse(File(session, "folder.index").exists())
    }

    @Test fun sourceMutationAndNewFilesDoNotChangeAnExistingSnapshot() {
        val original = ByteArray(6000) { 17 }
        val source = provider.file("opaque:/not/a/path", "a.png", "root:id", original, size = null)
        val session = session(); snapshot(session)
        val before = FolderArchiveReader(session).entries()
        source.writeBytes(ByteArray(6000) { 19 })
        provider.file("new:id", "new.png", "root:id", ByteArray(6000))
        FolderArchiveReader(session).use { reader ->
            assertEquals(before, reader.entries())
            assertArrayEquals(original, extract(reader, before.single().index))
        }
        assertEquals(1, provider.childQueries["root:id"])
        assertEquals(listOf("opaque:/not/a/path"), provider.opened)
    }

    @Test fun incompleteProviderListsAndDeniedPermissionsNeverPublishAnEmptySnapshot() {
        provider.nullDirectory = "root:id"
        expectFailure { snapshot(session()) }
        provider.nullDirectory = null; provider.loadingDirectory = "root:id"
        expectFailure { snapshot(session()) }
        provider.loadingDirectory = null; provider.deny = true
        expectFailure { snapshot(session()) }
        assertTrue(root.listFiles()!!.filter { it.name.startsWith("session-") }.all { !File(it, "folder.index").exists() })
    }

    @Test fun cancellationSpaceAndChangedDeclaredSizeNeverPublishAPartialSnapshot() {
        provider.file("image:id", "a.png", "root:id", ByteArray(6000))
        val cancelled = session()
        var stop = false
        try {
            FolderArchiveReader.snapshot(context, tree, cancelled, { Long.MAX_VALUE }, { if (stop) throw CancellationException("fixture") }) {
                if (it.endsWith(" MB")) stop = true
            }
            fail("Cancelled snapshot completed")
        } catch (_: CancellationException) {}
        assertFalse(File(cancelled, "folder.index").exists())
        assertTrue(cancelled.listFiles()!!.none { it.name.endsWith(".partial") })
        val noSpace = session()
        try { FolderArchiveReader.snapshot(context, tree, noSpace, { 16L * 1024 * 1024 }, {}, {}); fail("Space limit ignored") }
        catch (error: ArchiveReadException) { assertEquals(ArchiveError.LIMIT, error.reason) }
        assertFalse(File(noSpace, "folder.index").exists())
        provider.nodes.single { it.id == "image:id" }.size = 6001L
        val changed = session(); expectFailure { snapshot(changed) }
        assertFalse(File(changed, "folder.index").exists())
    }

    @Test fun entryLimitUnsafeNamesAndUnavailableVirtualFilesAreHandledExplicitly() {
        provider.file("virtual:id", "virtual.png", "root:id", ByteArray(6000), flags = DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT)
        provider.file("partial:id", "partial.mp4", "root:id", ByteArray(6000), flags = DocumentsContract.Document.FLAG_PARTIAL)
        val session = session(); snapshot(session)
        val skipped = FolderArchiveReader(session).entries()
        assertTrue(skipped.single { it.path == "virtual.png" }.skipReason!!.contains("虚拟"))
        assertTrue(skipped.single { it.path == "partial.mp4" }.skipReason!!.contains("完整"))
        assertTrue(provider.opened.isEmpty())
        provider.file("unsafe:id", "../unsafe.png", "root:id", ByteArray(6000))
        try { snapshot(session()); fail("Unsafe path accepted") }
        catch (error: ArchiveReadException) { assertEquals(ArchiveError.UNSAFE_PATH, error.reason) }
        provider.nodes.removeAll { it.id != "root:id" }
        repeat(ZipArchiveReader.MAX_ENTRIES + 1) { index -> provider.nodes.add(Node("n$index", "a$index.txt", "root:id", null, 0, 0)) }
        try { snapshot(session()); fail("Entry limit ignored") }
        catch (error: ArchiveReadException) { assertEquals(ArchiveError.LIMIT, error.reason) }
    }

    @Test fun declaredOversizedMediaFailsBeforeOpeningItsPayload() {
        provider.file("huge:id", "huge.mp4", "root:id", ByteArray(6000), size = ZipArchiveReader.MAX_ENTRY_BYTES + 1)
        val session = session()
        try { snapshot(session); fail("Media size limit ignored") }
        catch (error: ArchiveReadException) { assertEquals(ArchiveError.LIMIT, error.reason) }
        assertTrue(provider.opened.isEmpty())
        assertFalse(File(session, "folder.index").exists())
    }

    @Test fun indexSpaceChecksAreBatchedInsteadOfCheckingEachMetadataField() {
        repeat(500) { index -> provider.nodes.add(Node("txt:$index", "说明$index.txt", "root:id", File(root, "unused-source"), null, 0)) }
        val session = session()
        var spaceChecks = 0
        FolderArchiveReader.snapshot(context, tree, session, { spaceChecks++; Long.MAX_VALUE }, {}, {})
        assertEquals(500, FolderArchiveReader(session).entries().size)
        assertTrue("Index performed $spaceChecks storage queries", spaceChecks in 1..2)
        assertTrue(provider.opened.isEmpty())
    }

    @Test fun folderPdfUsesExistingPageCacheAndRetainsSiblingMedia() {
        val document = PdfDocument()
        val pdf = try {
            repeat(2) { page -> document.finishPage(document.startPage(PdfDocument.PageInfo.Builder(100, 200, page + 1).create())) }
            ByteArrayOutputStream().also(document::writeTo).toByteArray()
        } finally { document.close() }
        provider.file("pdf:id", "book.pdf", "root:id", pdf)
        provider.file("image:id", "a.png", "root:id", ByteArray(6000))
        val session = session(); snapshot(session)
        val first = PdfArchiveReader(FolderArchiveReader(session), session, { Long.MAX_VALUE }, {}).use { reader ->
            val entries = reader.entries()
            assertEquals(listOf("book/第0001页.png", "book/第0002页.png"), entries.filter { it.path.endsWith("页.png") }.map { it.path })
            entries.map { it to extract(reader, it.index) }
        }
        assertTrue("pdf:id" in provider.opened)
        provider.nodes.single { it.id == "pdf:id" }.file!!.delete()
        PdfArchiveReader(FolderArchiveReader(session), session, { Long.MAX_VALUE }, {}).use { reader ->
            assertEquals(first.map { it.first }, reader.entries())
            first.forEach { (entry, bytes) -> assertArrayEquals(bytes, extract(reader, entry.index)) }
        }
    }

    @Test fun folderSourceModeSurvivesPermissionFailureAndCannotRequestPassword() = runBlocking {
        withTask { db, repo, files, importer, scope ->
            val controller = ArchiveImportCoordinator(context, db, repo, importer, files, scope)
            controller.recover(); controller.openFolder("artist")
            assertEquals("FOLDER", controller.state.value.format)
            controller.requestPassword(); assertEquals(ArchiveStep.Source, controller.state.value.step)
            val password = "unused".toCharArray(); controller.unlock(password)
            assertTrue(password.all { it == '\u0000' })
            provider.deny = true; controller.pickFolder(tree)
            val state = await(controller, ArchiveStep.Error, scope)
            assertEquals("FOLDER", state.format)
            assertTrue(db.galleryDao().archiveTasks().isEmpty())
            val taskFolder = File(File(requireNotNull(context.getExternalFilesDir(null)), ".archive-imports"), requireNotNull(state.taskId))
            assertTrue(File(taskFolder, ".preparing").isFile)
            controller.cancel()
            withTimeout(10_000) { controller.state.first { !it.visible } }
            assertFalse(taskFolder.exists())
        }
    }

    @Test fun confirmedFolderResumesFromSnapshotAndNumbersExistingCollection() = runBlocking { resumeFixture(tamper = false) }
    @Test fun snapshotTamperingCannotCommitAnAssetAndRestoredSnapshotCanRetry() = runBlocking { resumeFixture(tamper = true) }

    @Test fun newFolderRootUsesDefaultOnlyAfterConfirmationAndKeepsSubdirectoryTarget() = runBlocking {
        provider.file("root:image", "root.png", "root:id", png())
        provider.file("root:txt", "notes.txt", "root:id", "fixture".toByteArray())
        provider.directory("series:id", "系列", "root:id")
        provider.file("series:image", "child.png", "series:id", png())
        withTask { db, repo, files, importer, scope ->
            val artist = UUID.randomUUID().toString()
            db.galleryDao().insertArtist(ArtistEntity(artist, "合成旧画师", 1, 1))
            val controller = ArchiveImportCoordinator(context, db, repo, importer, files, scope)
            var task: String? = null
            try {
                controller.recover(); controller.openFolder(artist); controller.pickFolder(tree)
                val preview = await(controller, ArchiveStep.Preview, scope)
                task = preview.taskId
                assertEquals("默认图集", preview.groups.single { it.relativeDirectory.isEmpty() }.name)
                assertTrue(db.galleryDao().getCollections(artist).isEmpty())
                assertTrue(db.galleryDao().archiveTasks().isEmpty())
                controller.cancel(); withTimeout(15_000) { controller.state.first { !it.visible } }
                assertTrue(db.galleryDao().getCollections(artist).isEmpty())
                controller.openFolder(artist); controller.pickFolder(tree)
                task = await(controller, ArchiveStep.Preview, scope).taskId
                controller.confirm()
                val result = await(controller, ArchiveStep.Result, scope)
                val groups = db.galleryDao().archiveGroups(requireNotNull(task))
                val rootGroup = groups.single { it.relativeDirectory.isEmpty() }
                val default = db.galleryDao().getCollections(artist).single { it.isDefault }
                assertNotEquals(default.id, rootGroup.id)
                assertTrue(result.items.filter { it.archiveGroupId == rootGroup.id }.all { it.targetCollectionId == default.id })
                assertEquals("SKIPPED", result.items.single { it.sourceName == "notes.txt" }.status)
                val child = result.items.single { it.sourceName == "child.png" }
                assertEquals(child.archiveGroupId, child.targetCollectionId)
                assertFalse(db.galleryDao().getCollection(requireNotNull(child.targetCollectionId))!!.isDefault)
                assertEquals(listOf("默认图集", "系列"), result.collectionNames)
                assertEquals(2, db.galleryDao().allReferences().size)
            } finally {
                task?.let { id ->
                    repo.importItems(id).forEach { files.deleteStaging(it.stagingKey); files.deleteOriginal(it.storageKey) }
                    val taskRoot = File(requireNotNull(context.getExternalFilesDir(null)), ".archive-imports").canonicalFile
                    val taskFolder = File(taskRoot, id).canonicalFile
                    assertEquals(taskRoot, taskFolder.parentFile)
                    taskFolder.deleteRecursively()
                }
            }
        }
    }

    private suspend fun resumeFixture(tamper: Boolean) {
        val bytes = png()
        val source = provider.file("image:id", "a.png", "root:id", bytes)
        withTask { db, repo, files, importer, scope ->
            val artist = UUID.randomUUID().toString(); val batch = UUID.randomUUID().toString(); val groupId = UUID.randomUUID().toString()
            db.galleryDao().insertArtist(ArtistEntity(artist, "合成画师", 1, 1))
            db.galleryDao().insertCollection(CollectionEntity(UUID.randomUUID().toString(), artist, "收藏.v2.zip", false, 1, 1))
            val taskRoot = File(requireNotNull(context.getExternalFilesDir(null)), ".archive-imports").canonicalFile
            val taskFolder = File(taskRoot, batch).canonicalFile
            assertEquals(taskRoot, taskFolder.parentFile); assertTrue(taskFolder.mkdirs())
            var item: ImportItemEntity? = null
            try {
                snapshot(taskFolder)
                source.writeBytes(ByteArray(bytes.size) { 99 })
                val entry = FolderArchiveReader(taskFolder).entries().single()
                val pending = ImportItemEntity(UUID.randomUUID().toString(), batch, null, "archive://$batch/${entry.index}", false, "a.png", "IMAGE",
                    UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, entry.byteSize, "QUEUED", null, 1, 1, groupId, entry.path)
                item = pending
                repo.insertImport(ImportBatchEntity(batch, null, "INTERRUPTED", 1, 1), listOf(pending))
                db.galleryDao().insertArchiveGroups(listOf(ArchiveGroupEntity(groupId, batch, artist, "收藏.v2.zip", "")))
                db.galleryDao().insertArchiveTask(ArchiveTaskEntity(batch, artist, "FOLDER", "收藏.v2.zip", JSONArray(listOf("收藏.v2.zip")).toString(), 1))
                if (tamper) File(taskFolder, "data-0").writeBytes(bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
                val controller = ArchiveImportCoordinator(context, db, repo, importer, files, scope)
                controller.recover(); controller.resume(batch)
                assertEquals("FOLDER", await(controller, ArchiveStep.Preview, scope).format)
                controller.confirm()
                var result = await(controller, ArchiveStep.Result, scope)
                if (tamper) {
                    assertEquals("FAILED", result.items.single().status)
                    assertNull(repo.getAsset(pending.id)); assertNull(db.galleryDao().getCollection(groupId))
                    assertFalse(files.hasStaging(pending.stagingKey))
                    File(taskFolder, "data-0").writeBytes(bytes)
                    controller.retry()
                    withTimeout(15_000) { controller.state.first { it.step == ArchiveStep.Error || it.step == ArchiveStep.Result && it.items.single().status == "COMPLETE" } }
                    result = await(controller, ArchiveStep.Result, scope)
                }
                assertEquals(result.message, "COMPLETE", result.items.single().status)
                assertEquals("收藏.v2.zip (2)", db.galleryDao().getCollection(groupId)?.name)
                assertEquals(1, repo.referenceCount(pending.id))
                assertEquals(1, provider.childQueries["root:id"])
                assertEquals(listOf("image:id"), provider.opened)
                val reopened = ArchiveImportCoordinator(context, db, repo, importer, files, scope)
                reopened.recover(); reopened.resume(batch)
                assertEquals("COMPLETE", await(reopened, ArchiveStep.Result, scope).items.single().status)
                assertEquals(1, repo.referenceCount(pending.id))
                assertFalse(taskFolder.exists())
            } finally {
                item?.let { files.deleteStaging(it.stagingKey); files.deleteOriginal(it.storageKey) }
                assertEquals(taskRoot, taskFolder.canonicalFile.parentFile)
                taskFolder.deleteRecursively()
            }
        }
    }

    private suspend fun withTask(block: suspend (GalleryDatabase, RoomGalleryRepository, MediaFileStore, MediaImportCoordinator, CoroutineScope) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val repository = RoomGalleryRepository(database); val files = MediaFileStore(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try { block(database, repository, files, MediaImportCoordinator(context, repository, files), scope) }
        finally { scope.coroutineContext[Job]?.cancelAndJoin(); database.close() }
    }

    private suspend fun await(controller: ArchiveImportCoordinator, step: ArchiveStep, scope: CoroutineScope): ArchiveImportState =
        withTimeout(15_000) {
            val state = controller.state.first { it.step == step || it.step == ArchiveStep.Error }
            assertEquals(state.message, step, state.step)
            scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
            state
        }
    private fun session() = File(root, "session-${UUID.randomUUID()}").also { assertTrue(it.mkdir()) }
    private fun snapshot(session: File) = FolderArchiveReader.snapshot(context, tree, session, { Long.MAX_VALUE }, {}, {})
    private fun extract(reader: ArchiveReader, index: Int): ByteArray = ByteArrayOutputStream().also { reader.extract(index, it) {} }.toByteArray()
    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("Expected provider failure") } catch (_: IOException) {} catch (_: SecurityException) {}
    }
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() + ByteArray(6000) }
        finally { bitmap.recycle() }
    }

    private data class Node(val id: String, val name: String, val parent: String?, val file: File?, var size: Long?, val flags: Int)
    private class TreeProvider(private val root: File) : ContentProvider() {
        val nodes = mutableListOf(Node("root:id", "收藏.v2.zip", null, null, 0, 0))
        val childQueries = mutableMapOf<String, Int>()
        val opened = mutableListOf<String>()
        var nullDirectory: String? = null
        var loadingDirectory: String? = null
        var deny = false
        override fun onCreate() = true
        fun directory(id: String, name: String, parent: String) { nodes.add(Node(id, name, parent, null, 0, 0)) }
        fun alias(id: String, parent: String) { nodes.add(nodes.first { it.id == id }.copy(parent = parent)) }
        fun file(id: String, name: String, parent: String, bytes: ByteArray, size: Long? = bytes.size.toLong(), flags: Int = 0): File =
            File(root, "source-${UUID.randomUUID()}").also { it.writeBytes(bytes); nodes.add(Node(id, name, parent, it, size, flags)) }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            if (deny) throw SecurityException("fixture denied")
            val id = DocumentsContract.getDocumentId(uri)
            val children = uri.lastPathSegment == "children"
            if (children) {
                childQueries[id] = (childQueries[id] ?: 0) + 1
                if (id == nullDirectory) return null
            }
            val cursor = MatrixCursor(requireNotNull(projection))
            nodes.filter { if (children) it.parent == id else it.id == id }.forEach { node ->
                cursor.addRow(projection.map { column -> when (column) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> node.id
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> node.name
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> if (node.file == null) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                    DocumentsContract.Document.COLUMN_SIZE -> node.size
                    DocumentsContract.Document.COLUMN_FLAGS -> node.flags
                    else -> null
                } })
            }
            if (children && id == loadingDirectory) cursor.setExtras(Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) })
            return cursor
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (deny) throw SecurityException("fixture denied")
            val id = DocumentsContract.getDocumentId(uri)
            opened.add(id)
            return ParcelFileDescriptor.open(requireNotNull(nodes.single { it.id == id }.file), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun getType(uri: Uri): String = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only fixture")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only fixture")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only fixture")
    }
}
