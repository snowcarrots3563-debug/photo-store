package dev.local.artistgallery.data.archive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.artistgallery.data.*
import dev.local.artistgallery.data.importing.ImportCompletion
import dev.local.artistgallery.data.importing.MediaFileStore
import dev.local.artistgallery.data.importing.MediaImportCoordinator
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Random in-memory metadata and checked UUID fixture files only; not run by the build. */
@RunWith(AndroidJUnit4::class)
class ArchiveImportNotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun recoveryAndReadOnlyResultNeverImportOrNotify() = runBlocking {
        withFixture { fixture ->
            val task = fixture.task(listOf("FAILED"))
            fixture.controller.recover()
            fixture.controller.openResult(task.id)
            val result = fixture.await(ArchiveStep.Result)
            assertEquals(task.id, result.taskId); assertEquals("FAILED", result.items.single().status)
            assertTrue(fixture.notices.isEmpty()); assertTrue(fixture.db.galleryDao().allAssets().isEmpty())
            assertTrue(fixture.db.galleryDao().allCollections().isEmpty())
        }
    }

    @Test fun unfinishedCheckSeparatesHistoryViewingFromRetryAndReportsMissing() = runBlocking {
        withFixture { fixture ->
            val complete = fixture.task(listOf("COMPLETE", "REUSED", "SKIPPED", "CANCELLED"))
            val failed = fixture.task(listOf("FAILED"))
            assertFalse(fixture.controller.hasUnfinishedItems(complete.id))
            assertTrue(fixture.controller.hasUnfinishedItems(failed.id))
            try { fixture.controller.hasUnfinishedItems(UUID.randomUUID().toString()); fail("Missing task") }
            catch (error: IOException) { assertEquals("导入记录不存在。", error.message) }
        }
    }

    @Test fun busyResultOpeningKeepsCurrentJobAndUsesLatestQueuedTarget() = runBlocking {
        withFixture { fixture ->
            val first = fixture.task(listOf("FAILED")); val second = fixture.task(listOf("FAILED")); val last = fixture.task(listOf("SKIPPED"))
            // The first result job waits for recovery; queued clicks must not release it.
            fixture.controller.openResult(first.id)
            fixture.controller.openResult(second.id); fixture.controller.openResult(last.id)
            assertNull(fixture.controller.state.value.taskId)
            assertEquals("当前导入结束后查看结果。", fixture.controller.state.value.message)
            fixture.controller.recover()
            val result = withTimeout(10_000) { fixture.controller.state.first { it.step == ArchiveStep.Result && it.taskId == last.id } }
            fixture.joinJobs()
            assertEquals(last.id, result.taskId); assertTrue(fixture.notices.isEmpty())
        }
    }

    @Test fun queuedResultDrainsAfterCancellationHasFinished() = runBlocking {
        withFixture { fixture ->
            val first = fixture.task(listOf("FAILED")); val next = fixture.task(listOf("SKIPPED"))
            fixture.controller.openResult(first.id)
            fixture.controller.openResult(next.id)
            fixture.controller.cancel()
            fixture.controller.recover()
            withTimeout(10_000) { fixture.controller.state.first { it.step == ArchiveStep.Result && it.taskId == next.id } }
            fixture.joinJobs()
            assertTrue(fixture.notices.isEmpty())
        }
    }

    @Test fun missingNotificationTargetPreservesHeldResult() = runBlocking {
        withFixture { fixture ->
            val task = fixture.task(listOf("FAILED"))
            fixture.controller.recover(); fixture.controller.openResult(task.id); fixture.await(ArchiveStep.Result)
            fixture.controller.openResult(UUID.randomUUID().toString()); fixture.joinJobs()
            val result = fixture.controller.state.value
            assertEquals(task.id, result.taskId); assertEquals(ArchiveStep.Result, result.step)
            assertEquals("导入记录不存在。", result.message)
        }
    }

    @Test fun actualAttemptNotifiesOnceAndRetryReplacesTheSameBatch() = runBlocking {
        withFixture { fixture ->
            val first = png(Color.RED); val second = png(Color.BLUE)
            val task = fixture.task(listOf("FAILED", "FAILED"), listOf(first, ByteArray(second.size)))
            fixture.controller.recover(); fixture.controller.resume(task.id); fixture.await(ArchiveStep.Preview)
            fixture.controller.confirm(); fixture.await(ArchiveStep.Result)
            assertEquals(1, fixture.notices.size)
            val partial = fixture.notices.single()
            assertEquals(1, partial.added); assertEquals(1, partial.unresolved)
            assertEquals("导入部分完成", partial.title)
            fixture.writeZip(task, listOf(first, second))
            fixture.controller.retry()
            withTimeout(10_000) { fixture.controller.state.first { it.step == ArchiveStep.Result && it.items.all { item -> item.status == "COMPLETE" } } }
            fixture.joinJobs()
            assertEquals(2, fixture.notices.size)
            assertEquals(partial.notificationTag, fixture.notices.last().notificationTag)
            assertEquals("导入完成", fixture.notices.last().title)
        }
    }

    @Test fun missingVolumeAfterPreviewNotifiesUnfinishedOnce() = runBlocking {
        withFixture { fixture ->
            val task = fixture.task(listOf("FAILED"), listOf(png(Color.RED)))
            fixture.controller.recover(); fixture.controller.resume(task.id); fixture.await(ArchiveStep.Preview)
            assertTrue(File(fixture.folder(task.id), task.mainName).delete())
            fixture.controller.confirm(); fixture.await(ArchiveStep.Error)
            assertEquals(1, fixture.notices.size); assertEquals(1, fixture.notices.single().unresolved)
            assertEquals("导入未完成", fixture.notices.single().title)
            assertEquals(ArchiveError.MISSING_VOLUME, fixture.controller.state.value.reason)
        }
    }

    @Test fun preparationFailureDoesNotNotify() = runBlocking {
        withFixture { fixture ->
            val task = fixture.task(listOf("FAILED"))
            fixture.controller.recover(); fixture.controller.resume(task.id); fixture.await(ArchiveStep.Error)
            assertTrue(fixture.notices.isEmpty())
        }
    }

    @Test fun notificationCallbackFailureDoesNotFailSavedImport() = runBlocking {
        withFixture(callbackFails = true) { fixture ->
            val task = fixture.task(listOf("FAILED"), listOf(png(Color.RED)))
            fixture.controller.recover(); fixture.controller.resume(task.id); fixture.await(ArchiveStep.Preview)
            fixture.controller.confirm()
            assertEquals("COMPLETE", fixture.await(ArchiveStep.Result).items.single().status)
            assertEquals(1, fixture.notices.size)
        }
    }

    private suspend fun withFixture(callbackFails: Boolean = false, block: suspend (Fixture) -> Unit) {
        val fixture = Fixture(callbackFails)
        try { block(fixture) } finally { fixture.close() }
    }

    private inner class Fixture(callbackFails: Boolean) {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val repo = RoomGalleryRepository(db)
        val files = MediaFileStore(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val notices: MutableList<ImportCompletion> = Collections.synchronizedList(mutableListOf())
        private val items = mutableListOf<ImportItemEntity>()
        private val taskIds = mutableListOf<String>()
        private val root = File(requireNotNull(context.getExternalFilesDir(null)), ".archive-imports").canonicalFile
        val controller = ArchiveImportCoordinator(context, db, repo, MediaImportCoordinator(context, repo, files), files, scope) { result ->
            notices.add(result)
            if (callbackFails) throw SecurityException("fixture notification failure")
        }

        suspend fun task(statuses: List<String>, bytes: List<ByteArray>? = null): ArchiveTaskEntity {
            val artist = UUID.randomUUID().toString(); val batch = UUID.randomUUID().toString(); val group = UUID.randomUUID().toString()
            db.galleryDao().insertArtist(ArtistEntity(artist, "合成通知画师", 1, 1))
            val entries = statuses.mapIndexed { index, status -> ImportItemEntity(UUID.randomUUID().toString(), batch, null,
                "archive://$batch/$index", false, "$index.png", "IMAGE", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                null, bytes?.get(index)?.size?.toLong(), status, null, index.toLong(), 1, group, "目录/$index.png") }
            items.addAll(entries); taskIds.add(batch)
            repo.insertImport(ImportBatchEntity(batch, null, "PARTIAL", 1, 1), entries)
            db.galleryDao().insertArchiveGroups(listOf(ArchiveGroupEntity(group, batch, artist, "目录", "目录")))
            val task = ArchiveTaskEntity(batch, artist, "ZIP", "fixture.zip", "[\"fixture.zip\"]", 1)
            db.galleryDao().insertArchiveTask(task)
            if (bytes != null) writeZip(task, bytes)
            return task
        }

        fun folder(id: String): File = File(root, UUID.fromString(id).toString()).canonicalFile.also { assertEquals(root, it.parentFile) }
        fun writeZip(task: ArchiveTaskEntity, bytes: List<ByteArray>) {
            val folder = folder(task.id)
            assertTrue(folder.isDirectory || folder.mkdirs())
            ZipOutputStream(File(folder, task.mainName).outputStream()).use { zip ->
                bytes.forEachIndexed { index, image -> zip.putNextEntry(ZipEntry("目录/$index.png")); zip.write(image); zip.closeEntry() }
            }
        }

        suspend fun await(step: ArchiveStep): ArchiveImportState {
            val result = withTimeout(10_000) { controller.state.first { it.step == step || it.step == ArchiveStep.Error } }
            assertEquals(result.message, step, result.step)
            joinJobs()
            return result
        }
        suspend fun joinJobs() = withTimeout(10_000) { scope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
        suspend fun close() {
            scope.coroutineContext[Job]?.cancelAndJoin()
            items.forEach { files.deleteStaging(it.stagingKey); files.deleteOriginal(it.storageKey) }
            taskIds.forEach { folder(it).deleteRecursively() }
            db.close()
        }
    }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().copyOf(8000) }
        finally { bitmap.recycle() }
    }
}
