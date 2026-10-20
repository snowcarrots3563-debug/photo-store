package dev.local.artistgallery.data.archive

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.artistgallery.data.GalleryDatabase
import dev.local.artistgallery.data.ImportBatchEntity
import dev.local.artistgallery.data.ImportItemEntity
import dev.local.artistgallery.data.RoomGalleryRepository
import dev.local.artistgallery.data.importing.MediaFileStore
import dev.local.artistgallery.data.importing.MediaImportCoordinator
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic history and isolated cache files only; never opens the ordinary gallery. */
@RunWith(AndroidJUnit4::class)
class ArchiveRecoveryQueryTest {
    @Test fun completedHistoryReadsEachTaskOnceAndKeepsUnchangedTimestamps() = runBlocking {
        withFixture { f ->
            val ids = List(3) { f.task(List(80) { "COMPLETE" }, "COMPLETED") }
            ids.forEach { f.residue(it) }
            f.queries.clear()

            f.archive.recover()
            f.archive.awaitRecovery()

            val reads = synchronized(f.queries) { f.queries.toList() }.filter { (sql, _) ->
                sql.trim().replace(Regex("\\s+"), " ").equals(
                    "SELECT * FROM ImportItem WHERE batchId = ? ORDER BY createdAt ASC", ignoreCase = true)
            }
            assertEquals(ids.size, reads.size)
            ids.forEach { id ->
                assertEquals(1, reads.count { it.second.single() == id })
                assertEquals("COMPLETED" to 9L, f.batch(id))
                assertFalse(f.folder(id).exists())
            }
        }
    }

    @Test fun conditionalStatusUpdateWritesOnlyWhenStatusDiffers() = runBlocking {
        withFixture { f ->
            val id = f.task(emptyList(), "COMPLETED")
            val dao = f.db.galleryDao()
            assertEquals(0, dao.updateImportBatchStatusIfChanged(id, "COMPLETED", 10))
            assertEquals("COMPLETED" to 9L, f.batch(id))
            assertEquals(1, dao.updateImportBatchStatusIfChanged(id, "PARTIAL", 20))
            assertEquals("PARTIAL" to 20L, f.batch(id))
            assertEquals(0, dao.updateImportBatchStatusIfChanged(id, "PARTIAL", 30))
            assertEquals("PARTIAL" to 20L, f.batch(id))
            assertEquals(0, dao.updateImportBatchStatusIfChanged(UUID.randomUUID().toString(), "FAILED", 40))
        }
    }

    @Test fun recoveryPreservesStatusPriorityAndCleanupEligibilityIncludingPdfPages() = runBlocking {
        withFixture { f ->
            val cases = listOf(
                Triple(emptyList(), "COMPLETED", true),
                Triple(listOf("COMPLETE", "REUSED"), "COMPLETED", true),
                Triple(listOf("SKIPPED"), "PARTIAL", true),
                Triple(listOf("COMPLETE", "SKIPPED"), "PARTIAL", true),
                Triple(listOf("CANCELLED"), "CANCELLED", true),
                Triple(listOf("COMPLETE", "CANCELLED"), "PARTIAL_CANCELLED", true),
                Triple(listOf("FAILED"), "FAILED", false),
                Triple(listOf("COMPLETE", "FAILED"), "PARTIAL", false),
                Triple(listOf("UNKNOWN"), "FAILED", false),
                Triple(listOf("CANCELLED", "FAILED"), "CANCELLED", false),
                Triple(listOf("REUSED", "CANCELLED", "FAILED"), "PARTIAL_CANCELLED", false),
            )
            val tasks = cases.map { (statuses, result, cleanup) ->
                Triple(f.task(statuses), result, cleanup).also { f.residue(it.first) }
            }

            f.archive.recover()
            f.archive.awaitRecovery()

            tasks.forEach { (id, expected, cleanup) ->
                val (status, updatedAt) = f.batch(id)
                assertEquals(expected, status)
                assertTrue(updatedAt > 9L)
                assertEquals(!cleanup, f.folder(id).exists())
            }
        }
    }

    @Test fun pendingMediaRecoveryPrecedesSummaryAndTerminalResidueIsRetried() = runBlocking {
        withFixture { f ->
            val pending = f.task(listOf("COMPLETE", "QUEUED"))
            val terminal = f.task(listOf("CANCELLED"), "CANCELLED")
            f.residue(pending)
            f.residue(terminal)

            f.archive.recover()
            f.archive.awaitRecovery()

            assertEquals(listOf("COMPLETE", "FAILED"), f.repo.importItems(pending).map { it.status })
            assertEquals("PARTIAL", f.batch(pending).first)
            assertTrue(f.folder(pending).exists())
            assertFalse(f.folder(terminal).exists())
            assertEquals("CANCELLED" to 9L, f.batch(terminal))

            f.residue(terminal)
            f.archive.recover()
            assertFalse(f.folder(terminal).exists())
            assertTrue(f.folder(pending).exists())
            assertEquals("CANCELLED" to 9L, f.batch(terminal))
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.close() }
    }

    private class Fixture {
        private val base: Context = ApplicationProvider.getApplicationContext()
        private val root = File(base.cacheDir, "archive-recovery-query-${UUID.randomUUID()}").canonicalFile.also {
            check(it.parentFile == base.cacheDir.canonicalFile)
            check(it.mkdir())
        }
        private val external = File(root, "external").also { check(it.mkdir()) }
        private val context = object : ContextWrapper(base) { override fun getExternalFilesDir(type: String?) = external }
        val queries: MutableList<Pair<String, List<Any?>>> = Collections.synchronizedList(mutableListOf())
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java)
            .setQueryCallback({ sql, args -> queries.add(sql to args.toList()) }, Executor { it.run() }).build()
        val repo = RoomGalleryRepository(db)
        private val files = MediaFileStore(context)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val archive = ArchiveImportCoordinator(context, db, repo, MediaImportCoordinator(context, repo, files), files, scope)

        suspend fun task(statuses: List<String>, status: String = "INTERRUPTED"): String {
            val id = UUID.randomUUID().toString()
            val items = statuses.mapIndexed { index, itemStatus ->
                ImportItemEntity(UUID.randomUUID().toString(), id, null, "content://fixture/page/$index", false,
                    "page-$index.png", "IMAGE", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    null, null, itemStatus, null, index.toLong(), 9,
                    archiveGroupId = UUID.randomUUID().toString(), sourceRelativePath = "book.pdf/page-$index.png")
            }
            repo.insertImport(ImportBatchEntity(id, null, status, 1, 9), items)
            db.galleryDao().insertArchiveTask(ArchiveTaskEntity(id, "artist", "ZIP", "fixture.zip", "[]", 1))
            return id
        }

        fun folder(id: String) = File(File(external, ".archive-imports"), UUID.fromString(id).toString())
        fun residue(id: String) {
            val folder = folder(id)
            check(folder.canonicalFile.parentFile == File(external, ".archive-imports").canonicalFile)
            check(folder.mkdirs())
            File(folder, "fixture.volume").writeText("synthetic residue")
        }

        fun batch(id: String): Pair<String, Long> = db.openHelper.readableDatabase
            .query("SELECT status, updatedAt FROM ImportBatch WHERE id = ?", arrayOf(id)).use {
                check(it.moveToFirst())
                it.getString(0) to it.getLong(1)
            }

        suspend fun close() {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            db.close()
            check(root.parentFile == base.cacheDir.canonicalFile)
            check(root.deleteRecursively())
        }
    }
}
