package dev.local.artistgallery.data.importing

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.artistgallery.data.*
import dev.local.artistgallery.model.ImportItemStatus
import dev.local.artistgallery.model.ImportProgress
import dev.local.artistgallery.model.SaveResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic files under a checked UUID cache root; no provider grants or device media. */
@RunWith(AndroidJUnit4::class)
class MediaImportProcessedProgressTest {
    @Test fun copyingTheLastItemNeverReportsCompleteBeforeTheDatabaseCommit() = runBlocking {
        fixture { f ->
            val progress = mutableListOf<ImportProgress>()
            f.beforeCommit = { assertEquals(0, progress.last().completedItems) }
            val result = f.importer.importUris(listOf(Uri.fromFile(f.source())), null) { progress.add(it) }
            assertEquals(0, progress.first().completedItems)
            assertTrue(progress.dropLast(1).all { it.completedItems == 0 })
            assertEquals(1, progress.last().completedItems)
            assertEquals(ImportItemStatus.COMPLETE, result.items.single().status)
            assertEquals(2, f.batchReads)
        }
    }

    @Test fun stagedFinalizedAndOriginalBackedRetriesAllPublishPostCommitCounts() = runBlocking {
        fixture { f ->
            val batch = UUID.randomUUID().toString()
            val items = listOf("STAGED", "FINALIZED", "FAILED").mapIndexed { index, status ->
                var item = f.item(batch, status, index)
                val staged = f.files.writeToStaging(item.stagingKey) { it.write(f.png()) }
                item = item.copy(sha256 = staged.sha256, byteSize = staged.byteSize)
                if (status != "STAGED") f.files.promote(item.stagingKey, item.storageKey)
                item
            }
            f.repo.insertImport(ImportBatchEntity(batch, null, "INTERRUPTED", 1, 1), items)
            val progress = mutableListOf<ImportProgress>()
            val result = f.importer.retryFailed(batch) { progress.add(it) }
            assertEquals(listOf(0, 1, 2, 3), progress.map { it.completedItems })
            assertTrue(result.items.all { it.status in setOf(ImportItemStatus.COMPLETE, ImportItemStatus.REUSED) })
            assertEquals(3, f.batchReads)
        }
    }

    @Test fun failuresAreProcessedWithoutBeingReportedAsSuccess() = runBlocking {
        fixture { f ->
            val batch = UUID.randomUUID().toString()
            f.repo.insertImport(ImportBatchEntity(batch, null, "FAILED", 1, 1),
                listOf(f.item(batch, "STAGED", 0).copy(sha256 = "synthetic-missing-staging", byteSize = 8000)))
            val progress = mutableListOf<ImportProgress>()
            val result = f.importer.retryFailed(batch) { progress.add(it) }
            assertEquals(listOf(0, 1), progress.map { it.completedItems })
            assertEquals(ImportItemStatus.FAILED, result.items.single().status)
        }
    }

    @Test fun systemInterruptionPersistsInterruptedBatchAndFinalizedCheckpointCanRetry() = runBlocking {
        fixture { f ->
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            f.beforeCommit = { entered.complete(Unit); gate.await() }
            val progress = mutableListOf<ImportProgress>()
            val job = launch { f.importer.importUris(listOf(Uri.fromFile(f.source())), null) { progress.add(it) } }
            withTimeout(10_000) { entered.await() }
            job.cancel(ImportWorkInterruptedException("合成系统中断")); job.join()
            val batch = f.repo.latestImportBatches().single()
            assertEquals("INTERRUPTED", batch.status)
            assertEquals(ImportItemStatus.FINALIZED, batch.items.single().status)
            assertTrue(progress.all { it.completedItems == 0 })
            f.beforeCommit = {}; gate.complete(Unit)
            val retry = f.importer.retryFailed(batch.id) { }
            assertEquals(ImportItemStatus.COMPLETE, retry.items.single().status)
        }
    }

    @Test fun copyingInterruptionMakesCurrentAndQueuedItemsRetryableInTheSameProcess() = runBlocking {
        fixture { f ->
            f.persistedSourcePermissions = true
            val artist = (f.repo.createArtist("合成中断画师") as SaveResult.Saved).id
            val target = (f.repo.createCollection(artist, "合成中断目标") as SaveResult.Saved).id
            val sources = listOf(f.source("first.png", 4 * 1024 * 1024), f.source("next.png"))
            lateinit var copying: Job
            copying = launch(start = CoroutineStart.LAZY) {
                f.importer.importUris(sources.map(Uri::fromFile), target) { progress ->
                    if (progress.copiedBytes > 0) copying.cancel(ImportWorkInterruptedException("合成复制中断"))
                }
            }
            copying.start(); copying.join()
            val batch = f.repo.latestImportBatches().single()
            val interrupted = f.repo.importItems(batch.id)
            assertEquals("INTERRUPTED", batch.status)
            assertEquals(listOf("FAILED", "FAILED"), interrupted.map { it.status })
            assertTrue(interrupted.all { it.hasPersistedPermission && !f.files.hasStaging(it.stagingKey) })
            assertTrue(interrupted.all { it.targetCollectionId == target })
            assertTrue(batch.items.all { it.canRetry })
            val retried = f.importer.retryFailed(batch.id) { }
            assertTrue(retried.items.all { it.status in setOf(ImportItemStatus.COMPLETE, ImportItemStatus.REUSED) })
            val finished = f.repo.importItems(batch.id)
            assertEquals(interrupted.map { it.id }, finished.map { it.id })
            assertEquals(interrupted.map { it.targetCollectionId }, finished.map { it.targetCollectionId })
        }
    }

