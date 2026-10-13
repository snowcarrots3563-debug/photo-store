package dev.local.artistgallery.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.artistgallery.model.SaveResult
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "phase1-test-${UUID.randomUUID()}.db"
    private lateinit var database: GalleryDatabase
    private lateinit var repository: RoomGalleryRepository

    @Before fun open() {
        database = Room.databaseBuilder(context, GalleryDatabase::class.java, databaseName).build()
        repository = RoomGalleryRepository(database)
    }

    @After fun close() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    private fun savedId(result: SaveResult): String {
        assertTrue("应返回事务已提交结果，实际为 $result", result is SaveResult.Saved)
        return (result as SaveResult.Saved).id
    }

    @Test fun namesIdentityRelationshipsAndReopen() = runBlocking {
        assertEquals(SaveResult.InvalidName, repository.createArtist(" \n\u3000"))
        val first = savedId(repository.createArtist("  同名画师  "))
        val second = savedId(repository.createArtist("同名画师"))
        assertNotEquals(first, second)
        assertEquals(first, UUID.fromString(first).toString())
        val collection = savedId(repository.createCollection(first, "  山间来信  "))
        assertEquals(SaveResult.DuplicateCollectionName, repository.createCollection(first, "山间来信"))
        savedId(repository.createCollection(second, "山间来信"))
        assertEquals(SaveResult.Saved(collection), repository.renameCollection(collection, "山间来信"))
        val original = database.galleryDao().getCollection(collection)!!
        assertEquals(SaveResult.Saved(collection), repository.renameCollection(collection, " <>&/\\ 中文  A "))
        val renamed = database.galleryDao().getCollection(collection)!!
        assertEquals(original.id, renamed.id)
        assertEquals(original.createdAt, renamed.createdAt)
        assertEquals(original.artistId, renamed.artistId)
        assertEquals("<>&/\\ 中文  A", renamed.name)
        val before = repository.observeArtists().first()
        assertEquals(2, before.size)
        assertTrue(before.all { it.collectionCount == 1 && it.imageCount == 0 })
        assertEquals(0, repository.observeCollection(collection).first()!!.videoCount)
        database.close()
        open()
        assertEquals(before, repository.observeArtists().first())
        assertEquals(renamed, database.galleryDao().getCollection(collection))
    }

    @Test fun temporaryNamesAreScopedAndBlankRenameUsesNewName() = runBlocking {
        val first = savedId(repository.createArtist("甲"))
        val second = savedId(repository.createArtist("乙"))
        val one = savedId(repository.createCollection(first, ""))
        savedId(repository.createCollection(first, "未命名作品集 3"))
        val two = savedId(repository.createCollection(first, "  "))
        assertEquals("未命名作品集 2", repository.observeCollection(two).first()!!.name)
        val other = savedId(repository.createCollection(second, ""))
        assertEquals("未命名作品集 1", repository.observeCollection(other).first()!!.name)
        assertEquals(SaveResult.Saved(one), repository.renameCollection(one, ""))
        assertEquals("未命名作品集 4", repository.observeCollection(one).first()!!.name)
        assertEquals(SaveResult.DuplicateCollectionName, repository.renameCollection(one, "未命名作品集 2"))
        assertEquals("未命名作品集 4", repository.observeCollection(one).first()!!.name)
        assertTrue(repository.observeCollection(one).first()!!.isTemporary)
        assertEquals(SaveResult.Saved(one), repository.renameCollection(one, "正式名"))
        assertFalse(repository.observeCollection(one).first()!!.isTemporary)
    }

    @Test fun concurrentCreatesAreAtomicAcrossRepositoryInstances() = runBlocking {
        val artist = savedId(repository.createArtist("并发"))
        val otherRepository = RoomGalleryRepository(database)
        val results = (1..12).map { index -> async(Dispatchers.IO) {
            (if (index % 2 == 0) repository else otherRepository).createCollection(artist, "")
        } }.awaitAll()
        assertEquals(12, results.map { savedId(it) }.toSet().size)
        val names = repository.observeArtist(artist).first()!!.collections.map { it.name }.toSet()
        assertEquals((1..12).map { "未命名作品集 $it" }.toSet(), names)
        val duplicates = (1..8).map { async(Dispatchers.IO) {
            repository.createCollection(artist, "同一名称")
        } }.awaitAll()
        assertEquals(1, duplicates.count { it is SaveResult.Saved })
        assertEquals(7, duplicates.count { it == SaveResult.DuplicateCollectionName })
        assertEquals(13, repository.observeArtist(artist).first()!!.artist.collectionCount)
    }

    @Test fun missingParentsAndMissingCollectionsDoNotWrite() = runBlocking {
        assertEquals(SaveResult.NotFound, repository.createCollection("missing", "作品"))
        assertEquals(SaveResult.NotFound, repository.renameCollection("missing", "作品"))
        assertNull(repository.observeArtist("missing").first())
        assertNull(repository.observeCollection("missing").first())
        assertTrue(repository.observeArtists().first().isEmpty())
        try {
            database.galleryDao().insertCollection(CollectionEntity("orphan", "missing", "作品", false, 0, 0))
            fail("外键必须拒绝没有画师归属的作品集")
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            assertNull(database.galleryDao().getCollection("orphan"))
        }
    }

    @Test fun cancellationBeforeSubmissionDoesNotWriteAndTransactionRollsBack() = runBlocking {
        val pending = async(start = CoroutineStart.LAZY) { repository.createArtist("取消") }
        pending.cancel()
        try {
            pending.await()
            fail("取消异常必须继续传播")
        } catch (_: CancellationException) {
            assertTrue(repository.observeArtists().first().isEmpty())
        }
        try {
            database.withTransaction {
                database.galleryDao().insertArtist(ArtistEntity("rolled-back", "未提交", 0, 0))
                throw CancellationException("取消事务")
            }
        } catch (_: CancellationException) {
            assertNull(database.galleryDao().getArtist("rolled-back"))
        }
    }

    @Test fun writeFailureReturnsFailureWithoutFalseSuccess() = runBlocking {
        savedId(repository.createArtist("保留记录"))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_test_artist BEFORE INSERT ON Artist " +
                "WHEN NEW.name = '不可保存' BEGIN SELECT RAISE(ABORT, 'test failure'); END"
        )
        assertEquals(SaveResult.StorageFailure, repository.createArtist("不可保存"))
        assertEquals(listOf("保留记录"), repository.observeArtists().first().map { it.name })
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_test_artist")
        savedId(repository.createArtist("恢复保存"))
        assertEquals(listOf("保留记录", "恢复保存"), repository.observeArtists().first().map { it.name })
    }

    @Test fun listsOrderByCreationTimeThenStableIdAndRestrictParentDeletion() = runBlocking {
        val dao = database.galleryDao()
        database.withTransaction {
            dao.insertArtist(ArtistEntity("b", "同时间乙", 1, 1))
            dao.insertArtist(ArtistEntity("a", "同时间甲", 1, 1))
            dao.insertArtist(ArtistEntity("c", "较早", 0, 0))
            dao.insertCollection(CollectionEntity("z", "a", "较晚", false, 2, 2))
            dao.insertCollection(CollectionEntity("y", "a", "同时间乙", false, 1, 1))
            dao.insertCollection(CollectionEntity("x", "a", "同时间甲", false, 1, 1))
        }
        assertEquals(listOf("c", "a", "b"), repository.observeArtists().first().map { it.id })
        assertEquals(listOf("x", "y", "z"), repository.observeArtist("a").first()!!.collections.map { it.id })
        try {
            database.withTransaction {
                database.openHelper.writableDatabase.execSQL("DELETE FROM Artist WHERE id = 'a'")
            }
            fail("外键必须阻止删除仍有作品集的画师")
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            assertEquals(3, repository.observeArtist("a").first()!!.artist.collectionCount)
        }
    }
}
