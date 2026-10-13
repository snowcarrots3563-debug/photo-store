package dev.local.artistgallery.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.artistgallery.data.*
import dev.local.artistgallery.data.importing.MediaFileStore
import dev.local.artistgallery.data.importing.MediaImportCoordinator
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrganizationDeleteFlowTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var testRoot: File
    private lateinit var database: GalleryDatabase
    private lateinit var repository: RoomGalleryRepository
    private lateinit var files: MediaFileStore
    private lateinit var importer: MediaImportCoordinator
    private lateinit var model: GalleryViewModel
    private val store = ViewModelStore()

    @Before fun open() {
        testRoot = File(context.cacheDir, "organization-delete-flow-${UUID.randomUUID()}")
        assertTrue(testRoot.mkdirs())
        requireTestPath(testRoot)
        val isolated = object : ContextWrapper(context) {
            override fun getExternalFilesDir(type: String?): File = testRoot
        }
        database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        repository = RoomGalleryRepository(database)
        files = MediaFileStore(isolated)
        importer = MediaImportCoordinator(isolated, repository, files)
    }

    @After fun close() = runBlocking {
        withTimeout(5_000) {
            withContext(Dispatchers.Main) {
                store.clear()
                if (::model.isInitialized) model.viewModelScope.coroutineContext[Job]?.join()
            }
        }
        if (::database.isInitialized) database.close()
        if (::testRoot.isInitialized) {
            requireTestPath(testRoot)
            assertTrue(testRoot.deleteRecursively())
        }
    }

    private fun requireTestPath(file: File) {
        val root = testRoot.canonicalFile
        check(root.name.startsWith("organization-delete-flow-"))
        check(root.path.startsWith(context.cacheDir.canonicalPath + File.separator))
        val path = file.canonicalPath
        check(path == root.path || path.startsWith(root.path + File.separator))
    }

    private suspend fun artist(id: String, vararg collectionIds: String) {
        val dao = database.galleryDao()
        dao.insertArtist(ArtistEntity(id, "画师$id", 1, 1))
        collectionIds.forEach { dao.insertCollection(CollectionEntity(it, id, "作品$it", false, 1, 1)) }
    }

    private suspend fun asset(id: String, vararg collectionIds: String): MediaAssetEntity {
        val asset = MediaAssetEntity(id, "$id.png", "IMAGE", 4, null, null, id, UUID.randomUUID().toString(), "READY", 1)
        val dao = database.galleryDao()
        dao.insertAsset(asset)
        collectionIds.forEach { dao.insertCollectionMedia(CollectionMediaEntity(it, id, dao.nextManualOrder(it), "$id.png", null)) }
        withContext(Dispatchers.IO) {
            val file = files.originalFile(asset.storageKey)
            requireTestPath(file)
            assertTrue(file.parentFile!!.exists() || file.parentFile!!.mkdirs())
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        return asset
    }

    private suspend fun select(kind: EntitySelectionKind, vararg ids: String) {
        withContext(Dispatchers.Main) {
            model = GalleryViewModel(repository, SavedStateHandle(), repository, importer)
            store.put("organization", model)
            if (kind == EntitySelectionKind.Collections) {
                model.onEvent(GalleryEvent.OpenArtist("a"))
                model.onEvent(GalleryEvent.SwitchArtistTab(ArtistTab.Collections))
            }
        }
        withTimeout(5_000) { model.state.first { it.loadState == LoadState.Ready && it.mediaLoadState == LoadState.Ready } }
        withContext(Dispatchers.Main) {
            model.onEvent(GalleryEvent.StartEntitySelection(kind, ids.first()))
            ids.drop(1).forEach { model.onEvent(GalleryEvent.ToggleEntitySelection(it)) }
            model.onEvent(GalleryEvent.OpenDeleteSelectedEntities)
        }
    }

    private suspend fun confirm(): GalleryUiState {
        withContext(Dispatchers.Main) {
            model.onEvent(GalleryEvent.ConfirmDeleteSelectedEntities)
            assertTrue(model.state.value.isSaving)
            model.onEvent(GalleryEvent.ConfirmDeleteSelectedEntities)
            model.onEvent(GalleryEvent.DismissDialog)
            model.onEvent(GalleryEvent.OpenManage)
        }
        return withTimeout(5_000) { model.state.first { !it.isSaving } }
    }

    @Test fun cancelBatchArtistDeleteKeepsAllFilesReferencesAndArtists() = runBlocking {
        artist("a", "c"); artist("b", "d")
        val shared = asset("shared", "c", "d")
        select(EntitySelectionKind.Artists, "a", "b")
        assertEquals(GalleryDialog.DeleteArtists(listOf("a", "b")), model.state.value.dialog)
        withContext(Dispatchers.Main) {
            model.onEvent(GalleryEvent.NavigateBack)
            model.onEvent(GalleryEvent.ConfirmDeleteSelectedEntities)
        }
        assertEquals(GalleryDialog.None, model.state.value.dialog)
        assertEquals(setOf("a", "b"), model.state.value.selectedEntityIds)
        assertEquals(2, repository.referenceCount(shared.id))
        assertNotNull(database.galleryDao().getArtist("a"))
        assertNotNull(database.galleryDao().getArtist("b"))
        assertTrue(files.hasOriginal(shared.storageKey))
    }

    @Test fun batchArtistsDeleteSharedCopiesEverywhereAndLeaveUnselectedArtistAndSource() = runBlocking {
        artist("a", "c"); artist("b", "d"); artist("other", "e")
        val shared = asset("shared", "c", "d", "e")
        val owned = asset("owned", "d")
        val other = asset("other", "e")
        assertTrue(repository.setArtistCover("other", shared.id))
        val source = File(testRoot, "source-outside-gallery.png")
        requireTestPath(source)
        source.writeText("unchanged source")
        select(EntitySelectionKind.Artists, "a", "b")
        val state = confirm()
        val dao = database.galleryDao()
        assertEquals(GalleryRoute.Home, state.route)
        assertEquals(GalleryDialog.None, state.dialog)
        assertNull(state.entitySelectionKind)
        assertNull(dao.getArtist("a")); assertNull(dao.getArtist("b"))
        assertNull(repository.getAsset(shared.id)); assertNull(repository.getAsset(owned.id))
        assertFalse(files.hasOriginal(shared.storageKey)); assertFalse(files.hasOriginal(owned.storageKey))
        assertNotNull(dao.getCollection("e"))
        assertNull(dao.getArtist("other")!!.coverAssetId)
        assertEquals(listOf(other.id), repository.getArtistAssets("other").map { it.id })
        assertTrue(files.hasOriginal(other.storageKey))
        assertEquals("unchanged source", source.readText())
    }

    @Test fun collectionBatchUsesFrozenIdsAndRetainsOnlyFailedCollectionForRetry() = runBlocking {
        artist("a", "c", "d", "e"); artist("other", "foreign")
        val good = asset("good", "c", "foreign")
        val blocked = asset("blocked", "d")
        val untouched = asset("untouched", "e")
        assertTrue(repository.setArtistCover("other", good.id))
        val original = files.originalFile(blocked.storageKey)
        requireTestPath(original)
        assertTrue(original.delete()); assertTrue(original.mkdir())
        val child = File(original, "prevent-file-deletion")
        requireTestPath(child)
        child.writeText("test only")
        select(EntitySelectionKind.Collections, "c", "d")
        withContext(Dispatchers.Main) {
            model.onEvent(GalleryEvent.ToggleEntitySelection("d"))
            model.onEvent(GalleryEvent.ToggleEntitySelection("e"))
        }
        assertEquals(GalleryDialog.DeleteCollections(listOf("c", "d")), model.state.value.dialog)
        val partial = confirm()
        assertEquals(GalleryRoute.Artist("a"), partial.route)
        assertEquals(GalleryDialog.DeleteCollections(listOf("d")), partial.dialog)
        assertEquals(setOf("d"), partial.selectedEntityIds)
        assertNotNull(partial.fieldError)
        val dao = database.galleryDao()
        assertNull(dao.getCollection("c")); assertNotNull(dao.getCollection("d")); assertNotNull(dao.getCollection("e"))
        assertNull(repository.getAsset(good.id)); assertEquals(0, repository.referenceCount(good.id))
        assertNull(dao.getArtist("other")!!.coverAssetId)
        assertEquals("READY", repository.getAsset(blocked.id)!!.state)
        assertEquals(1, repository.referenceCount(blocked.id))
        assertTrue(files.hasOriginal(untouched.storageKey))
        requireTestPath(child); assertTrue(child.delete())
        val retried = confirm()
        assertEquals(GalleryDialog.None, retried.dialog)
        assertNull(retried.entitySelectionKind)
        assertNull(dao.getCollection("d"))
        assertNull(repository.getAsset(blocked.id))
        assertTrue(files.hasOriginal(untouched.storageKey))
        assertNotNull(dao.getArtist("a"))
        assertNotNull(dao.getCollection("foreign"))
    }

    @Test fun failedDatabaseCleanupLeavesPendingDeleteForRecoveryBeforeCollectionRetry() = runBlocking {
        artist("a", "c")
        val image = asset("image", "c")
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_asset_delete BEFORE DELETE ON MediaAsset BEGIN SELECT RAISE(ABORT, 'failure'); END")
        select(EntitySelectionKind.Collections, "c")
        val partial = confirm()
        assertEquals(GalleryDialog.DeleteCollections(listOf("c")), partial.dialog)
        assertEquals("PENDING_DELETE", repository.getAsset(image.id)!!.state)
        assertEquals(1, repository.referenceCount(image.id))
        assertFalse(files.hasOriginal(image.storageKey))
        assertNotNull(database.galleryDao().getCollection("c"))
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_asset_delete")
        importer.recoverPending()
        assertNull(repository.getAsset(image.id))
        assertEquals(0, repository.referenceCount(image.id))
        val recovered = confirm()
        assertEquals(GalleryDialog.None, recovered.dialog)
        assertNull(database.galleryDao().getCollection("c"))
        assertNotNull(database.galleryDao().getArtist("a"))
    }

    @Test fun cancellationAfterCommittedBatchKeepsOnlyUnfinishedMediaSelectedAndInReturnCache() = runBlocking {
        artist("a", "c", "other")
        val selected = (0..256).map { asset("image${it.toString().padStart(3, '0')}", "c") }
        val untouched = asset("untouched", "other")
        var batches = 0
        val cancelOnSecondBatch = object : MediaLibraryRepository by repository {
            override suspend fun markAssetsForDelete(assetIds: List<String>): List<MediaAssetEntity> {
                if (++batches == 2) throw CancellationException("cancel before next batch")
                return repository.markAssetsForDelete(assetIds)
            }
        }
        withContext(Dispatchers.Main) {
            model = GalleryViewModel(repository, SavedStateHandle(), cancelOnSecondBatch, importer)
            store.put("organization", model)
            model.onEvent(GalleryEvent.OpenArtist("a"))
        }
        withTimeout(30_000) { model.state.first { it.loadState == LoadState.Ready && it.mediaLoadState == LoadState.Ready && it.mediaItems.size == 258 } }
        withContext(Dispatchers.Main) { model.onEvent(GalleryEvent.OpenCollection("c", "a")) }
        withTimeout(30_000) { model.state.first { it.loadState == LoadState.Ready && it.mediaLoadState == LoadState.Ready && it.mediaItems.size == 257 } }
        withContext(Dispatchers.Main) {
            model.onEvent(GalleryEvent.StartMediaSelection(selected.first().id))
            selected.drop(1).forEach { model.onEvent(GalleryEvent.ToggleMediaSelection(it.id)) }
            model.onEvent(GalleryEvent.OpenDeleteSelectedMedia)
            model.onEvent(GalleryEvent.ConfirmDeleteSelectedMedia)
            assertTrue(model.state.value.isSaving)
        }
        val partial = withTimeout(30_000) { model.state.first { !it.isSaving } }
        val last = selected.last()
        assertEquals(2, batches)
        assertEquals(GalleryDialog.DeleteSelectedMedia(listOf(last.id)), partial.dialog)
        assertTrue(partial.mediaSelectionMode)
        assertEquals(setOf(last.id), partial.selectedMediaIds)
        assertTrue(partial.fieldError!!.contains("已删除 256 个副本，1 个未完成"))
        assertEquals(setOf(last.id, untouched.id), repository.getArtistAssets("a").mapTo(mutableSetOf()) { it.id })
        assertEquals("READY", repository.getAsset(last.id)!!.state)
        assertTrue(files.hasOriginal(last.storageKey))
        withContext(Dispatchers.Main) {
            model.onEvent(GalleryEvent.ExitMediaSelection)
            model.onEvent(GalleryEvent.NavigateBack)
            assertEquals(GalleryRoute.Artist("a"), model.state.value.route)
            assertEquals(LoadState.Ready, model.state.value.mediaLoadState)
            assertEquals(setOf(last.id, untouched.id), model.state.value.mediaItems.mapTo(mutableSetOf()) { it.id })
        }
        assertNotNull(database.galleryDao().getCollection("c"))
        assertNotNull(database.galleryDao().getArtist("a"))
    }

    @Test fun unavailableReferencedAssetPreventsCollectionRemovalAfterOtherCopiesAreDeleted() = runBlocking {
        artist("a", "c")
        val unavailable = asset("unavailable", "c")
        database.galleryDao().updateAsset(unavailable.copy(state = "STAGED"))
        val ready = asset("ready", "c")
        select(EntitySelectionKind.Collections, "c")
        val partial = confirm()
        assertEquals(GalleryDialog.DeleteCollections(listOf("c")), partial.dialog)
        assertEquals(setOf("c"), partial.selectedEntityIds)
        assertNotNull(partial.fieldError)
        assertNotNull(database.galleryDao().getArtist("a"))
        assertNotNull(database.galleryDao().getCollection("c"))
        assertEquals("STAGED", repository.getAsset(unavailable.id)!!.state)
        assertEquals(1, repository.referenceCount(unavailable.id))
        assertTrue(files.hasOriginal(unavailable.storageKey))
        assertNull(repository.getAsset(ready.id))
        assertFalse(files.hasOriginal(ready.storageKey))
    }
}
