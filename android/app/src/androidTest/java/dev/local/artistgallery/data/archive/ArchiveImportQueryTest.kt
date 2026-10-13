package dev.local.artistgallery.data.archive

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.artistgallery.data.ArtistEntity
import dev.local.artistgallery.data.GalleryDatabase
import dev.local.artistgallery.data.ImportBatchEntity
import dev.local.artistgallery.data.ImportItemEntity
import dev.local.artistgallery.data.MediaAssetEntity
import dev.local.artistgallery.data.RoomGalleryRepository
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Database-only fixtures: no archive files, ordinary gallery database or device media. */
@RunWith(AndroidJUnit4::class)
class ArchiveImportQueryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun thousandCommitsReadOnlyCurrentItem() = runBlocking {
        withLibrary { db, repository, queries ->
            val group = createGroup(db)
            val items = (0 until 1000).map { item(group, it) }
            repository.insertImport(ImportBatchEntity(group.batchId, null, "COPYING", 1, 1), items)
            queries.clear()

            items.forEach { repository.commitArchiveImport(it, asset(it), false) }

            val itemReads = synchronized(queries) {
                queries.filter { (sql, _) ->
                    sql.trimStart().startsWith("SELECT", true) && sql.contains("FROM ImportItem", true)
                }
            }
            assertEquals("One item read per commit, without reading the full batch", 1000, itemReads.size)
            itemReads.forEachIndexed { index, (sql, arguments) ->
                assertTrue("Item reads must constrain the primary key: $sql", Regex("\\bid\\s*=\\s*\\?", RegexOption.IGNORE_CASE).containsMatchIn(sql))
                assertEquals(listOf(group.batchId, items[index].id), arguments)
            }
            assertEquals(1000, db.galleryDao().getCollectionAssets(group.id).size)
            assertEquals(1000, repository.importItems(group.batchId).count { it.status == "COMPLETE" })
            assertEquals(1, db.galleryDao().getCollections(group.artistId).size)
        }
    }

    @Test fun singleItemLookupIsBatchScopedAndMissingCommitWritesNothing() = runBlocking {
        withLibrary { db, repository, _ ->
            val group = createGroup(db)
            val item = item(group, 0)
            repository.insertImport(ImportBatchEntity(group.batchId, null, "COPYING", 1, 1), listOf(item))

            assertEquals(item, repository.getImportItem(group.batchId, item.id))
            assertNull(repository.getImportItem("another-batch", item.id))
            assertNull(repository.getImportItem(group.batchId, "missing-item"))
            for (missing in listOf(item.copy(batchId = "another-batch"), item.copy(id = "missing-item"))) {
                try {
                    repository.commitArchiveImport(missing, asset(missing), false)
                    fail("A missing or cross-batch item must fail before creating any collection or asset")
                } catch (error: IOException) {
                    assertEquals("导入条目不存在。", error.message)
                }
                assertNull(db.galleryDao().getCollection(group.id))
                assertNull(repository.getAsset(missing.id))
            }
            assertEquals("QUEUED", repository.getImportItem(group.batchId, item.id)?.status)
        }
    }

    @Test fun duplicateCommitRemainsIdempotent() = runBlocking {
        withLibrary { db, repository, _ ->
            val group = createGroup(db)
            val item = item(group, 0)
            repository.insertImport(ImportBatchEntity(group.batchId, null, "COPYING", 1, 1), listOf(item))
            val original = asset(item)
            repository.commitArchiveImport(item, original, false)
            val duplicate = original.copy(id = UUID.randomUUID().toString(), storageKey = UUID.randomUUID().toString())

            repository.commitArchiveImport(item, duplicate, false)

            assertEquals("COMPLETE", repository.getImportItem(group.batchId, item.id)?.status)
            assertEquals(1, repository.referenceCount(original.id))
            assertNull(repository.getAsset(duplicate.id))
            assertEquals(listOf(original), db.galleryDao().getCollectionAssets(group.id))
            assertEquals(1, db.galleryDao().getCollections(group.artistId).size)
        }
    }

    private suspend fun withLibrary(block: suspend (GalleryDatabase, RoomGalleryRepository, MutableList<Pair<String, List<Any?>>>) -> Unit) {
        val queries = Collections.synchronizedList(mutableListOf<Pair<String, List<Any?>>>())
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java)
            .setQueryCallback({ sql, arguments -> queries.add(sql to arguments.toList()) }, Executor { it.run() })
            .build()
        try { block(database, RoomGalleryRepository(database), queries) }
        finally { database.close() }
    }

    private suspend fun createGroup(database: GalleryDatabase): ArchiveGroupEntity {
        val artist = UUID.randomUUID().toString()
        database.galleryDao().insertArtist(ArtistEntity(artist, "查询回归画师", 1, 1))
        val group = ArchiveGroupEntity(UUID.randomUUID().toString(), UUID.randomUUID().toString(), artist, "查询回归作品集", "目录")
        database.galleryDao().insertArchiveGroups(listOf(group))
        return group
    }

    private fun item(group: ArchiveGroupEntity, position: Int) = ImportItemEntity(
        UUID.randomUUID().toString(), group.batchId, null, "archive://${group.batchId}/$position", false,
        "$position.png", "IMAGE", UUID.randomUUID().toString(), UUID.randomUUID().toString(), "hash-$position", 1,
        "QUEUED", null, position.toLong(), 1, group.id, "目录/$position.png",
    )

    private fun asset(item: ImportItemEntity) = MediaAssetEntity(
        item.id, item.sourceName, item.mediaType, requireNotNull(item.byteSize), null, null,
        requireNotNull(item.sha256), item.storageKey, "READY", item.createdAt,
    )
}
