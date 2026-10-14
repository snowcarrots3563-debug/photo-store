package dev.local.artistgallery.data.archive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.artistgallery.data.*
import dev.local.artistgallery.data.importing.MediaFileStore
import dev.local.artistgallery.data.importing.MediaImportCoordinator
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Random in-memory databases and UUID files only. No ordinary gallery data. */
@RunWith(AndroidJUnit4::class)
class ArchiveImportIntegrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GalleryDatabase::class.java)

    @Test fun migrationPreservesV2AssetReferenceAndInterruptedItem() {
        val name = "phase4-migration-${UUID.randomUUID()}.db"
        try {
            migration.createDatabase(name, 2).use { db ->
                db.execSQL("INSERT INTO Artist VALUES ('a', '已有画师', 1, 1)")
                db.execSQL("INSERT INTO WorkCollection VALUES ('c', 'a', '已有作品集', 0, 1, 1)")
                db.execSQL("INSERT INTO MediaAsset VALUES ('m', '原图.png', 'IMAGE', 7, NULL, NULL, 'hash', 'key', 'READY', 1)")
                db.execSQL("INSERT INTO CollectionMedia VALUES ('c', 'm', 4, '来源.png', '目录/来源.png')")
                db.execSQL("INSERT INTO ImportBatch VALUES ('b', 'c', 'INTERRUPTED', 1, 1)")
                db.execSQL("INSERT INTO ImportItem VALUES ('i', 'b', 'c', 'content://fixture', 0, '来源.png', 'IMAGE', 'stage', 'original', NULL, NULL, 'COPYING', NULL, 1, 1)")
            }
            migration.runMigrationsAndValidate(name, 3, true, GalleryDatabase.MIGRATION_2_3).use { db ->
                db.query("SELECT cm.manualOrder, cm.sourceName, ma.sha256 FROM CollectionMedia cm JOIN MediaAsset ma ON cm.assetId = ma.id").use {
                    assertTrue(it.moveToFirst()); assertEquals(4, it.getInt(0)); assertEquals("来源.png", it.getString(1)); assertEquals("hash", it.getString(2))
                }
                db.query("SELECT status, archiveGroupId, sourceRelativePath FROM ImportItem WHERE id = 'i'").use {
                    assertTrue(it.moveToFirst()); assertEquals("COPYING", it.getString(0)); assertTrue(it.isNull(1)); assertTrue(it.isNull(2))
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun firstSuccessCreatesGroupAtomicallyAndReusedContentCanEnterAnotherGroup() = runBlocking {
        withLibrary { db, repo, files, importer, items ->
            val artist = UUID.randomUUID().toString()
            db.galleryDao().insertArtist(ArtistEntity(artist, "画师", 1, 1))
            val groups = listOf(group(artist, "作品"), group(artist, "作品"))
            groups.forEach { db.galleryDao().insertArchiveGroups(listOf(it)) }
            val first = item(groups[0]); val same = item(groups[0]); val other = item(groups[1])
            items.addAll(listOf(first, same, other))
            items.groupBy { it.batchId }.forEach { (batch, entries) -> repo.insertImport(ImportBatchEntity(batch, null, "COPYING", 1, 1), entries) }
            val bytes = png()
            importer.importArchiveItem(first, hashWhileWriting = { true }) { it.write(bytes) }
            importer.importArchiveItem(same, hashWhileWriting = { true }) { it.write(bytes) }
            importer.importArchiveItem(other, hashWhileWriting = { true }) { it.write(bytes) }
            assertEquals(listOf("作品", "作品 (2)"), db.galleryDao().getCollections(artist).map { it.name }.sorted())
            assertEquals("COMPLETE", repo.importItems(first.batchId).first { it.id == first.id }.status)
            assertEquals("SKIPPED", repo.importItems(same.batchId).first { it.id == same.id }.status)
            assertEquals("REUSED", repo.importItems(other.batchId).first { it.id == other.id }.status)
            assertEquals(2, repo.referenceCount(first.id))
            assertFalse(files.hasStaging(same.stagingKey)); assertFalse(files.hasOriginal(other.storageKey))
        }
    }

    @Test fun failedTransactionDoesNotLeaveAnEmptyCollection() = runBlocking {
        withLibrary { db, repo, _, _, items ->
            val group = group(UUID.randomUUID().toString(), "无效目标")
            db.galleryDao().insertArchiveGroups(listOf(group))
            val item = item(group); items.add(item)
            repo.insertImport(ImportBatchEntity(item.batchId, null, "COPYING", 1, 1), listOf(item))
            val asset = MediaAssetEntity(item.id, item.sourceName, "IMAGE", 1, null, null, "hash", item.storageKey, "READY", 1)
            try { repo.commitArchiveImport(item, asset, false); fail("Missing artist must fail") } catch (_: java.io.IOException) {}
            assertNull(db.galleryDao().getCollection(group.id)); assertNull(repo.getAsset(asset.id))
            assertEquals("QUEUED", repo.importItems(item.batchId).single().status)
        }
    }

    @Test fun corruptMediaAndCancellationNeverCreateCollections() = runBlocking {
        withLibrary { db, repo, files, importer, items ->
            val artist = UUID.randomUUID().toString()
            db.galleryDao().insertArtist(ArtistEntity(artist, "画师", 1, 1))
            val group = group(artist, "损坏目录")
            db.galleryDao().insertArchiveGroups(listOf(group))
            val bad = item(group); val cancelled = item(group); items.addAll(listOf(bad, cancelled))
            items.groupBy { it.batchId }.forEach { (batch, entries) -> repo.insertImport(ImportBatchEntity(batch, null, "COPYING", 1, 1), entries) }
            try { importer.importArchiveItem(bad, hashWhileWriting = { true }) { it.write(ByteArray(6 * 1024) { 1 }) }; fail("Invalid PNG must fail") } catch (error: ArchiveReadException) { assertEquals(ArchiveError.CORRUPT, error.reason) }
            try { importer.importArchiveItem(cancelled, hashWhileWriting = { true }) { it.write(byteArrayOf(1)); throw CancellationException("fixture cancel") }; fail("Cancel must propagate") } catch (_: CancellationException) {}
            importer.recoverPending()
            assertTrue(db.galleryDao().getCollections(artist).isEmpty())
            items.forEach { assertFalse(files.hasStaging(it.stagingKey)); assertNull(repo.getAsset(it.id)) }
        }
    }

    @Test fun finalizedRecoveryCommitsOnceWithoutReadingArchiveAgain() = runBlocking {
        withLibrary { db, repo, files, importer, items ->
            val artist = UUID.randomUUID().toString()
            db.galleryDao().insertArtist(ArtistEntity(artist, "画师", 1, 1))
            val group = group(artist, "恢复目录"); db.galleryDao().insertArchiveGroups(listOf(group))
            val initial = item(group)
            val staged = files.writeToStaging(initial.stagingKey) { it.write(png()) }
            files.promote(initial.stagingKey, initial.storageKey)
            val item = initial.copy(status = "FINALIZED", sha256 = staged.sha256, byteSize = staged.byteSize); items.add(item)
            repo.insertImport(ImportBatchEntity(item.batchId, null, "INTERRUPTED", 1, 1), listOf(item))
            importer.recoverPending(); importer.recoverPending()
            assertEquals("COMPLETE", repo.importItems(item.batchId).single().status)
            assertEquals(1, repo.referenceCount(item.id)); assertEquals(1, db.galleryDao().getCollections(artist).size)
        }
    }

    @Test fun freshVerifiedStagingTamperingIsRejectedAfterPromotion() = runBlocking {
        withLibrary { db, repo, files, _, items ->
            val artist = UUID.randomUUID().toString()
            db.galleryDao().insertArtist(ArtistEntity(artist, "画师", 1, 1))
            val group = group(artist, "核验目录"); db.galleryDao().insertArchiveGroups(listOf(group))
            val initial = item(group); items.add(initial)
            repo.insertImport(ImportBatchEntity(initial.batchId, null, "COPYING", 1, 1), listOf(initial))
            val bytes = png()
            var tampered = false
            val intercepting = object : MediaLibraryRepository by repo {
                override suspend fun updateImportItem(item: ImportItemEntity) {
                    repo.updateImportItem(item)
                    if (item.id == initial.id && item.status == "STAGED") {
                        files.stagingFile(item.stagingKey).writeBytes(bytes.copyOf().apply { this[lastIndex] = 1 })
                        tampered = true
                    }
                }
            }
            val importer = MediaImportCoordinator(context, intercepting, files)
            try { importer.importArchiveItem(initial, hashWhileWriting = { true }) { it.write(bytes) }; fail("Promoted bytes must be checked") }
            catch (error: java.io.IOException) { assertEquals("图库文件核验失败。", error.message) }
            assertTrue(tampered)
            assertEquals("FINALIZED", repo.importItems(initial.batchId).single().status)
            assertTrue(files.hasOriginal(initial.storageKey)); assertFalse(files.hasStaging(initial.stagingKey))
            assertNull(repo.getAsset(initial.id)); assertTrue(db.galleryDao().getCollections(artist).isEmpty())
        }
    }

    @Test fun stagedRecoveryRejectsSameLengthTampering() = runBlocking {
        withLibrary { db, repo, files, importer, items ->
            val artist = UUID.randomUUID().toString()
            db.galleryDao().insertArtist(ArtistEntity(artist, "画师", 1, 1))
            val group = group(artist, "暂存恢复"); db.galleryDao().insertArchiveGroups(listOf(group))
            val initial = item(group)
            val bytes = png()
            val staged = files.writeToStaging(initial.stagingKey) { it.write(bytes) }
            val item = initial.copy(status = "STAGED", sha256 = staged.sha256, byteSize = staged.byteSize); items.add(item)
            repo.insertImport(ImportBatchEntity(item.batchId, null, "INTERRUPTED", 1, 1), listOf(item))
            files.stagingFile(item.stagingKey).writeBytes(bytes.copyOf().apply { this[lastIndex] = 1 })
            assertEquals(staged.byteSize, files.stagingFile(item.stagingKey).length())
            importer.recoverPending()
            val recovered = repo.importItems(item.batchId).single()
            assertEquals("FAILED", recovered.status); assertEquals("暂存文件核验失败。", recovered.errorMessage)
            assertFalse(files.hasStaging(item.stagingKey)); assertFalse(files.hasOriginal(item.storageKey))
            assertNull(repo.getAsset(item.id)); assertTrue(db.galleryDao().getCollections(artist).isEmpty())
        }
    }

    @Test fun stagedResumeCommitsWithoutReadingArchiveAgain() = runBlocking {
        withLibrary { db, repo, files, importer, items ->
            val artist = UUID.randomUUID().toString()
            db.galleryDao().insertArtist(ArtistEntity(artist, "画师", 1, 1))
            val group = group(artist, "合法暂存"); db.galleryDao().insertArchiveGroups(listOf(group))
            val initial = item(group)
            val staged = files.writeToStaging(initial.stagingKey) { it.write(png()) }
            val item = initial.copy(status = "STAGED", sha256 = staged.sha256, byteSize = staged.byteSize); items.add(item)
            repo.insertImport(ImportBatchEntity(item.batchId, null, "INTERRUPTED", 1, 1), listOf(item))
            importer.importArchiveItem(item) { fail("STAGED resume must not read archive") }
            importer.recoverPending()
            assertEquals("COMPLETE", repo.importItems(item.batchId).single().status)
            assertEquals(1, repo.referenceCount(item.id)); assertEquals(1, db.galleryDao().getCollections(artist).size)
            assertEquals(staged, files.inspectOriginal(item.storageKey)); assertFalse(files.hasStaging(item.stagingKey))
        }
    }

    @Test fun finalizedRecoveryRejectsSameLengthTampering() = runBlocking {
        withLibrary { db, repo, files, importer, items ->
            val artist = UUID.randomUUID().toString()
            db.galleryDao().insertArtist(ArtistEntity(artist, "画师", 1, 1))
            val group = group(artist, "原图恢复"); db.galleryDao().insertArchiveGroups(listOf(group))
            val initial = item(group)
            val bytes = png()
            val staged = files.writeToStaging(initial.stagingKey) { it.write(bytes) }
            files.promote(initial.stagingKey, initial.storageKey)
            val item = initial.copy(status = "FINALIZED", sha256 = staged.sha256, byteSize = staged.byteSize); items.add(item)
            repo.insertImport(ImportBatchEntity(item.batchId, null, "INTERRUPTED", 1, 1), listOf(item))
            files.originalFile(item.storageKey).writeBytes(bytes.copyOf().apply { this[lastIndex] = 1 })
            assertEquals(staged.byteSize, files.originalFile(item.storageKey).length())
            importer.recoverPending()
            val recovered = repo.importItems(item.batchId).single()
            assertEquals("FAILED", recovered.status); assertEquals("图库文件核验失败。", recovered.errorMessage)
            assertNull(repo.getAsset(item.id)); assertTrue(db.galleryDao().getCollections(artist).isEmpty())
        }
    }

    @Test fun stagedFinalizedAndPromotedFailedRetriesReadDiskWithoutEvaluatingZipEligibility() = runBlocking {
        withLibrary { db, repo, files, importer, items ->
            val artist = UUID.randomUUID().toString()
            db.galleryDao().insertArtist(ArtistEntity(artist, "画师", 1, 1))
            for (status in listOf("STAGED", "FINALIZED", "FAILED")) {
                val group = group(artist, "重试$status"); db.galleryDao().insertArchiveGroups(listOf(group))
                val initial = item(group)
                val bytes = png()
                val staged = files.writeToStaging(initial.stagingKey) { it.write(bytes) }
                if (status != "STAGED") files.promote(initial.stagingKey, initial.storageKey)
                val item = initial.copy(status = status, sha256 = staged.sha256, byteSize = staged.byteSize); items.add(item)
                repo.insertImport(ImportBatchEntity(item.batchId, null, "INTERRUPTED", 1, 1), listOf(item))
                val target = if (status == "STAGED") files.stagingFile(item.stagingKey) else files.originalFile(item.storageKey)
                target.writeBytes(bytes.copyOf().apply { this[lastIndex] = 1 })
                assertEquals(staged.byteSize, target.length())
                try {
                    importer.importArchiveItem(item, hashWhileWriting = { error("Recovery must not prepare ZIP or PDF entries") }) {
                        error("Recovery must not extract archive bytes")
                    }
                    fail("Changed retry file must be rejected")
                } catch (error: java.io.IOException) {
                    assertEquals(if (status == "STAGED") "暂存文件核验失败。" else "图库文件核验失败。", error.message)
                }
                assertNull(repo.getAsset(item.id)); assertEquals(0, repo.referenceCount(item.id))
            }
            assertTrue(db.galleryDao().getCollections(artist).isEmpty())
        }
    }

    @Test fun cancellationAfterStreamingStagedPersistenceFinishesTheCommitBoundary() = runBlocking {
        withLibrary { db, repo, files, _, items ->
            val artist = UUID.randomUUID().toString()
            db.galleryDao().insertArtist(ArtistEntity(artist, "画师", 1, 1))
            val group = group(artist, "取消边界"); db.galleryDao().insertArchiveGroups(listOf(group))
            val initial = item(group); items.add(initial)
            repo.insertImport(ImportBatchEntity(initial.batchId, null, "COPYING", 1, 1), listOf(initial))
            var eligibilityCalls = 0
            val bytes = png()
            val task = launch {
                val running = requireNotNull(coroutineContext[Job])
                val intercepting = object : MediaLibraryRepository by repo {
                    override suspend fun updateImportItem(item: ImportItemEntity) {
                        repo.updateImportItem(item)
                        if (item.id == initial.id && item.status == "STAGED") running.cancel()
                    }
                }
                MediaImportCoordinator(context, intercepting, files).importArchiveItem(initial, hashWhileWriting = {
                    eligibilityCalls++
                    true
                }) { it.write(bytes) }
            }
            task.join()
            assertTrue(task.isCancelled); assertEquals(1, eligibilityCalls)
            assertEquals("COMPLETE", repo.importItems(initial.batchId).single().status)
            assertNotNull(repo.getAsset(initial.id)); assertEquals(1, repo.referenceCount(initial.id))
            assertArrayEquals(bytes, files.originalFile(initial.storageKey).readBytes())
            assertFalse(files.hasStaging(initial.stagingKey))
        }
    }

    @Test fun oldSkippedPdfDoesNotBlockUnfinishedArchiveTaskAfterUpgrade() = runBlocking {
        withLibrary { db, repo, files, importer, items ->
            val artist = UUID.randomUUID().toString()
            db.galleryDao().insertArtist(ArtistEntity(artist, "恢复画师", 1, 1))
            val group = group(artist, "作品")
            db.galleryDao().insertArchiveGroups(listOf(group))
            val pdf = ByteArrayOutputStream().also { output ->
                val document = PdfDocument()
                try {
                    document.finishPage(document.startPage(PdfDocument.PageInfo.Builder(100, 200, 1).create()))
                    document.writeTo(output)
                } finally { document.close() }
            }.toByteArray()
            val image = png()
            val skipped = item(group).copy(sourceName = "book.pdf", mediaType = "UNKNOWN", byteSize = pdf.size.toLong(),
                status = "SKIPPED", errorMessage = "PDF 暂不导入。", sourceRelativePath = "作品/book.pdf")
            val pending = item(group).copy(sourceUri = "archive://${group.batchId}/1", byteSize = image.size.toLong(), status = "FAILED")
            items.addAll(listOf(skipped, pending))
            repo.insertImport(ImportBatchEntity(group.batchId, null, "INTERRUPTED", 1, 1), items)
            val root = File(requireNotNull(context.getExternalFilesDir(null)), ".archive-imports").canonicalFile
            val folder = File(root, group.batchId).canonicalFile
            assertEquals(root, folder.parentFile)
            assertTrue(folder.mkdirs())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                ZipOutputStream(File(folder, "resume.zip").outputStream()).use { zip ->
                    listOf("作品/book.pdf" to pdf, "目录/原件.png" to image).forEach { (path, bytes) ->
                        zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
                    }
                }
                db.galleryDao().insertArchiveTask(ArchiveTaskEntity(group.batchId, artist, "ZIP", "resume.zip", JSONArray(listOf("resume.zip")).toString(), 1))
                val controller = ArchiveImportCoordinator(context, db, repo, importer, files, scope)
                controller.recover()
                controller.resume(group.batchId)
                val state = withTimeout(10_000) { controller.state.first { it.step in setOf(ArchiveStep.Preview, ArchiveStep.Error) } }
                assertEquals(state.message, ArchiveStep.Preview, state.step)
                val retained = state.groups.flatMap { it.items }.single { it.entry.index == 0 }
                assertEquals(skipped.sourceRelativePath, retained.entry.path)
                assertEquals(skipped.byteSize, retained.entry.byteSize)
                assertNull(retained.mediaType)
                assertEquals("SKIPPED", repo.getImportItem(group.batchId, skipped.id)?.status)
                assertEquals("FAILED", repo.getImportItem(group.batchId, pending.id)?.status)
            } finally {
                scope.coroutineContext[Job]?.cancelAndJoin()
                assertEquals(root, folder.canonicalFile.parentFile)
                folder.deleteRecursively()
            }
        }
    }

    private suspend fun withLibrary(block: suspend (GalleryDatabase, RoomGalleryRepository, MediaFileStore, MediaImportCoordinator, MutableList<ImportItemEntity>) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val repo = RoomGalleryRepository(database); val files = MediaFileStore(context)
        val importer = MediaImportCoordinator(context, repo, files); val items = mutableListOf<ImportItemEntity>()
        try { block(database, repo, files, importer, items) }
        finally { items.forEach { files.deleteStaging(it.stagingKey); files.deleteOriginal(it.storageKey) }; database.close() }
    }

    private fun group(artistId: String, name: String) = ArchiveGroupEntity(UUID.randomUUID().toString(), UUID.randomUUID().toString(), artistId, name, name)
    private fun item(group: ArchiveGroupEntity): ImportItemEntity {
        val batch = group.batchId
        return ImportItemEntity(UUID.randomUUID().toString(), batch, null, "archive://$batch/0", false, "原件.png", "IMAGE", UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, null, "QUEUED", null, 1, 1, group.id, "目录/原件.png")
    }
    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() + ByteArray(6 * 1024) } } finally { bitmap.recycle() }
    }
}
