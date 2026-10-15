package dev.local.artistgallery.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.artistgallery.data.*
import dev.local.artistgallery.data.importing.MediaFileStore
import dev.local.artistgallery.data.importing.MediaImportCoordinator
import dev.local.artistgallery.model.SaveResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated Room and generated PNG only. Source compilation does not constitute device acceptance. */
@RunWith(AndroidJUnit4::class)
class GalleryDefaultImportFlowTest {
    private lateinit var database: GalleryDatabase
    private lateinit var repository: RoomGalleryRepository
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var uri: Uri
    private val store = ViewModelStore()
    private var ensures = 0
    private var completions = 0
    private val artistId = "neutral-old-artist"

    @Before fun open() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        root = File(base.cacheDir, "default-import-flow-${UUID.randomUUID()}").apply { check(mkdir()) }
        context = object : ContextWrapper(base) { override fun getExternalFilesDir(type: String?) = root }
        database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        repository = RoomGalleryRepository(database)
        database.galleryDao().insertArtist(ArtistEntity(artistId, "旧画师样本", 1, 1))
        val source = File(root, "neutral-source.png")
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            val random = java.util.Random(0)
            val pixels = IntArray(128 * 128) { 0xff000000.toInt() or random.nextInt(0x1000000) }
            bitmap.setPixels(pixels, 0, 128, 0, 0, 128, 128)
            source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        check(source.length() >= 5120)
        uri = Uri.fromFile(source)
    }

    @After fun close() = runBlocking {
        withContext(Dispatchers.Main) { store.clear() }
        database.close()
        assertEquals(context.cacheDir.canonicalFile, root.canonicalFile.parentFile)
        assertTrue(root.name.startsWith("default-import-flow-"))
        assertTrue(root.deleteRecursively())
    }

    private fun countingRepository(): GalleryRepository = object : GalleryRepository by repository {
        override suspend fun ensureDefaultCollection(artistId: String): SaveResult {
            ensures++
            return repository.ensureDefaultCollection(artistId)
        }
    }
    private suspend fun model(handle: SavedStateHandle = SavedStateHandle(), gallery: GalleryRepository = countingRepository(),
                              media: MediaLibraryRepository = repository) = withContext(Dispatchers.Main) {
        GalleryViewModel(gallery, handle, media, MediaImportCoordinator(context, media, MediaFileStore(context)),
            onImportCompleted = { completions++ }).also { store.put("default-flow", it) }
    }
    private suspend fun artistTarget(vm: GalleryViewModel, collectionId: String? = null, unassigned: Boolean = false, fromFiles: Boolean = false) {
        withContext(Dispatchers.Main) { vm.onEvent(GalleryEvent.OpenArtist(artistId)) }
        withTimeout(5_000) { vm.state.first { it.artistDetail?.artist?.id == artistId } }
        withContext(Dispatchers.Main) { vm.onEvent(GalleryEvent.OpenImportTarget(artistId, collectionId, unassigned, fromFiles)) }
        withTimeout(5_000) { vm.state.first { it.dialog is GalleryDialog.ImportTarget } }
    }
    private suspend fun pick(vm: GalleryViewModel) {
        withContext(Dispatchers.Main) {
            vm.onEvent(GalleryEvent.ConfirmImportTarget)
            vm.onMediaPicked(listOf(uri))
        }
        withTimeout(5_000) { vm.state.first { it.dialog == GalleryDialog.ImportPreview } }
        assertNull(vm.state.value.importCandidates.single().error)
    }
    private suspend fun finish(vm: GalleryViewModel) {
        withContext(Dispatchers.Main) { vm.onEvent(GalleryEvent.ConfirmImport) }
        withTimeout(5_000) { vm.state.first { it.dialog is GalleryDialog.ImportResult || it.fieldError != null } }
    }
    private suspend fun collections() = repository.observeArtist(artistId).first()!!.collections
    private suspend fun ordinary() = (repository.createCollection(artistId, "普通图集样本") as SaveResult.Saved).id

    @Test fun targetCancelAndEmptyPickerDoNotCreateDefault() = runBlocking {
        val vm = model()
        artistTarget(vm, fromFiles = true)
        assertEquals(GalleryDialog.ImportTarget(artistId, null, fromFiles = true, allowDefault = true), vm.state.value.dialog)
        withContext(Dispatchers.Main) { vm.onEvent(GalleryEvent.DismissDialog) }
        assertTrue(collections().isEmpty())
        artistTarget(vm)
        withContext(Dispatchers.Main) {
            vm.onEvent(GalleryEvent.ConfirmImportTarget)
            vm.onMediaPicked(emptyList())
        }
        assertNull(vm.state.value.pendingImportDefaultArtistId)
        assertNull(vm.state.value.pendingImportTargetCollectionId)
        assertTrue(collections().isEmpty())
        assertEquals(0, ensures)
        assertTrue(repository.latestImportBatches().isEmpty())
    }

    @Test fun previewDismissalDoesNotCreateDefaultOrKeepItsTarget() = runBlocking {
        val vm = model()
        artistTarget(vm)
        pick(vm)
        assertTrue(collections().isEmpty())
        withContext(Dispatchers.Main) { vm.onEvent(GalleryEvent.DismissDialog) }
        assertNull(vm.state.value.pendingImportDefaultArtistId)
        assertTrue(vm.state.value.importCandidates.isEmpty())
        assertEquals(0, ensures)
        assertTrue(repository.latestImportBatches().isEmpty())
    }

    @Test fun onlyFinalConfirmationCreatesDefaultAndPersistsItsActualId() = runBlocking {
        val vm = model()
        artistTarget(vm)
        pick(vm)
        assertEquals(0, ensures)
        assertTrue(collections().isEmpty())
        finish(vm)
        val target = collections().single()
        assertTrue(target.isDefault)
        assertEquals(1, ensures)
        val result = vm.state.value.importResult!!
        val item = repository.importItems(result.id).single()
        assertEquals(target.id, item.targetCollectionId)
        assertEquals("COMPLETE", item.status)
        assertEquals(1, repository.observeCollectionMedia(target.id).first().size)
        assertNull(vm.state.value.pendingImportDefaultArtistId)
        assertNull(vm.state.value.pendingImportTargetCollectionId)
        assertEquals(1, completions)
    }

    @Test fun renamedDefaultIsReusedByStableIdentity() = runBlocking {
        val target = (repository.ensureDefaultCollection(artistId) as SaveResult.Saved).id
        repository.renameCollection(target, "改名后的默认样本")
        val vm = model()
        artistTarget(vm)
        pick(vm)
        finish(vm)
        assertEquals(target, repository.importItems(vm.state.value.importResult!!.id).single().targetCollectionId)
        assertEquals("改名后的默认样本", collections().single().name)
        assertEquals(1, ensures)
    }

    @Test fun managementPreviewRestoresTheRenamedDefaultCaptionAndStableTarget() = runBlocking {
        val target = (repository.ensureDefaultCollection(artistId) as SaveResult.Saved).id
        repository.renameCollection(target, "恢复后可见的默认样本")
        val handle = SavedStateHandle()
        val vm = model(handle)
        withContext(Dispatchers.Main) {
            vm.onEvent(GalleryEvent.OpenManage)
            vm.onEvent(GalleryEvent.OpenImportTarget(artistId, fromFiles = true))
        }
        withTimeout(5_000) { vm.state.first { it.importTargetCollections.any { group -> group.id == target } } }
        pick(vm)
        withContext(Dispatchers.Main) { store.clear() }
        val restored = model(handle)
        val preview = withTimeout(5_000) { restored.state.first { it.importTargetCollections.any { group -> group.id == target } } }
        assertEquals(GalleryRoute.Manage, preview.route)
        assertNull(preview.artistDetail)
        assertEquals(GalleryDialog.ImportPreview, preview.dialog)
        assertEquals("恢复后可见的默认样本", preview.importTargetCollections.single().name)
        assertEquals(artistId, preview.pendingImportDefaultArtistId)
        finish(restored)
        assertEquals(target, repository.importItems(restored.state.value.importResult!!.id).single().targetCollectionId)
    }

    @Test fun explicitOrdinaryCollectionKeepsIdAndDoesNotCreateDefault() = runBlocking {
        val target = ordinary()
        val vm = model()
        artistTarget(vm, target)
        pick(vm)
        finish(vm)
        assertEquals(target, repository.importItems(vm.state.value.importResult!!.id).single().targetCollectionId)
        assertTrue(collections().none { it.isDefault })
        assertEquals(0, ensures)
    }

    @Test fun explicitUnassignedOverridesArtistDefault() = runBlocking {
        val vm = model()
        artistTarget(vm, unassigned = true)
        pick(vm)
        finish(vm)
        assertNull(repository.importItems(vm.state.value.importResult!!.id).single().targetCollectionId)
        assertEquals(1, repository.observeUnassignedMedia().first().size)
        assertTrue(collections().isEmpty())
        assertEquals(0, ensures)
    }

    @Test fun lostExplicitCollectionRetainsImportFailureWithoutChoosingDefault() = runBlocking {
        val existing = (repository.createCollection(artistId, "已有媒体图集样本") as SaveResult.Saved).id
        MediaImportCoordinator(context, repository, MediaFileStore(context)).importUris(listOf(uri), existing) { }
        val target = ordinary()
        val vm = model()
        artistTarget(vm, target)
        pick(vm)
        assertTrue(repository.deleteCollectionIfEmpty(target))
        finish(vm)
        val item = repository.importItems(vm.state.value.importResult!!.id).single()
        assertEquals(target, item.targetCollectionId)
        assertTrue(item.errorMessage!!.contains("目标作品集不存在"))
        assertEquals("FAILED", item.status)
        assertEquals(listOf(existing), collections().map { it.id })
        assertTrue(repository.observeUnassignedMedia().first().isEmpty())
        assertEquals(0, ensures)
    }

    @Test fun sourceDefaultAndPreviewSurvivePickerAndViewModelRecreation() = runBlocking {
        val handle = SavedStateHandle()
        val vm = model(handle)
        artistTarget(vm, fromFiles = true)
        val targetRestored = model(handle)
        assertEquals(GalleryDialog.ImportTarget(artistId, null, fromFiles = true, allowDefault = true), targetRestored.state.value.dialog)
        withContext(Dispatchers.Main) { targetRestored.onEvent(GalleryEvent.ConfirmImportTarget) }
        val pickerRestored = model(handle)
        assertEquals(artistId, pickerRestored.state.value.pendingImportDefaultArtistId)
        withContext(Dispatchers.Main) { pickerRestored.onMediaPicked(listOf(uri)) }
        withTimeout(5_000) { pickerRestored.state.first { it.dialog == GalleryDialog.ImportPreview } }
        val oldKey = withContext(Dispatchers.Main) { checkNotNull(pickerRestored.importPermissionActionKey(GalleryEvent.ConfirmImport)) }
        val previewRestored = model(handle)
        assertEquals(pickerRestored.state.value.importCandidates, previewRestored.state.value.importCandidates)
        assertEquals(GalleryDialog.ImportPreview, previewRestored.state.value.dialog)
        assertEquals(artistId, previewRestored.state.value.pendingImportDefaultArtistId)
        withContext(Dispatchers.Main) {
            assertNotEquals(oldKey, previewRestored.importPermissionActionKey(GalleryEvent.ConfirmImport))
            previewRestored.continueImportAfterPermission(GalleryEvent.ConfirmImport, oldKey)
        }
        assertEquals(GalleryDialog.ImportPreview, previewRestored.state.value.dialog)
        assertEquals(0, ensures)
        finish(previewRestored)
        assertTrue(collections().single().isDefault)
        assertEquals(1, ensures)
    }

    @Test fun missingArtistBeforeFinalConfirmationReportsFailureWithoutFallback() = runBlocking {
        val vm = model()
        artistTarget(vm)
        pick(vm)
        assertEquals(1, database.galleryDao().deleteArtist(artistId))
        finish(vm)
        assertEquals("画师不存在，请重新选择导入位置。", vm.state.value.fieldError)
        assertNull(vm.state.value.importResult)
        assertNull(vm.state.value.pendingImportDefaultArtistId)
        assertEquals(0, completions)
        assertTrue(repository.latestImportBatches().isEmpty())
    }

    @Test fun defaultStorageFailureDoesNotImportToUnassignedOrReportSuccess() = runBlocking {
        val failing = object : GalleryRepository by repository {
            override suspend fun ensureDefaultCollection(artistId: String): SaveResult = SaveResult.StorageFailure
        }
        val vm = model(gallery = failing)
        artistTarget(vm)
        pick(vm)
        val feedback = async(start = CoroutineStart.UNDISPATCHED) { vm.feedback.first() }
        finish(vm)
        assertEquals("无法准备默认图集，请稍后重试。", vm.state.value.fieldError)
        assertEquals(GalleryFeedback.Saved("无法准备默认图集，请稍后重试。"), withTimeout(5_000) { feedback.await() })
        assertNull(vm.state.value.importResult)
        assertNull(vm.state.value.pendingImportDefaultArtistId)
        assertEquals(0, completions)
        assertTrue(collections().isEmpty())
        assertTrue(repository.latestImportBatches().isEmpty())
    }

    @Test fun hidingActiveImportProgressKeepsDefaultTargetUntilBackgroundCompletion() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val gallery = object : GalleryRepository by repository {
            override suspend fun ensureDefaultCollection(artistId: String): SaveResult {
                entered.complete(Unit)
                gate.await()
                return repository.ensureDefaultCollection(artistId)
            }
        }
        val vm = model(gallery = gallery)
        artistTarget(vm)
        pick(vm)
        withContext(Dispatchers.Main) { vm.onEvent(GalleryEvent.ConfirmImport) }
        withTimeout(5_000) { entered.await() }
        withContext(Dispatchers.Main) { vm.onEvent(GalleryEvent.DismissDialog) }
        assertEquals(artistId, vm.state.value.pendingImportDefaultArtistId)
        gate.complete(Unit)
        withTimeout(5_000) { vm.state.first { it.importResult != null } }
        assertEquals(GalleryDialog.None, vm.state.value.dialog)
        assertEquals(collections().single().id, repository.importItems(vm.state.value.importResult!!.id).single().targetCollectionId)
        assertNull(vm.state.value.pendingImportDefaultArtistId)
    }

    @Test fun newTargetDiscardsPreviousPreviewAndItsPendingDefault() = runBlocking {
        val vm = model()
        artistTarget(vm)
        pick(vm)
        val target = ordinary()
        withContext(Dispatchers.Main) { vm.onEvent(GalleryEvent.OpenImportTarget(artistId, target, fromFiles = true)) }
        assertTrue(vm.state.value.importCandidates.isEmpty())
        assertNull(vm.state.value.pendingImportDefaultArtistId)
        assertNull(vm.state.value.pendingImportTargetCollectionId)
        assertEquals(GalleryDialog.ImportTarget(artistId, target, fromFiles = true, allowDefault = true), vm.state.value.dialog)
        assertNull(withContext(Dispatchers.Main) { vm.importPermissionActionKey(GalleryEvent.ConfirmImport) })
        assertEquals(0, ensures)
    }
}
