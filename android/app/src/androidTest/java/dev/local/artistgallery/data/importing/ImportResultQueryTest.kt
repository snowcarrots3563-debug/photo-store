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
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory metadata only; no ordinary gallery or media files. */
@RunWith(AndroidJUnit4::class)
class ImportResultQueryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

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
        } finally { database.close() }
    }

    private fun item(batch: String) = ImportItemEntity(UUID.randomUUID().toString(), batch, null, "content://fixture", false,
        "original.png", "IMAGE", UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, 1, "COMPLETE", null, 1, 1)
}
