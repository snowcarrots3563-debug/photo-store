package dev.local.artistgallery

import android.app.Application
import androidx.room.Room
import dev.local.artistgallery.data.MediaLibraryRepository
import dev.local.artistgallery.data.GalleryDatabase
import dev.local.artistgallery.data.GalleryRepository
import dev.local.artistgallery.data.RoomGalleryRepository
import dev.local.artistgallery.data.preview.AssetReader
import dev.local.artistgallery.data.preview.PreviewDecoder
import dev.local.artistgallery.data.importing.MediaFileStore
import dev.local.artistgallery.data.importing.MediaImportCoordinator
import dev.local.artistgallery.data.importing.ImportNotifications
import dev.local.artistgallery.data.archive.ArchiveImportCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GalleryApplication : Application() {
    private val database by lazy {
        Room.databaseBuilder(this, GalleryDatabase::class.java, "gallery.db")
            .addMigrations(GalleryDatabase.MIGRATION_1_2, GalleryDatabase.MIGRATION_2_3, GalleryDatabase.MIGRATION_3_4, GalleryDatabase.MIGRATION_4_5, GalleryDatabase.MIGRATION_5_6)
            .build()
    }
    private val roomRepository by lazy { RoomGalleryRepository(database) }
    val repository: GalleryRepository get() = roomRepository
    val browsePreferences by lazy { dev.local.artistgallery.data.LocalBrowsePreferences(this) }
    val updateHistory by lazy { dev.local.artistgallery.data.LocalUpdateHistory(this) }
    val mediaRepository: MediaLibraryRepository get() = roomRepository
    val mediaFiles by lazy { MediaFileStore(this) }
    val assetReader by lazy { AssetReader(this, mediaFiles) }
    val previewDecoder by lazy { PreviewDecoder(contentResolver, assetReader) }
    val mediaImportCoordinator by lazy { MediaImportCoordinator(this, mediaRepository, mediaFiles) }
    val importNotifications by lazy { ImportNotifications(this) }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val archiveImporter by lazy { ArchiveImportCoordinator(this, database, roomRepository, mediaImportCoordinator, mediaFiles, applicationScope, importNotifications::completed) }
    val localTransfer by lazy {
        dev.local.artistgallery.data.exporting.LocalTransferCoordinator(this, database, mediaFiles, browsePreferences, applicationScope)
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(importNotifications)
        applicationScope.launch {
            archiveImporter.recover()
        }
        applicationScope.launch { localTransfer.recover() }
    }
}
