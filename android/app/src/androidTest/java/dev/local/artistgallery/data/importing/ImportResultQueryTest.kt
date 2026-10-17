package dev.local.artistgallery.data.importing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.artistgallery.data.GalleryDatabase
import dev.local.artistgallery.data.ImportBatchEntity
import dev.local.artistgallery.data.ImportItemEntity
import dev.local.artistgallery.data.RoomGalleryRepository
import dev.local.artistgallery.data.archive.ArchiveTaskEntity
import dev.local.artistgallery.model.ImportBatchHistorySummary
import dev.local.artistgallery.model.ImportBatchSummary
import dev.local.artistgallery.model.ImportItemStatus
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory metadata only; no ordinary gallery or media files. */
@RunWith(AndroidJUnit4::class)
class ImportResultQueryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun liveHistoryDoesNotSelectItemsFromLargeBatches() = runBlocking {
        val queries = Collections.synchronizedList(mutableListOf<String>())
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java)
            .setQueryCallback({ sql, _ -> queries.add(sql) }, Executor { it.run() }).build()
        val emissions = Channel<List<ImportBatchHistorySummary>>(Channel.UNLIMITED)
        try {
            val repository = RoomGalleryRepository(database)
            lateinit var changedItem: ImportItemEntity
            val batches = (0 until 12).map { index ->
                val id = "batch-$index"
                val items = (0 until 1001).map { item(id) }
                changedItem = items.first()
                repository.insertImport(ImportBatchEntity(id, null, "COMPLETED", index.toLong(), index.toLong()),
                    items)
                id
            }
            queries.clear()
            val history = launch { repository.observeLatestImportBatches().collect { emissions.send(it) } }
            try {
                val initial = withTimeout(5000) { emissions.receive() }
                assertEquals(batches.takeLast(10).reversed(), initial.map { it.id })
                assertTrue(initial.all { it.status == "COMPLETED" })
                repository.updateImportItem(changedItem.copy(status = "FAILED"))
                repository.updateImportBatch(batches.last(), "FAILED")
                assertEquals("FAILED", withTimeout(5000) { emissions.receive() }.first().status)
                database.galleryDao().insertArchiveTask(ArchiveTaskEntity(batches.last(), UUID.randomUUID().toString(), "ZIP", "test.zip", "[]", 20))
                assertFalse(withTimeout(5000) { emissions.receive() }.any { it.id == batches.last() })
                val itemSelects = synchronized(queries) {
                    queries.filter { Regex("\\bFROM\\s+[`\"]?ImportItem\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                }
                assertTrue("History must not query ImportItem: $itemSelects", itemSelects.isEmpty())
            } finally { history.cancel(); history.join() }
        } finally { emissions.close(); database.close() }
    }

    @Test fun oldBatchDetailObservationIncludesItemsAndTracksTheirChanges() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val emissions = Channel<ImportBatchSummary?>(Channel.UNLIMITED)
        try {
            val repository = RoomGalleryRepository(database)
            val oldItem = item("old").copy(status = "STAGED", sha256 = "synthetic", byteSize = 10)
            repository.insertImport(ImportBatchEntity("old", null, "INTERRUPTED", 0, 0), listOf(oldItem))
            repeat(11) { index -> repository.insertImport(ImportBatchEntity("recent-$index", null, "COMPLETED", index + 1L, index + 1L), emptyList()) }
            assertFalse(repository.observeLatestImportBatches().first().any { it.id == "old" })
            val detail = launch { repository.observeImportBatchResult("old").collect { emissions.send(it) } }
            try {
                val initial = withTimeout(5000) { emissions.receive() }
                assertEquals("old", initial?.id)
                assertEquals(ImportItemStatus.STAGED, initial?.items?.single()?.status)
                assertTrue(initial?.items?.single()?.canRetry == true)
                repository.updateImportItem(oldItem.copy(status = "COMPLETE"))
                assertEquals(ImportItemStatus.COMPLETE, withTimeout(5000) { emissions.receive() }?.items?.single()?.status)
                assertEquals("original.png", repository.getImportBatchResult("old")?.items?.single()?.sourceName)
            } finally { detail.cancel(); detail.join() }
        } finally { emissions.close(); database.close() }
    }

    @Test fun directlyReadsAnOrdinaryBatchOlderThanTheLatestTen() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val repository = RoomGalleryRepository(database)
            val batches = (0 until 12).map { index ->
                val id = UUID.randomUUID().toString()
                repository.insertImport(ImportBatchEntity(id, null, "COMPLETED", index.toLong(), index.toLong()), listOf(item(id)))
                id
            }
            assertFalse(repository.latestImportBatches().any { it.id == batches.first() })
            val result = repository.getImportBatchResult(batches.first())
            assertEquals(batches.first(), result?.id); assertEquals("original.png", result?.items?.single()?.sourceName)
            assertNull(repository.getImportBatchResult(UUID.randomUUID().toString()))
        } finally { database.close() }
    }

    @Test fun ordinaryResultEntryDoesNotReadAnArchiveBatch() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val repository = RoomGalleryRepository(database)
            val batch = UUID.randomUUID().toString()
            repository.insertImport(ImportBatchEntity(batch, null, "COMPLETED", 1, 1), listOf(item(batch)))
            database.galleryDao().insertArchiveTask(ArchiveTaskEntity(batch, UUID.randomUUID().toString(), "ZIP", "test.zip", "[]", 1))
            assertNull(repository.getImportBatchResult(batch))
            assertNull(repository.observeImportBatchResult(batch).first())
            assertTrue(repository.observeLatestImportBatches().first().isEmpty())
        } finally { database.close() }
    }

    private fun item(batch: String) = ImportItemEntity(UUID.randomUUID().toString(), batch, null, "content://fixture", false,
        "original.png", "IMAGE", UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, 1, "COMPLETE", null, 1, 1)
}