    @Test fun cancellationDuringRetryKeepsUnverifiedItemsRetryableWithoutStartupRecovery() = runBlocking {
        fixture { f ->
            f.persistedSourcePermissions = true
            val batch = UUID.randomUUID().toString()
            val sources = listOf(f.source("retry-first.png", 4 * 1024 * 1024), f.source("retry-next.png"))
            val initial = sources.mapIndexed { index, source -> f.item(batch, "FAILED", index).copy(
                sourceUri = Uri.fromFile(source).toString(), hasPersistedPermission = true) }
            f.repo.insertImport(ImportBatchEntity(batch, null, "FAILED", 1, 1), initial)
            lateinit var retrying: Job
            retrying = launch(start = CoroutineStart.LAZY) {
                f.importer.retryFailed(batch) { progress ->
                    if (progress.copiedBytes > 0) retrying.cancel(ImportWorkInterruptedException("合成重试中断"))
                }
            }
            retrying.start(); retrying.join()
            assertEquals("INTERRUPTED", f.repo.latestImportBatches().single().status)
            val interrupted = f.repo.importItems(batch)
            assertEquals(listOf("FAILED", "FAILED"), interrupted.map { it.status })
            assertTrue(interrupted.all { it.hasPersistedPermission && !f.files.hasStaging(it.stagingKey) })
            val retried = f.importer.retryFailed(batch) { }
            assertTrue(retried.items.all { it.status in setOf(ImportItemStatus.COMPLETE, ImportItemStatus.REUSED) })
            assertEquals(initial.map { it.id }, f.repo.importItems(batch).map { it.id })
        }
    }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture()
        try { block(f) } finally { f.close() }
    }
    private class Fixture {
        private val base: Context = ApplicationProvider.getApplicationContext()
        private val root = File(base.cacheDir, "media-progress-${UUID.randomUUID()}").also { check(it.mkdir()) }
        private val context = object : ContextWrapper(base) { override fun getExternalFilesDir(type: String?) = root }
        private val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val repo = RoomGalleryRepository(db)
        val files = MediaFileStore(context)
        var beforeCommit: suspend () -> Unit = {}
        // File fixtures model retained permission metadata without obtaining any real provider grant.
        var persistedSourcePermissions = false
        var batchReads = 0
        private val delegate = object : MediaLibraryRepository by repo {
            override suspend fun insertImport(batch: ImportBatchEntity, items: List<ImportItemEntity>) {
                repo.insertImport(batch, items.map { if (persistedSourcePermissions) it.copy(hasPersistedPermission = true) else it })
            }
            override suspend fun updateImportItem(item: ImportItemEntity) {
                repo.updateImportItem(if (persistedSourcePermissions) item.copy(hasPersistedPermission = true) else item)
            }
            override suspend fun importItems(batchId: String): List<ImportItemEntity> { batchReads++; return repo.importItems(batchId) }
            override suspend fun commitNewAsset(asset: MediaAssetEntity, targetCollectionId: String?, sourceName: String) {
                beforeCommit(); repo.commitNewAsset(asset, targetCollectionId, sourceName)
            }
        }
        val importer = MediaImportCoordinator(context, delegate, files)
        fun source(name: String = "synthetic.png", size: Int = 8000) = File(root, name).also { it.writeBytes(png().copyOf(size)) }
        fun png(): ByteArray {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            return try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().copyOf(8000) }
            finally { bitmap.recycle() }
        }
        fun item(batch: String, status: String, index: Int) = ImportItemEntity(UUID.randomUUID().toString(), batch, null,
            "content://synthetic-progress/$index.png", false, "$index.png", "IMAGE", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            null, null, status, null, index.toLong(), 1)
        fun close() {
            db.close(); assertEquals(base.cacheDir.canonicalFile, root.canonicalFile.parentFile)
            assertTrue(root.name.startsWith("media-progress-")); root.deleteRecursively()
        }
    }
}
