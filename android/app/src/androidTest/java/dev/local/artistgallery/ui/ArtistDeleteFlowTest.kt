package dev.local.artistgallery.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.artistgallery.data.ArtistEntity
import dev.local.artistgallery.data.CollectionEntity
import dev.local.artistgallery.data.CollectionMediaEntity
import dev.local.artistgallery.data.GalleryDatabase
import dev.local.artistgallery.data.MediaAssetEntity
import dev.local.artistgallery.data.RoomGalleryRepository
import dev.local.artistgallery.data.importing.MediaFileStore
import dev.local.artistgallery.data.importing.MediaImportCoordinator
import java.io.File
import java.util.UUID
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
class ArtistDeleteFlowTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var testRoot: File
    private lateinit var database: GalleryDatabase
    private lateinit var repository: RoomGalleryRepository
    private lateinit var files: MediaFileStore
    private lateinit var importer: MediaImportCoordinator
    private val viewModels = ViewModelStore()
    private lateinit var model: GalleryViewModel

    @Before fun open() {
        testRoot = File(context.cacheDir, "artist-delete-flow-${UUID.randomUUID()}")
        assertTrue(testRoot.mkdirs())
        requireTestPath(testRoot)
        val isolatedContext = object : ContextWrapper(context) {
            override fun getExternalFilesDir(type: String?): File = testRoot
        }
        database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        repository = RoomGalleryRepository(database)
        files = MediaFileStore(isolatedContext)
        importer = MediaImportCoordinator(isolatedContext, repository, files)
    }

    @After fun close() = runBlocking {
        withTimeout(5_000) {
            withContext(Dispatchers.Main) {
                viewModels.clear()
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
        check(root.name.startsWith("artist-delete-flow-"))
        check(root.path.startsWith(context.cacheDir.canonicalPath + File.separator))
        val path = file.canonicalPath
        check(path == root.path || path.startsWith(root.path + File.separator))
    }

    private suspend fun seedArtist(id: String, vararg collections: String) {
        val dao = database.galleryDao()
        dao.insertArtist(ArtistEntity(id, "画师$id", 1, 1))
        collections.forEach { collection ->
            dao.insertCollection(CollectionEntity(collection, id, "作品$collection", false, 1, 1))
        }
    }

    private suspend fun seedAsset(id: String, vararg collections: String, pending: Boolean = false): MediaAssetEntity {
        val asset = MediaAssetEntity(id, "$id.png", "IMAGE", 4, null, null, id, UUID.randomUUID().toString(),
            if (pending) "PENDING_DELETE" else "READY", 1)
        val dao = database.galleryDao()
        dao.insertAsset(asset)
        collections.forEach { collection ->
            dao.insertCollectionMedia(CollectionMediaEntity(collection, id, dao.nextManualOrder(collection), "$id.png", null))
        }
        withContext(Dispatchers.IO) {
            val original = files.originalFile(asset.storageKey)
            requireTestPath(original)
            assertTrue(original.parentFile!!.exists() || original.parentFile!!.mkdirs())
            original.writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        return asset
    }

    private suspend fun openConfirmation() {
        withContext(Dispatchers.Main) {
            model = GalleryViewModel(repository, SavedStateHandle(), repository, importer)
            viewModels.put("artist-delete", model)
            model.onEvent(GalleryEvent.OpenArtist("a"))
        }
        withTimeout(5_000) {
            model.state.first { it.route == GalleryRoute.Artist("a") && it.loadState == LoadState.Ready && it.mediaLoadState == LoadState.Ready }
        }
        withContext(Dispatchers.Main) {
            model.onEvent(GalleryEvent.OpenDialog(GalleryDialog.ArtistMenu))
            model.onEvent(GalleryEvent.OpenDialog(GalleryDialog.DeleteArtist("a", "画师a")))
        }
    }

    private suspend fun confirmAndWait(): GalleryUiState {
        withContext(Dispatchers.Main) {
            model.onEvent(GalleryEvent.ConfirmDeleteArtist)
            assertTrue(model.state.value.isSaving)
        }
        return withTimeout(5_000) { model.state.first { !it.isSaving } }
    }

    @Test fun cancelConfirmationLeavesArtistReferencesAndOriginalUntouched() = runBlocking {
        seedArtist("a", "c")
        val asset = seedAsset("image", "c")
        val before = database.galleryDao().getArtist("a")
        openConfirmation()
        withContext(Dispatchers.Main) { model.onEvent(GalleryEvent.DismissDialog) }
        assertEquals(GalleryDialog.None, model.state.value.dialog)
        assertFalse(model.state.value.isSaving)
        assertEquals(before, database.galleryDao().getArtist("a"))
        assertEquals(asset, repository.getAsset(asset.id))
        assertEquals(1, repository.referenceCount(asset.id))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), files.originalFile(asset.storageKey).readBytes())
    }

    @Test fun confirmedDeletionClearsSharedOriginalReferencesAndCoversButKeepsOtherContent() = runBlocking {
        seedArtist("a", "c", "empty")
        seedArtist("b", "d")
        val shared = seedAsset("shared", "c", "d")
        val owned = seedAsset("owned", "c")
        val other = seedAsset("other", "d")
        assertTrue(repository.setArtistCover("a", shared.id))
        assertTrue(repository.setArtistCover("b", shared.id))
        val sourceMarker = File(testRoot, "external-source.png")
        requireTestPath(sourceMarker)
        sourceMarker.writeText("source remains unchanged")
        openConfirmation()
        val state = confirmAndWait()
        val dao = database.galleryDao()
        assertEquals(GalleryRoute.Home, state.route)
        assertNull(state.fieldError)
        assertNull(dao.getArtist("a"))
        assertTrue(dao.getCollections("a").isEmpty())
        assertNull(repository.getAsset(shared.id))
        assertNull(repository.getAsset(owned.id))
        assertEquals(0, repository.referenceCount(shared.id))
        assertFalse(files.originalFile(shared.storageKey).exists())
        assertFalse(files.originalFile(owned.storageKey).exists())
        assertNotNull(dao.getArtist("b"))
        assertNull(dao.getArtist("b")!!.coverAssetId)
        assertNotNull(dao.getCollection("d"))
        assertEquals(listOf(other.id), repository.getArtistAssets("b").map { it.id })
        assertTrue(files.hasOriginal(other.storageKey))
        assertEquals("source remains unchanged", sourceMarker.readText())
    }

    @Test fun partialFileFailureRetainsFailedReferenceAndSupportsRetry() = runBlocking {
        seedArtist("a", "c")
        val failed = seedAsset("failed", "c")
        val successful = seedAsset("successful", "c")
        val blockedOriginal = files.originalFile(failed.storageKey)
        requireTestPath(blockedOriginal)
        assertTrue(blockedOriginal.delete())
        assertTrue(blockedOriginal.mkdir())
        val child = File(blockedOriginal, "prevents-file-delete")
        requireTestPath(child)
        child.writeText("test-only child")
        openConfirmation()
        val failedState = confirmAndWait()
        assertEquals(GalleryRoute.Artist("a"), failedState.route)
        assertTrue(failedState.fieldError!!.contains("1 个副本删除失败"))
        assertTrue(failedState.fieldError!!.contains("已删除的副本"))
        assertFalse(failedState.isSaving)
        assertNotNull(database.galleryDao().getArtist("a"))
        assertNotNull(database.galleryDao().getCollection("c"))
        assertEquals("READY", repository.getAsset(failed.id)!!.state)
        assertEquals(1, repository.referenceCount(failed.id))
        assertTrue(child.isFile)
        assertNull(repository.getAsset(successful.id))
        assertFalse(files.originalFile(successful.storageKey).exists())
        requireTestPath(child)
        assertTrue(child.delete())
        val retried = confirmAndWait()
        assertEquals(GalleryRoute.Home, retried.route)
        assertNull(retried.fieldError)
        assertNull(database.galleryDao().getArtist("a"))
        assertNull(repository.getAsset(failed.id))
        assertFalse(blockedOriginal.exists())
    }

    @Test fun confirmedRetryCompletesPreviouslyPendingAssetDeletion() = runBlocking {
        seedArtist("a", "c")
        val asset = seedAsset("pending", "c", pending = true)
        openConfirmation()
        assertEquals("PENDING_DELETE", repository.getAsset(asset.id)!!.state)
        val state = confirmAndWait()
        assertEquals(GalleryRoute.Home, state.route)
        assertFalse(state.isSaving)
        assertNull(state.fieldError)
        assertNull(database.galleryDao().getArtist("a"))
        assertTrue(database.galleryDao().getCollections("a").isEmpty())
        assertNull(repository.getAsset(asset.id))
        assertEquals(0, repository.referenceCount(asset.id))
        assertFalse(files.originalFile(asset.storageKey).exists())
    }
}
