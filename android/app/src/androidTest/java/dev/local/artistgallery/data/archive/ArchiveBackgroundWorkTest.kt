package dev.local.artistgallery.data.archive

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.artistgallery.data.*
import dev.local.artistgallery.data.importing.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated synthetic sources and fake service callbacks; never launches a real FGS. */
@RunWith(AndroidJUnit4::class)
class ArchiveBackgroundWorkTest {
    @Test fun preparationWaitsForProtectionAndReleasesItAtPreview() = runBlocking {
        fixture { f ->
            f.archive.recover(); f.archive.open(f.artist)
            f.archive.pick(listOf(Uri.fromFile(f.source())), false)
            assertEquals(1, f.work.activeSnapshot().size)
            assertTrue(f.db.galleryDao().allCollections().isEmpty())
            f.ready(); f.await(ArchiveStep.Preview)
            assertTrue(f.work.activeSnapshot().isEmpty())
            assertTrue(f.db.galleryDao().allCollections().isEmpty())
            assertTrue(f.notices.isEmpty())
        }
    }

    @Test fun encryptedPreparationReleasesProtectionWhileWaitingForPassword() = runBlocking {
        fixture { f ->
            f.archive.recover(); f.archive.open(f.artist)
            f.archive.pick(listOf(Uri.fromFile(f.source(encrypted = true))), false)
            f.ready(); f.await(ArchiveStep.Password)
            assertTrue(f.work.activeSnapshot().isEmpty())
            f.archive.unlock("fixture-password".toCharArray())
            assertEquals(1, f.work.activeSnapshot().size)
            f.await(ArchiveStep.Preview)
            assertTrue(f.work.activeSnapshot().isEmpty())
        }
    }

    @Test fun deniedServiceStartDoesNotCopyOrCreateDefaultCollection() = runBlocking {
        fixture { f ->
            f.archive.recover(); f.archive.open(f.artist); f.denyStart = true
            f.archive.pick(listOf(Uri.fromFile(f.source())), false)
            assertEquals(ArchiveStep.Error, f.archive.state.value.step)
            assertTrue(f.archive.state.value.message.orEmpty().contains("无法启动后台导入"))
            assertNull(f.archive.state.value.taskId)
            assertTrue(f.db.galleryDao().allCollections().isEmpty())
            assertTrue(f.work.activeSnapshot().isEmpty())
        }
    }

    @Test fun recoveryBarrierPreventsSelectionCleanupRace() = runBlocking {
        fixture { f ->
            f.archive.open(f.artist); f.archive.pick(listOf(Uri.fromFile(f.source())), false); f.ready()
            yield()
            assertNull(f.archive.state.value.taskId)
            f.archive.recover(); f.await(ArchiveStep.Preview)
            assertTrue(File(f.archiveRoot, requireNotNull(f.archive.state.value.taskId)).isDirectory)
        }
    }

    @Test fun cancellationAcquiresCleanupLeaseBeforeReleasingOriginal() = runBlocking {
        fixture { f ->
            f.archive.recover(); f.archive.open(f.artist)
            f.archive.pick(listOf(Uri.fromFile(f.source())), false)
            f.archive.cancel()
            assertTrue(f.startSizes.contains(2))
            f.ready(); f.joinJobs()
            assertTrue(f.work.activeSnapshot().isEmpty())
            assertNull(f.archive.state.value.taskId)
            assertTrue(f.db.galleryDao().archiveTasks().isEmpty())
        }
    }

    @Test fun interruptionKeepsCheckpointAndPrivateSourceForSuccessfulRetry() = runBlocking {
        fixture { f ->
            f.archive.recover(); f.archive.open(f.artist)
            f.archive.pick(listOf(Uri.fromFile(f.source())), false); f.ready(); f.await(ArchiveStep.Preview)
            val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
            f.onCopy = { entered.complete(Unit); gate.await() }
            f.archive.confirm(); withTimeout(10_000) { entered.await() }
            f.work.serviceDestroyed(f.generation)
            f.await(ArchiveStep.Error)
            val id = requireNotNull(f.archive.state.value.taskId)
            assertEquals("INTERRUPTED", f.repo.latestImportBatches().single().status)
            assertTrue(f.repo.importItems(id).none { it.status == "CANCELLED" })
            assertTrue(File(File(f.archiveRoot, id), "fixture.zip").isFile)
            assertTrue(f.notices.isEmpty())
            f.onCopy = {}; gate.complete(Unit)
            f.archive.retry(); f.ready(); f.await(ArchiveStep.Result)
            assertEquals("COMPLETE", f.repo.importItems(id).single().status)
            assertEquals(1, f.notices.size)
            assertTrue(f.work.activeSnapshot().isEmpty())
            assertEquals(listOf(0L), f.beforeCommitCounts)
        }
    }

