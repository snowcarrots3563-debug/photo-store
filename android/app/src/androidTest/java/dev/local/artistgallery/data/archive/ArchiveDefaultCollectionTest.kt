package dev.local.artistgallery.data.archive

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.artistgallery.data.*
import dev.local.artistgallery.data.importing.MediaFileStore
import dev.local.artistgallery.data.importing.MediaImportCoordinator
import dev.local.artistgallery.model.SaveResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Generated sources, private fixture storage and in-memory Room; no user gallery or picker. */
@RunWith(AndroidJUnit4::class)
class ArchiveDefaultCollectionTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var db: GalleryDatabase
    private lateinit var repo: RoomGalleryRepository
    private lateinit var files: MediaFileStore
    private lateinit var scope: CoroutineScope
    private val dao get() = db.galleryDao()

    @Before fun prepare() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(base.cacheDir, "archive-default-${UUID.randomUUID()}")
        assertTrue(root.mkdir())
        context = object : ContextWrapper(base) { override fun getExternalFilesDir(type: String?): File = root }
        db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        repo = RoomGalleryRepository(db)
        files = MediaFileStore(context)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After fun cleanUp() = runBlocking {
        scope.coroutineContext[Job]?.cancelAndJoin()
        db.close()
        assertEquals(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile, root.canonicalFile.parentFile)
        assertTrue(root.name.startsWith("archive-default-"))
        root.deleteRecursively()
        Unit
    }

    @Test fun managementPreviewPredictsCollisionNameAndCancelDoesNotCreateDefault() = runBlocking {
        val artist = oldArtist()
        val ordinary = CollectionEntity(UUID.randomUUID().toString(), artist, "默认图集", false, 1, 1)
        dao.insertCollection(ordinary)
        val controller = controller()
        controller.open(null); controller.selectArtist(artist)
        controller.pick(listOf(Uri.fromFile(zip("root.png" to png()))), false)
        val preview = await(controller, ArchiveStep.Preview)
        assertEquals("默认图集（2）", preview.groups.single().name)
        assertEquals(listOf(ordinary), dao.getCollections(artist))
        assertTrue(dao.archiveTasks().isEmpty())
        controller.cancel()
        withTimeout(15_000) { controller.state.first { !it.visible } }
        assertEquals(listOf(ordinary), dao.getCollections(artist))
        assertTrue(dao.archiveTasks().isEmpty())
    }

    @Test fun newRootPersistsActualDefaultTargetAndKeepsSubdirectoryPdfAndTxtRules() = runBlocking {
        val artist = oldArtist()
        val controller = selection(artist, zip("root.png" to png(), "notes.txt" to "fixture".toByteArray(),
            "系列/child.png" to png(1), "book.pdf" to pdf()))
        val preview = controller.state.value
        assertEquals("默认图集", preview.groups.single { it.relativeDirectory.isEmpty() }.name)
        assertTrue(dao.getCollections(artist).isEmpty())
        controller.confirm()
        val result = await(controller, ArchiveStep.Result)
        val target = dao.getCollections(artist).single { it.isDefault }
        val groups = dao.archiveGroups(requireNotNull(result.taskId))
        val rootGroup = groups.single { it.relativeDirectory.isEmpty() }
        assertNotEquals(target.id, rootGroup.id)
        assertEquals(target.name, rootGroup.name)
        val rootItems = result.items.filter { it.archiveGroupId == rootGroup.id }
        assertEquals(setOf("COMPLETE", "SKIPPED"), rootItems.map { it.status }.toSet())
        assertTrue(rootItems.all { it.targetCollectionId == target.id })
        assertEquals("notes.txt", rootItems.single { it.status == "SKIPPED" }.sourceRelativePath)
        val subdirectory = groups.single { it.relativeDirectory == "系列" }
        val book = groups.single { it.relativeDirectory == "book" }
        assertFalse(dao.getCollection(subdirectory.id)!!.isDefault)
        assertFalse(dao.getCollection(book.id)!!.isDefault)
        assertEquals(subdirectory.id, result.items.single { it.archiveGroupId == subdirectory.id }.targetCollectionId)
        assertTrue(result.items.filter { it.archiveGroupId == book.id }.all { it.status == "COMPLETE" && it.targetCollectionId == book.id })
        assertEquals(listOf(target.name, "系列", "book"), result.collectionNames)
        assertEquals(3, dao.allAssets().size)
        assertEquals(3, dao.allReferences().size)
    }

    @Test fun sameNamedOrdinaryCollectionIsKeptAndRootUsesTheNewMarkedDefault() = runBlocking {
        val artist = oldArtist()
        val ordinary = CollectionEntity(UUID.randomUUID().toString(), artist, "默认图集", false, 1, 1)
        dao.insertCollection(ordinary)
        val controller = selection(artist, zip("root.png" to png()))
        controller.confirm(); val result = await(controller, ArchiveStep.Result)
        val default = dao.getCollections(artist).single { it.isDefault }
        assertEquals("默认图集（2）", default.name)
        assertEquals(ordinary, dao.getCollection(ordinary.id))
        assertEquals(default.id, result.items.single().targetCollectionId)
        assertTrue(repo.observeCollectionMedia(ordinary.id).first().isEmpty())
    }

    @Test fun twoBatchesReuseOneDefaultAndDeduplicateWithoutSharingArchiveGroupIds() = runBlocking {
        val artist = oldArtist()
        val bytes = png()
        val first = selection(artist, zip("one.png" to bytes, "two.png" to bytes))
        first.confirm()
        val firstResult = await(first, ArchiveStep.Result)
        val default = dao.getCollections(artist).single()
        assertEquals(listOf("COMPLETE", "SKIPPED"), firstResult.items.map { it.status })
        val references = dao.allReferences()
        val second = selection(artist, zip("three.png" to bytes))
        second.confirm()
        val secondResult = await(second, ArchiveStep.Result)
        assertEquals("SKIPPED", secondResult.items.single().status)
        assertEquals(listOf(default), dao.getCollections(artist))
        assertEquals(references, dao.allReferences())
        assertEquals(0, references.single().manualOrder)
        assertEquals("one.png", references.single().sourceName)
        assertEquals("one.png", references.single().sourceRelativePath)
        assertTrue((firstResult.items + secondResult.items).all { it.targetCollectionId == default.id })
        val firstGroup = dao.archiveGroups(requireNotNull(firstResult.taskId)).single()
        val secondGroup = dao.archiveGroups(requireNotNull(secondResult.taskId)).single()
        assertNotEquals(firstGroup.id, secondGroup.id)
        assertNotEquals(default.id, firstGroup.id)
        assertNotEquals(default.id, secondGroup.id)
        assertEquals(listOf(default.name), secondResult.collectionNames)
    }

    @Test fun renamedDefaultIsReusedInNewPreviewAndResultReadsItsCurrentName() = runBlocking {
        val artist = oldArtist()
        val target = (repo.ensureDefaultCollection(artist) as SaveResult.Saved).id
        repo.renameCollection(target, "常用收藏")
        val controller = selection(artist, zip("root.png" to png()))
        assertEquals("常用收藏", controller.state.value.groups.single().name)
        controller.confirm(); val result = await(controller, ArchiveStep.Result)
        assertEquals(target, result.items.single().targetCollectionId)
        repo.renameCollection(target, "改名后的收藏")
        controller.openResult(requireNotNull(result.taskId))
        assertEquals(listOf("改名后的收藏"), await(controller, ArchiveStep.Result).collectionNames)
        assertEquals(1, dao.getCollections(artist).size)
        db.openHelper.writableDatabase.execSQL("DELETE FROM CollectionMedia WHERE collectionId = ?", arrayOf(target))
        db.openHelper.writableDatabase.execSQL("DELETE FROM WorkCollection WHERE id = ?", arrayOf(target))
        controller.openResult(requireNotNull(result.taskId))
        assertTrue(await(controller, ArchiveStep.Result).collectionNames.isEmpty())
    }

    @Test fun confirmUsesTheDefaultRenamedAfterPreviewInsteadOfItsPredictedName() = runBlocking {
        val artist = oldArtist()
        val controller = selection(artist, zip("root.png" to png()))
        val target = (repo.ensureDefaultCollection(artist) as SaveResult.Saved).id
        repo.renameCollection(target, "确认时名称")
        controller.confirm(); val result = await(controller, ArchiveStep.Result)
        assertEquals(target, result.items.single().targetCollectionId)
        assertEquals("确认时名称", dao.archiveGroups(requireNotNull(result.taskId)).single().name)
        assertEquals(listOf("确认时名称"), result.collectionNames)
    }

    @Test fun retryAndCommittedPreviewResolveRenamedActualDefault() = runBlocking {
        val artist = oldArtist()
        val controller = selection(artist, zip("root.png" to png()))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_asset BEFORE INSERT ON MediaAsset BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        controller.confirm(); val failed = await(controller, ArchiveStep.Result)
        assertEquals("FINALIZED", failed.items.single().status)
        val target = requireNotNull(failed.items.single().targetCollectionId)
        assertTrue(dao.getCollection(target)!!.isDefault)
        assertTrue(dao.allAssets().isEmpty())
        repo.renameCollection(target, "重试目标")
        val reopened = controller()
        reopened.resume(requireNotNull(failed.taskId))
        val preview = await(reopened, ArchiveStep.Preview)
        assertEquals("重试目标", preview.groups.single().name)
        assertEquals(listOf("重试目标"), preview.collectionNames)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_asset")
        reopened.retry(); val retried = await(reopened, ArchiveStep.Result)
        assertEquals("COMPLETE", retried.items.single().status)
        assertEquals(target, retried.items.single().targetCollectionId)
        assertEquals(listOf("重试目标"), retried.collectionNames)
        assertEquals(1, dao.getCollections(artist).size)
    }

    @Test fun actualConfirmRetainsEmptyDefaultWhenEveryRootMediaFails() = runBlocking {
        val artist = oldArtist()
        val controller = selection(artist, zip("broken.png" to ByteArray(6000)))
        controller.confirm(); val result = await(controller, ArchiveStep.Result)
        assertEquals("FAILED", result.items.single().status)
        val target = dao.getCollections(artist).single()
        assertTrue(target.isDefault)
        assertEquals(target.id, result.items.single().targetCollectionId)
        assertTrue(dao.allAssets().isEmpty())
        assertTrue(dao.allReferences().isEmpty())
        assertEquals(listOf(target.name), result.collectionNames)
    }

    @Test fun skippedOnlyRootDoesNotCreateDefaultForSubdirectoryTask() = runBlocking {
        val artist = oldArtist()
        val controller = selection(artist, zip("notes.txt" to "fixture".toByteArray(), "系列/a.png" to png()))
        controller.confirm(); val result = await(controller, ArchiveStep.Result)
        assertTrue(dao.getCollections(artist).none { it.isDefault })
        assertNull(result.items.single { it.sourceName == "notes.txt" }.targetCollectionId)
        assertEquals("系列", dao.getCollections(artist).single().name)
    }

    @Test fun artistRemovedAfterPreviewCannotCommitRootOrSubdirectoryTask() = runBlocking {
        listOf("root.png", "系列/a.png").forEach { path ->
            val artist = oldArtist()
            val controller = selection(artist, zip(path to png()))
            assertTrue(repo.deleteArtistIfEmpty(artist))
            controller.confirm(); await(controller, ArchiveStep.Error)
            assertTrue(dao.allCollections().isEmpty())
            assertTrue(dao.archiveTasks().isEmpty())
            assertTrue(repo.importItems(requireNotNull(controller.state.value.taskId)).isEmpty())
            assertTrue(repo.latestImportBatches().isEmpty())
            assertTrue(dao.archiveGroups(requireNotNull(controller.state.value.taskId)).isEmpty())
            controller.cancel(); withTimeout(15_000) { controller.state.first { !it.visible } }
        }
    }

    @Test fun taskInsertFailureRollsBackDefaultGroupsItemsAndBatch() = runBlocking {
        val artist = oldArtist()
        val controller = selection(artist, zip("root.png" to png()))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_task BEFORE INSERT ON ArchiveTask BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        controller.confirm(); await(controller, ArchiveStep.Error)
        val batch = requireNotNull(controller.state.value.taskId)
        assertTrue(dao.getCollections(artist).isEmpty())
        assertTrue(dao.archiveGroups(batch).isEmpty())
        assertTrue(repo.importItems(batch).isEmpty())
        assertTrue(repo.latestImportBatches().isEmpty())
        assertTrue(dao.archiveTasks().isEmpty())
        assertNotNull(dao.getArtist(artist))
    }

    @Test fun failedDefaultEnsureCannotPersistANewTask() = runBlocking {
        val artist = oldArtist()
        val controller = selection(artist, zip("root.png" to png()))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_default BEFORE INSERT ON WorkCollection WHEN NEW.isDefault = 1 BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        controller.confirm(); await(controller, ArchiveStep.Error)
        assertTrue(dao.getCollections(artist).isEmpty())
        assertTrue(dao.archiveTasks().isEmpty())
        assertTrue(repo.latestImportBatches().isEmpty())
        assertTrue(repo.importItems(requireNotNull(controller.state.value.taskId)).isEmpty())
    }

    @Test fun missingAndForeignPersistedTargetsRejectBeforeInsertingAssetsOrReferences() = runBlocking {
        val artist = oldArtist()
        val foreignArtist = oldArtist()
        val foreign = (repo.ensureDefaultCollection(foreignArtist) as SaveResult.Saved).id
        listOf(UUID.randomUUID().toString(), foreign).forEach { target ->
            val item = pendingItem(artist, target)
            assertThrows(IOException::class.java) { runBlocking { repo.commitArchiveImport(item.copy(targetCollectionId = null), asset(item), false) } }
            assertNull(dao.getCollection(requireNotNull(item.archiveGroupId)))
            assertEquals(item, repo.getImportItem(item.batchId, item.id))
            assertTrue(dao.allAssets().isEmpty())
            assertTrue(dao.allReferences().isEmpty())
        }
        assertTrue(dao.getCollections(artist).isEmpty())
    }

    @Test fun commitHonorsLatestTargetAndMetadataAndKeepsTerminalItemsIdempotent() = runBlocking {
        val artist = oldArtist()
        val target = (repo.ensureDefaultCollection(artist) as SaveResult.Saved).id
        val ordinary = (repo.createCollection(artist, "普通") as SaveResult.Saved).id
        val latest = pendingItem(artist, target).copy(sha256 = "persisted-hash", sourceName = "persisted.png", sourceRelativePath = "persisted.png")
        repo.updateImportItem(latest)
        val stale = latest.copy(targetCollectionId = ordinary, sha256 = null, sourceName = "stale.png", sourceRelativePath = "stale.png")
        repo.commitArchiveImport(stale, asset(latest), false)
        val saved = requireNotNull(repo.getImportItem(latest.batchId, latest.id))
        assertEquals(target, saved.targetCollectionId)
        assertEquals("persisted-hash", saved.sha256)
        assertEquals("persisted.png", saved.sourceName)
        assertEquals("persisted.png", dao.allReferences().single().sourceRelativePath)
        assertEquals(target, dao.allReferences().single().collectionId)
        assertNull(dao.getCollection(requireNotNull(latest.archiveGroupId)))
        listOf("COMPLETE", "REUSED", "SKIPPED").forEach { status ->
            val terminal = saved.copy(status = status)
            repo.updateImportItem(terminal)
            repo.commitArchiveImport(stale, asset(latest).copy(id = UUID.randomUUID().toString()), false)
            assertEquals(terminal, repo.getImportItem(latest.batchId, latest.id))
        }
        assertEquals(1, dao.allAssets().size)
        assertEquals(1, dao.allReferences().size)
    }

    @Test fun persistedTargetReuseRequiresAReadyAssetAndAppendsReferenceOrder() = runBlocking {
        val artist = oldArtist()
        val target = (repo.ensureDefaultCollection(artist) as SaveResult.Saved).id
        val previous = pendingItem(artist, target)
        repo.commitArchiveImport(previous, asset(previous), false)
        val item = pendingItem(artist, target)
        val reused = asset(item).copy(id = UUID.randomUUID().toString(), state = "PENDING_DELETE")
        dao.insertAsset(reused)
        assertThrows(IOException::class.java) { runBlocking { repo.commitArchiveImport(item, reused, true) } }
        assertEquals(1, dao.allReferences().size)
        assertEquals(item, repo.getImportItem(item.batchId, item.id))
        dao.updateAsset(reused.copy(state = "READY"))
        repo.commitArchiveImport(item, reused, true)
        assertEquals("REUSED", repo.getImportItem(item.batchId, item.id)!!.status)
        val reference = dao.allReferences().single { it.assetId == reused.id }
        assertEquals(target, reference.collectionId)
        assertEquals(1, reference.manualOrder)
        assertNull(dao.getCollection(requireNotNull(item.archiveGroupId)))
    }

    @Test fun oldCommittedNullTargetResumesIntoItsOriginalOrdinaryGroup() = runBlocking {
        val artist = oldArtist()
        val source = zip("root.png" to png())
        val batch = UUID.randomUUID().toString()
        val folder = File(File(root, ".archive-imports"), batch).also { assertTrue(it.mkdirs()) }
        source.copyTo(File(folder, "legacy.zip"))
        val entry = ZipArchiveReader(File(folder, "legacy.zip"), listOf(File(folder, "legacy.zip")), Long.MAX_VALUE).use { it.entries().single() }
        val item = pendingItem(artist, null, batch, entry.index, entry.byteSize)
        dao.insertArchiveTask(ArchiveTaskEntity(batch, artist, "ZIP", "legacy.zip", JSONArray(listOf("legacy.zip")).toString(), 1))
        val controller = controller()
        controller.resume(batch)
        assertEquals("旧批次", await(controller, ArchiveStep.Preview).groups.single().name)
        assertTrue(dao.getCollections(artist).isEmpty())
        controller.confirm(); val result = await(controller, ArchiveStep.Result)
        val collection = dao.getCollections(artist).single()
        assertEquals(item.archiveGroupId, collection.id)
        assertFalse(collection.isDefault)
        assertEquals(collection.id, result.items.single().targetCollectionId)
        val saved = result.items.single()
        repo.renameCollection(collection.id, "旧普通组改名")
        controller.openResult(batch)
        assertEquals(listOf("旧普通组改名"), await(controller, ArchiveStep.Result).collectionNames)
        assertEquals(saved, repo.importItems(batch).single())
        assertTrue(dao.getCollections(artist).none { it.isDefault })
    }

    private suspend fun controller(): ArchiveImportCoordinator = ArchiveImportCoordinator(context, db, repo,
        MediaImportCoordinator(context, repo, files), files, scope).also { it.recover() }

    private suspend fun selection(artist: String, source: File): ArchiveImportCoordinator = controller().also {
        it.open(artist); it.pick(listOf(Uri.fromFile(source)), false); await(it, ArchiveStep.Preview)
    }

    private suspend fun await(controller: ArchiveImportCoordinator, step: ArchiveStep): ArchiveImportState = withTimeout(20_000) {
        scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
        val state = controller.state.first { it.step == step || it.step == ArchiveStep.Error }
        assertEquals(state.message, step, state.step)
        state
    }

    private suspend fun oldArtist(): String = UUID.randomUUID().toString().also { dao.insertArtist(ArtistEntity(it, "合成旧画师", 1, 1)) }

    private suspend fun pendingItem(artist: String, target: String?, batch: String = UUID.randomUUID().toString(), index: Int = 0, size: Long = 6000): ImportItemEntity {
        val group = ArchiveGroupEntity(UUID.randomUUID().toString(), batch, artist, "旧批次", "")
        val item = ImportItemEntity(UUID.randomUUID().toString(), batch, target, "archive://$batch/$index", false, "root.png", "IMAGE",
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, size, "QUEUED", null, 1, 1, group.id, "root.png")
        repo.insertImport(ImportBatchEntity(batch, null, "INTERRUPTED", 1, 1), listOf(item))
        dao.insertArchiveGroups(listOf(group))
        return item
    }

    private fun asset(item: ImportItemEntity) = MediaAssetEntity(item.id, item.sourceName, "IMAGE", 6000, null, null, "fixture-hash", item.storageKey, "READY", 1)

    private fun zip(vararg entries: Pair<String, ByteArray>): File = File(root, "fixture-${UUID.randomUUID()}.zip").also { source ->
        ZipOutputStream(source.outputStream()).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }

    private fun png(padding: Int = 0): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() + ByteArray(6000) { padding.toByte() } }
        finally { bitmap.recycle() }
    }

    private fun pdf(): ByteArray {
        val document = PdfDocument()
        return try {
            document.finishPage(document.startPage(PdfDocument.PageInfo.Builder(100, 200, 1).create()))
            ByteArrayOutputStream().also(document::writeTo).toByteArray()
        } finally { document.close() }
    }
}
