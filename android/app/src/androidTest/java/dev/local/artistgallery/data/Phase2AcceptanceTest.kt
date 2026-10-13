package dev.local.artistgallery.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.artistgallery.data.importing.MediaFileStore
import dev.local.artistgallery.data.importing.MediaImportCoordinator
import dev.local.artistgallery.model.ImportItemStatus
import dev.local.artistgallery.model.CoverRef
import dev.local.artistgallery.model.ReferenceRemovalResult
import dev.local.artistgallery.model.SaveResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase2AcceptanceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val migrationHelper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GalleryDatabase::class.java)

    @Test fun migrateV1PreservesArtistsAndCollections() {
        val name = "phase2-migration-${UUID.randomUUID()}.db"
        migrationHelper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO Artist (id, name, createdAt, updatedAt) VALUES ('a1', '迁移样例画师', 1, 1)")
            execSQL("INSERT INTO WorkCollection (id, artistId, name, isTemporary, createdAt, updatedAt) VALUES ('c1', 'a1', '迁移样例作品集', 0, 2, 2)")
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(name, 2, true, GalleryDatabase.MIGRATION_1_2)
        try {
            migrated.query("SELECT id, name FROM Artist").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("a1", cursor.getString(0))
                assertEquals("迁移样例画师", cursor.getString(1))
                assertFalse(cursor.moveToNext())
            }
            migrated.query("SELECT id, artistId, name FROM WorkCollection").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("c1", cursor.getString(0))
                assertEquals("a1", cursor.getString(1))
                assertEquals("迁移样例作品集", cursor.getString(2))
                assertFalse(cursor.moveToNext())
            }
        } finally {
            migrated.close()
        }
    }

    @Test fun identicalImportsReuseAssetKeepSourceNamesAndLastReferenceCanRemainUnassigned() = runBlocking {
        val databaseName = "phase2-import-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, GalleryDatabase::class.java, databaseName)
            .addMigrations(GalleryDatabase.MIGRATION_1_2)
            .build()
        val repository = RoomGalleryRepository(database)
        val files = MediaFileStore(context)
        val importer = MediaImportCoordinator(context, repository, files)
        val sourceUris = mutableListOf<Uri>()
        val batchIds = mutableListOf<String>()
        try {
            val artistId = savedId(repository.createArtist("阶段二导入样例"))
            val firstCollection = savedId(repository.createCollection(artistId, "导入样例甲"))
            val secondCollection = savedId(repository.createCollection(artistId, "导入样例乙"))
            val bytes = samplePng()
            val firstName = "phase2-first-${UUID.randomUUID()}.png"
            val secondName = "phase2-second-${UUID.randomUUID()}.png"
            val firstUri = insertPendingImage(firstName, bytes)
            val secondUri = insertPendingImage(secondName, bytes)
            sourceUris += firstUri
            sourceUris += secondUri
            val firstSourceHash = hash(firstUri)
            val secondSourceHash = hash(secondUri)
            assertEquals(firstSourceHash, secondSourceHash)

            val firstImport = importer.importUris(listOf(firstUri), firstCollection) { }
            batchIds += firstImport.id
            assertEquals(ImportItemStatus.COMPLETE, firstImport.items.single().status)

            val secondImport = importer.importUris(listOf(secondUri), secondCollection) { }
            batchIds += secondImport.id
            assertEquals(ImportItemStatus.REUSED, secondImport.items.single().status)

            val firstMedia = repository.observeCollectionMedia(firstCollection).first().single()
            val secondMedia = repository.observeCollectionMedia(secondCollection).first().single()
            assertEquals(firstMedia.id, secondMedia.id)
            assertEquals(firstName, firstMedia.sourceName)
            assertEquals(secondName, secondMedia.sourceName)
            assertEquals(1, repository.observeArtistImages(artistId).first().size)
            assertEquals(2, repository.referenceCount(firstMedia.id))

            val duplicateInSameCollection = importer.importUris(listOf(secondUri), secondCollection) { }
            batchIds += duplicateInSameCollection.id
            assertEquals(ImportItemStatus.SKIPPED, duplicateInSameCollection.items.single().status)
            assertEquals(2, repository.referenceCount(firstMedia.id))
            assertEquals(firstSourceHash, hash(firstUri))
            assertEquals(secondSourceHash, hash(secondUri))

            assertEquals(ReferenceRemovalResult.REMOVED_SHARED, repository.removeReference(firstCollection, firstMedia.id))
            assertEquals(ReferenceRemovalResult.LAST_REFERENCE, repository.removeReference(secondCollection, firstMedia.id))
            assertTrue(repository.removeLastReferenceAndKeep(secondCollection, firstMedia.id))
            assertEquals(firstMedia.id, repository.observeUnassignedMedia().first().single().id)
        } finally {
            sourceUris.forEach { context.contentResolver.delete(it, null, null) }
            batchIds.forEach { batchId ->
                repository.importItems(batchId).forEach { item ->
                    files.deleteStaging(item.stagingKey)
                    files.deleteOriginal(item.storageKey)
                }
            }
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun truncatedCopyRemovesStagingFile() = runBlocking {
        val files = MediaFileStore(context)
        val bytes = samplePng()
        val uri = insertPendingImage("phase2-length-${UUID.randomUUID()}.png", bytes)
        val stagingKey = UUID.randomUUID().toString()
        try {
            var failed = false
            try {
                files.copyToStaging(uri, stagingKey, bytes.size.toLong() + 1) { }
            } catch (_: IOException) {
                failed = true
            }
            assertTrue(failed)
            assertFalse(files.hasStaging(stagingKey))
        } finally {
            files.deleteStaging(stagingKey)
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test fun unreadableItemDoesNotBlockOtherItemsInBatch() = runBlocking {
        val databaseName = "phase2-partial-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, GalleryDatabase::class.java, databaseName)
            .addMigrations(GalleryDatabase.MIGRATION_1_2)
            .build()
        val repository = RoomGalleryRepository(database)
        val files = MediaFileStore(context)
        val importer = MediaImportCoordinator(context, repository, files)
        val validUri = insertPendingImage("phase2-valid-${UUID.randomUUID()}.png", samplePng())
        val invalidUri = Uri.parse("content://dev.local.artistgallery.invalid/missing")
        var batchId: String? = null
        try {
            assertTrue(importer.previewUris(listOf(invalidUri, validUri)).first().error != null)
            val collectionId = savedId(repository.createCollection(savedId(repository.createArtist("阶段二部分失败样例")), "部分失败"))
            val result = importer.importUris(listOf(invalidUri, validUri), collectionId) { }
            batchId = result.id
            assertEquals(listOf(ImportItemStatus.FAILED, ImportItemStatus.COMPLETE), result.items.map { it.status })
            assertFalse(result.items.first().canRetry)
            assertEquals(1, repository.observeCollectionMedia(collectionId).first().size)
        } finally {
            batchId?.let { id -> repository.importItems(id).forEach { item ->
                files.deleteStaging(item.stagingKey)
                files.deleteOriginal(item.storageKey)
            } }
            context.contentResolver.delete(validUri, null, null)
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun recoveryOnlyChangesInterruptedItems() = runBlocking {
        val databaseName = "phase2-recovery-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, GalleryDatabase::class.java, databaseName)
            .addMigrations(GalleryDatabase.MIGRATION_1_2)
            .build()
        val repository = RoomGalleryRepository(database)
        try {
            val now = System.currentTimeMillis()
            val batchId = UUID.randomUUID().toString()
            val interruptedId = UUID.randomUUID().toString()
            val completedId = UUID.randomUUID().toString()
            repository.insertImport(
                ImportBatchEntity(batchId, null, "INTERRUPTED", now, now),
                listOf(
                    ImportItemEntity(interruptedId, batchId, null, "content://invalid/interrupted", false, "interrupted.png", "IMAGE", UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, null, "COPYING", null, now, now),
                    ImportItemEntity(completedId, batchId, null, "content://invalid/completed", false, "reused.png", "IMAGE", UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, null, "REUSED", null, now, now),
                ),
            )

            MediaImportCoordinator(context, repository, MediaFileStore(context)).recoverPending()

            val items = repository.importItems(batchId).associateBy { it.id }
            assertEquals("FAILED", items.getValue(interruptedId).status)
            assertEquals("REUSED", items.getValue(completedId).status)
            assertEquals("PARTIAL", repository.latestImportBatches().single { it.id == batchId }.status)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test fun sharedAssetsKeepIndependentCollectionOrderAndDeleteFailureCanRecover() = runBlocking {
        val databaseName = "phase2-order-delete-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, GalleryDatabase::class.java, databaseName)
            .addMigrations(GalleryDatabase.MIGRATION_1_2)
            .build()
        val repository = RoomGalleryRepository(database)
        val files = MediaFileStore(context)
        val importer = MediaImportCoordinator(context, repository, files)
        val sourceUris = mutableListOf<Uri>()
        val batchIds = mutableListOf<String>()
        try {
            val artistId = savedId(repository.createArtist("顺序删除样例画师"))
            val firstCollection = savedId(repository.createCollection(artistId, "顺序样例甲"))
            val secondCollection = savedId(repository.createCollection(artistId, "顺序样例乙"))
            val firstBytes = samplePng(android.graphics.Color.RED)
            val secondBytes = samplePng(android.graphics.Color.BLUE)
            val firstName = "phase2-order-first-${UUID.randomUUID()}.png"
            val secondName = "phase2-order-second-${UUID.randomUUID()}.png"
            val firstUri = insertPendingImage(firstName, firstBytes)
            val secondUri = insertPendingImage(secondName, secondBytes)
            sourceUris += firstUri
            sourceUris += secondUri
            val firstSourceHash = hash(firstUri)
            val secondSourceHash = hash(secondUri)

            listOf(
                importer.importUris(listOf(firstUri), firstCollection) { },
                importer.importUris(listOf(secondUri), firstCollection) { },
                importer.importUris(listOf(secondUri), secondCollection) { },
                importer.importUris(listOf(firstUri), secondCollection) { },
            ).forEach { batch ->
                batchIds += batch.id
                assertTrue(batch.items.single().status in setOf(ImportItemStatus.COMPLETE, ImportItemStatus.REUSED))
            }

            val firstOrder = repository.observeCollectionMedia(firstCollection).first()
            val secondOrder = repository.observeCollectionMedia(secondCollection).first()
            assertEquals(listOf(firstName, secondName), firstOrder.map { it.sourceName })
            assertEquals(listOf(0, 1), firstOrder.map { it.manualOrder })
            assertEquals(listOf(firstOrder[1].id, firstOrder[0].id), secondOrder.map { it.id })
            assertEquals(listOf(secondName, firstName), secondOrder.map { it.sourceName })
            assertEquals(listOf(0, 1), secondOrder.map { it.manualOrder })
            assertEquals(CoverRef.Asset(firstOrder[0].storageKey), repository.observeCollection(firstCollection).first()!!.cover)
            assertEquals(CoverRef.Asset(secondOrder[0].storageKey), repository.observeCollection(secondCollection).first()!!.cover)
            assertEquals(firstSourceHash, firstOrder.first().let { files.inspectOriginal(it.storageKey).sha256 })

            val firstAsset = repository.getAsset(firstOrder[0].id)!!
            assertEquals(ReferenceRemovalResult.REMOVED_SHARED, repository.removeReference(firstCollection, firstAsset.id))
            assertEquals(1, repository.referenceCount(firstAsset.id))
            assertEquals(CoverRef.Asset(firstOrder[1].storageKey), repository.observeCollection(firstCollection).first()!!.cover)

            val pendingDelete = repository.markLastReferenceForDelete(secondCollection, firstAsset.id)!!
            assertEquals("PENDING_DELETE", pendingDelete.state)
            assertTrue(files.hasOriginal(firstAsset.storageKey))
            repository.restoreAssetAfterDeleteFailure(firstAsset.id)
            assertEquals("READY", repository.getAsset(firstAsset.id)!!.state)
            assertEquals(1, repository.referenceCount(firstAsset.id))
            assertTrue(files.hasOriginal(firstAsset.storageKey))

            val confirmedDelete = repository.markLastReferenceForDelete(secondCollection, firstAsset.id)!!
            assertTrue(files.deleteOriginal(confirmedDelete.storageKey))
            repository.completeAssetDelete(firstAsset.id)
            assertNull(repository.getAsset(firstAsset.id))
            assertFalse(files.hasOriginal(firstAsset.storageKey))
            assertEquals(firstSourceHash, hash(firstUri))
            assertEquals(secondSourceHash, hash(secondUri))
            assertEquals(listOf(secondOrder[0].id), repository.observeCollectionMedia(secondCollection).first().map { it.id })
            assertEquals(CoverRef.Asset(secondOrder[0].storageKey), repository.observeCollection(secondCollection).first()!!.cover)
        } finally {
            sourceUris.forEach { context.contentResolver.delete(it, null, null) }
            batchIds.forEach { batchId ->
                repository.importItems(batchId).forEach { item ->
                    files.deleteStaging(item.stagingKey)
                    files.deleteOriginal(item.storageKey)
                }
            }
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun savedId(result: SaveResult): String {
        assertTrue(result is SaveResult.Saved)
        return (result as SaveResult.Saved).id
    }

    private fun samplePng(color: Int = android.graphics.Color.MAGENTA): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun insertPendingImage(name: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_PICTURES}/Phase2Acceptance")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法建立阶段二测试图片")
        try {
            context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: error("无法写入阶段二测试图片")
            return uri
        } catch (error: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private fun hash(uri: Uri): String = context.contentResolver.openInputStream(uri)!!.use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