    @Test fun failedCleanupProtectionStillHonorsCancellationAndSaving() = runBlocking {
        fixture { f ->
            f.archive.recover(); f.archive.open(f.artist)
            f.archive.pick(listOf(Uri.fromFile(f.source())), false); f.ready(); f.await(ArchiveStep.Preview)
            f.denyStart = true; f.archive.cancel(); f.joinJobs()
            assertNull(f.archive.state.value.taskId)
            assertTrue(f.work.activeSnapshot().isEmpty())
            assertTrue(f.db.galleryDao().archiveTasks().isEmpty())
        }
    }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture()
        try { f.db.galleryDao().insertArtist(ArtistEntity(f.artist, "合成后台导入画师", 1, 1)); block(f) }
        finally { f.close() }
    }

    private class Fixture {
        private val base: Context = ApplicationProvider.getApplicationContext()
        val root = File(base.cacheDir, "archive-background-${UUID.randomUUID()}").also { check(it.mkdir()) }
        private val external = File(root, "external").also { check(it.mkdir()) }
        val archiveRoot = File(external, ".archive-imports")
        private val context = object : ContextWrapper(base) { override fun getExternalFilesDir(type: String?) = external }
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val repo = RoomGalleryRepository(db)
        private val files = MediaFileStore(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val artist = UUID.randomUUID().toString()
        var denyStart = false
        var generation = 0L
        var onCopy: suspend () -> Unit = {}
        val startSizes: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        val beforeCommitCounts: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        val notices: MutableList<ImportCompletion> = Collections.synchronizedList(mutableListOf())
        val work = ImportWorkController({ requestStart() })
        private fun requestStart() { if (denyStart) throw SecurityException("synthetic start denial"); startSizes.add(work.activeSnapshot().size) }
        private val importRepository = object : MediaLibraryRepository by repo {
            override suspend fun updateImportItem(item: ImportItemEntity) {
                repo.updateImportItem(item)
                if (item.status == "COPYING") onCopy()
            }
            override suspend fun commitArchiveImport(item: ImportItemEntity, asset: MediaAssetEntity, reused: Boolean) {
                beforeCommitCounts.add(work.activeSnapshot().single().completed)
                repo.commitArchiveImport(item, asset, reused)
            }
        }
        val archive = ArchiveImportCoordinator(context, db, repo, MediaImportCoordinator(context, importRepository, files), files, scope,
            foregroundController = work, onImportCompleted = { notices.add(it) })

        fun ready() { generation = work.serviceCreated(); work.serviceReady(generation) }
        fun source(encrypted: Boolean = false): File {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            val bytes = try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().copyOf(8000) }
            finally { bitmap.recycle() }
            return File(root, "fixture.zip").also { source ->
                val parameters = ZipParameters().apply {
                    fileNameInZip = "synthetic.png"
                    isEncryptFiles = encrypted
                    encryptionMethod = EncryptionMethod.AES
                }
                ZipFile(source, if (encrypted) "fixture-password".toCharArray() else null).use { zip -> zip.addStream(bytes.inputStream(), parameters) }
            }
        }
        suspend fun joinJobs() = withTimeout(10_000) { scope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
        suspend fun await(step: ArchiveStep) {
            val state = withTimeout(10_000) { archive.state.first { it.step == step || it.step == ArchiveStep.Error } }
            assertEquals(state.message, step, state.step); joinJobs()
        }
        suspend fun close() {
            scope.coroutineContext[Job]?.cancelAndJoin(); db.close()
            assertEquals(base.cacheDir.canonicalFile, root.canonicalFile.parentFile)
            assertTrue(root.name.startsWith("archive-background-")); root.deleteRecursively()
        }
    }
}
