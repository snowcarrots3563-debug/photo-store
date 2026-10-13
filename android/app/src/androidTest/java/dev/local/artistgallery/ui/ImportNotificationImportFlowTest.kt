package dev.local.artistgallery.ui

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.artistgallery.data.*
import dev.local.artistgallery.data.importing.ImportCompletion
import dev.local.artistgallery.data.importing.ImportResultKind
import dev.local.artistgallery.data.importing.MediaFileStore
import dev.local.artistgallery.data.importing.MediaImportCoordinator
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real VM/importer paths with unsupported synthetic URIs and in-memory Room; no user assets. */
@RunWith(AndroidJUnit4::class)
class ImportNotificationImportFlowTest {
    private lateinit var database: GalleryDatabase
    private lateinit var repository: RoomGalleryRepository
    private lateinit var context: Context
    private lateinit var root: File
    private val store = ViewModelStore()
    private val completions = mutableListOf<ImportCompletion>()
    private var serial = 0
    private val uri = Uri.parse("content://synthetic-notification/a.txt")

    @Before fun open() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        root = File(base.cacheDir, "notification-flow-${UUID.randomUUID()}").apply { check(mkdir()) }
        context = object : ContextWrapper(base) { override fun getExternalFilesDir(type: String?) = root }
        database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        repository = RoomGalleryRepository(database)
    }

    @After fun close() = runBlocking {
        withContext(Dispatchers.Main) { store.clear() }
        database.close()
        assertEquals(context.cacheDir.canonicalFile, root.canonicalFile.parentFile)
        assertTrue(root.name.startsWith("notification-flow-"))
        root.deleteRecursively()
    }

    private suspend fun model(media: MediaLibraryRepository = repository, onCompleted: (ImportCompletion) -> Unit = { completions.add(it) }): GalleryViewModel = withContext(Dispatchers.Main) {
        GalleryViewModel(repository, SavedStateHandle(), media,
            MediaImportCoordinator(context, media, MediaFileStore(context)), onImportCompleted = onCompleted).also { store.put("flow${serial++}", it) }
    }

    private suspend fun preview(vm: GalleryViewModel): String {
        withContext(Dispatchers.Main) { vm.onMediaPicked(listOf(uri)) }
        withTimeout(5_000) { vm.state.first { it.dialog == GalleryDialog.ImportPreview } }
        return withContext(Dispatchers.Main) { checkNotNull(vm.importPermissionActionKey(GalleryEvent.ConfirmImport)) }
    }

    private suspend fun confirm(vm: GalleryViewModel, key: String) = withContext(Dispatchers.Main) { vm.continueImportAfterPermission(GalleryEvent.ConfirmImport, key) }
    private suspend fun result(vm: GalleryViewModel) = withTimeout(5_000) { vm.state.first { it.dialog is GalleryDialog.ImportResult } }.importResult!!

    @Test fun aRealImportAttemptCompletesOnceAndReportsAllFailureHonestly() = runBlocking {
        val vm = model()
        val key = preview(vm)
        confirm(vm, key)
        confirm(vm, key)
        val batch = result(vm)
        assertEquals(1, completions.size)
        assertEquals(ImportResultKind.Media, completions.single().kind)
        assertEquals(batch.id, completions.single().batchId)
        assertEquals(0, completions.single().added)
        assertEquals(1, completions.single().unresolved)
        assertFalse(completions.single().cancelled)
        assertEquals(1, repository.latestImportBatches().size)
    }

    @Test fun aRealRetryReturnsOneCallbackForItsOwnBatch() = runBlocking {
        val vm = model()
        confirm(vm, preview(vm))
        val first = result(vm)
        withContext(Dispatchers.Main) { vm.onEvent(GalleryEvent.RetryImport(first.id)) }
        val retried = result(vm)
        assertEquals(first.id, retried.id)
        assertEquals(2, completions.size)
        assertTrue(completions.all { it.batchId == first.id })
        assertEquals(1, repository.latestImportBatches().size)
    }

    @Test fun notifierRuntimeFailureCannotTurnAStoredImportIntoFailure() = runBlocking {
        var callbacks = 0
        val vm = model(onCompleted = { callbacks++; throw SecurityException("synthetic notification failure") })
        confirm(vm, preview(vm))
        val finished = result(vm)
        assertEquals(1, callbacks)
        assertNotNull(repository.getImportBatchResult(finished.id))
        assertNull(vm.state.value.fieldError)
    }

    @Test fun failedBatchSetupDoesNotSendAFabricatedCompletion() = runBlocking {
        val media = object : MediaLibraryRepository by repository {
            override suspend fun insertImport(batch: ImportBatchEntity, items: List<ImportItemEntity>) { throw IOException("synthetic setup failure") }
        }
        val vm = model(media)
        confirm(vm, preview(vm))
        withTimeout(5_000) { vm.state.first { it.fieldError == "synthetic setup failure" } }
        assertTrue(completions.isEmpty())
        assertTrue(repository.latestImportBatches().isEmpty())
    }

    @Test fun cancellingTheViewModelDuringSetupDoesNotSendCompletion() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val media = object : MediaLibraryRepository by repository {
            override suspend fun insertImport(batch: ImportBatchEntity, items: List<ImportItemEntity>) {
                entered.complete(Unit); gate.await(); repository.insertImport(batch, items)
            }
        }
        val vm = model(media)
        confirm(vm, preview(vm))
        withTimeout(5_000) { entered.await() }
        withContext(Dispatchers.Main) { store.clear() }
        gate.complete(Unit)
        withContext(Dispatchers.Main) { yield() }
        assertTrue(completions.isEmpty())
        assertTrue(repository.latestImportBatches().isEmpty())
    }

    @Test fun oldNotificationIsDeferredUntilTheActiveImportEndsWithoutReplacingProgress() = runBlocking {
        val oldId = UUID.randomUUID().toString()
        repository.insertImport(ImportBatchEntity(oldId, null, "COMPLETED", 1, 1), emptyList())
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val media = object : MediaLibraryRepository by repository {
            override suspend fun insertImport(batch: ImportBatchEntity, items: List<ImportItemEntity>) {
                entered.complete(Unit); gate.await(); repository.insertImport(batch, items)
            }
        }
        val vm = model(media)
        confirm(vm, preview(vm))
        withTimeout(5_000) { entered.await() }
        withContext(Dispatchers.Main) { vm.onEvent(GalleryEvent.OpenImportResult(oldId)) }
        assertEquals(GalleryDialog.ImportProgress, vm.state.value.dialog)
        assertNull(vm.state.value.importResult)
        gate.complete(Unit)
        withTimeout(5_000) { vm.state.first { it.dialog == GalleryDialog.ImportResult(oldId) } }
        assertEquals(oldId, vm.state.value.importResult?.id)
        assertEquals(1, completions.size)
        assertNotEquals(oldId, completions.single().batchId)
    }

    @Test fun aNewPreviewCannotUseThePreviousPermissionActionIdentifier() = runBlocking {
        val vm = model()
        val stale = preview(vm)
        withContext(Dispatchers.Main) { vm.onEvent(GalleryEvent.DismissDialog) }
        val current = preview(vm)
        assertNotEquals(stale, current)
        confirm(vm, stale)
        assertEquals(GalleryDialog.ImportPreview, vm.state.value.dialog)
        assertTrue(repository.latestImportBatches().isEmpty())
        confirm(vm, current)
        result(vm)
        assertEquals(1, completions.size)
    }
}
