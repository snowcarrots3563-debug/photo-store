package dev.local.artistgallery.data.archive

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.artistgallery.data.GalleryDatabase
import dev.local.artistgallery.data.RoomGalleryRepository
import dev.local.artistgallery.data.importing.ImportWorkController
import dev.local.artistgallery.data.importing.MediaFileStore
import dev.local.artistgallery.data.importing.MediaImportCoordinator
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Fake read-grant callbacks and isolated contexts only; no system grants or real FGS launch. */
@RunWith(AndroidJUnit4::class)
class ArchiveSourceReadLifetimeTest {
    @Test fun foregroundStartFailureReleasesReadAcquiredBeforeScheduling() = runBlocking {
        fixture { f ->
            val source = f.acquireRead()
            assertTrue(f.readHeld)
            f.denyStart = true
            f.runWithRead(source) { fail("Source IO must not begin") }
            assertFalse(f.readHeld); assertEquals(1, f.releases)
            assertEquals(ArchiveStep.Error, f.archive.state.value.step)
        }
    }

    @Test fun cancellationBeforeLazyBodyStartsReleasesNewReadExactlyOnce() = runBlocking {
        fixture { f ->
            val generation = f.work.serviceCreated()
            f.runWithRead(f.acquireRead()) { fail("Source IO must not begin") }
            f.work.serviceDestroyed(generation)
            f.dispatcher.scheduler.runCurrent()
            assertFalse(f.readHeld); assertEquals(1, f.releases)
            assertTrue(f.work.activeSnapshot().isEmpty())
        }
    }

    @Test fun successfulCopyCanReleaseEarlyAndFailureCleanupIsIdempotent() = runBlocking {
        fixture { f ->
            f.archive.recover()
            val read = f.acquireRead()
            f.runWithRead(read) {
                assertTrue(f.readHeld)
                read.close()
                assertFalse(f.readHeld)
                throw IOException("synthetic post-copy preparation failure")
            }
            f.work.serviceReady(f.work.serviceCreated()); f.dispatcher.scheduler.runCurrent()
            assertFalse(f.readHeld); assertEquals(1, f.releases)
            assertEquals(ArchiveStep.Error, f.archive.state.value.step)
        }
    }

    @Test fun systemCancellationDuringSourceWorkReleasesRead() = runBlocking {
        fixture { f ->
            f.archive.recover()
            var entered = false
            f.runWithRead(f.acquireRead()) { entered = true; assertTrue(f.readHeld); awaitCancellation() }
            val generation = f.work.serviceCreated(); f.work.serviceReady(generation); f.dispatcher.scheduler.runCurrent()
            assertTrue(entered); assertTrue(f.readHeld)
            f.work.serviceDestroyed(generation); f.dispatcher.scheduler.runCurrent()
            assertFalse(f.readHeld); assertEquals(1, f.releases)
        }
    }

    @Test fun schedulingRejectionReleasesAcquiredRead() = runBlocking {
        val rejecting = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { throw RejectedExecutionException("synthetic scheduling rejection") }
        }
        fixture(rejecting) { f ->
            f.runWithRead(f.acquireRead()) { fail("Source IO must not begin") }
            assertFalse(f.readHeld); assertEquals(1, f.releases)
            assertTrue(f.work.activeSnapshot().isEmpty())
        }
    }

    private suspend fun fixture(dispatcher: CoroutineDispatcher? = null, block: suspend (Fixture) -> Unit) {
        val f = Fixture(dispatcher)
        try { block(f) } finally { f.close() }
    }
    private class Fixture(overrideDispatcher: CoroutineDispatcher?) {
        private val base: Context = ApplicationProvider.getApplicationContext()
        private val root = File(base.cacheDir, "archive-source-read-${UUID.randomUUID()}").also { check(it.mkdir()) }
        private val context = object : ContextWrapper(base) { override fun getExternalFilesDir(type: String?) = root }
        private val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        private val repository = RoomGalleryRepository(database)
        private val files = MediaFileStore(context)
        val dispatcher = StandardTestDispatcher()
        private val scope = CoroutineScope(SupervisorJob() + (overrideDispatcher ?: dispatcher) + CoroutineExceptionHandler { _, _ -> })
        var denyStart = false
        var readHeld = false
        var releases = 0
        val work = ImportWorkController({ if (denyStart) throw SecurityException("synthetic foreground start denial") })
        val archive = ArchiveImportCoordinator(context, database, repository, MediaImportCoordinator(context, repository, files), files, scope,
            foregroundController = work)
        fun acquireRead(): AutoCloseable = SourceReadPermissions({ emptySet() }, { readHeld = true }, { readHeld = false; releases++ })
            .acquire(listOf("content://synthetic-source/document/fixture.zip"))
        fun runWithRead(read: AutoCloseable, body: suspend () -> Unit) {
            val method = ArchiveImportCoordinator::class.java.getDeclaredMethod("run", Boolean::class.javaPrimitiveType,
                AutoCloseable::class.java, kotlin.jvm.functions.Function1::class.java).apply { isAccessible = true }
            try { method.invoke(archive, true, read, body) }
            catch (error: InvocationTargetException) { throw requireNotNull(error.cause) }
        }
        suspend fun close() {
            scope.cancel(); dispatcher.scheduler.runCurrent(); scope.coroutineContext[Job]?.join(); database.close()
            assertEquals(base.cacheDir.canonicalFile, root.canonicalFile.parentFile)
            assertTrue(root.name.startsWith("archive-source-read-")); root.deleteRecursively()
        }
    }
}
