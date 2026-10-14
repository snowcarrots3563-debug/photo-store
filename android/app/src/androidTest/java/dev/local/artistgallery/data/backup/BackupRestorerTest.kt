package dev.local.artistgallery.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.artistgallery.data.ArtistEntity
import dev.local.artistgallery.data.ArtistImageLayout
import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.data.BrowsePreferences
import dev.local.artistgallery.data.CollectionEntity
import dev.local.artistgallery.data.CollectionMediaEntity
import dev.local.artistgallery.data.GalleryDatabase
import dev.local.artistgallery.data.MediaAssetEntity
import dev.local.artistgallery.data.importing.MediaFileStore
import dev.local.artistgallery.model.MediaSort
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory databases and isolated UUID directories only; never opens the ordinary gallery. */
@RunWith(AndroidJUnit4::class)
class BackupRestorerTest {
    @Test fun validMergeKeepsOldLibraryAndRestoresCoversSharedReferencesAndSeparateEqualHashPages() = runBlocking {
        fixture { f ->
            val old = f.installOld()
            val source = f.backup()
            val sourceAssets = BackupArchiveReader(source).use { BackupManifestCodec.decode(it.manifestBytes {}) }.assets
                .sortedWith(compareBy({ it.createdAt }, { it.id })).map { it.originalName }
            val before = f.database.galleryDao().allArtists()
            val prepared = f.restorer.prepare(Uri.fromFile(source), {})
            assertEquals(before, f.database.galleryDao().allArtists())
            val outcome = f.restorer.confirm(prepared)
            assertEquals(RestoreOutcome(1, 2, 2, 3), outcome)
            val dao = f.database.galleryDao()
            val restored = dao.allArtists().single { it.id != before.single().id }
            assertEquals("画师（2）", restored.name)
            assertEquals(17L, restored.createdAt)
            assertEquals(19L, restored.updatedAt)
            val collections = dao.allCollections().filter { it.artistId == restored.id }
            assertEquals(setOf("作品", "作品（2）"), collections.map { it.name }.toSet())
            val assets = dao.allAssets().filter { it.id != old.id }
            assertEquals(2, assets.size)
            assertEquals(1, assets.map { it.sha256 }.distinct().size)
            assertEquals(2, assets.map { it.storageKey }.distinct().size)
            assertEquals(1, assets.map { it.createdAt }.distinct().size)
            assertEquals(sourceAssets, dao.observeArtistImages(restored.id).first().map { it.asset.originalName })
            assertTrue(restored.coverAssetId in assets.map { it.id })
            val references = dao.allReferences().filter { it.assetId != old.id }
            assertEquals(3, references.size)
            assertEquals(2, references.count { it.assetId == restored.coverAssetId })
            assertEquals(setOf(2, 7), references.map { it.manualOrder }.toSet())
            assertTrue(references.all { it.sourceRelativePath?.startsWith("来源/") == true })
            assets.forEach { assertEquals(it.sha256, f.files.inspectOriginal(it.storageKey).sha256) }
            assertEquals(old, dao.getAsset(old.id))
            assertEquals(old.sha256, f.files.inspectOriginal(old.storageKey).sha256)
            assertEquals(ArtistImageLayout.Medium, f.preferences.options.value.artistLayouts[restored.id])
            assertFalse(f.preferences.options.value.cardEffects)
            assertTrue(f.preferences.options.value.playGifs)
            assertTrue(f.preferences.options.value.swipeDelete)
            assertTrue(source.exists())
            assertTrue(f.journalFiles().isEmpty())
        }
    }

    @Test fun wrongHashAndUndecodableMediaFailBeforeAnyDatabaseWrites() = runBlocking {
        fixture { f ->
            val old = f.installOld()
            val dao = f.database.galleryDao()
            val artists = dao.allArtists()
            val assets = dao.allAssets()
            listOf(f.backup(wrongHash = true), f.backup(media = byteArrayOf(1, 2, 3))).forEach { source ->
                rejects { f.restorer.prepare(Uri.fromFile(source), {}) }
                assertEquals(artists, dao.allArtists())
                assertEquals(assets, dao.allAssets())
                assertEquals(old.sha256, f.files.inspectOriginal(old.storageKey).sha256)
                assertTrue(source.exists())
                assertTrue(f.journalFiles().isEmpty())
                assertTrue(f.stagingFiles().isEmpty())
            }
        }
    }

    @Test fun malformedArchiveFailsWithoutChangingDatabaseOrSource() = runBlocking {
        fixture { f ->
            f.installOld()
            val source = f.backup()
            source.writeBytes(source.readBytes().dropLast(10).toByteArray())
            val before = f.database.galleryDao().allAssets()
            rejects { f.restorer.prepare(Uri.fromFile(source), {}) }
            assertEquals(before, f.database.galleryDao().allAssets())
            assertTrue(source.exists())
            assertTrue(f.journalFiles().isEmpty())
        }
    }

    @Test fun cancellingPrepareRemovesOnlyOperationFiles() = runBlocking {
        fixture { f ->
            val old = f.installOld()
            val source = f.backup()
            val job = launch {
                val running = coroutineContext[Job]!!
                f.restorer.prepare(Uri.fromFile(source)) { if (it.startsWith("正在校验原图")) running.cancel() }
            }
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(listOf(old), f.database.galleryDao().allAssets())
            assertTrue(f.files.hasOriginal(old.storageKey))
            assertTrue(source.exists())
            assertTrue(f.journalFiles().isEmpty())
            assertTrue(f.stagingFiles().isEmpty())
        }
    }

    @Test fun databaseInsertFailureRollsBackAllRowsAndPromotedFiles() = runBlocking {
        fixture { f ->
            val old = f.installOld()
            val source = f.backup()
            val prepared = f.restorer.prepare(Uri.fromFile(source), {})
            val id = prepared.stagedAssets.values.first().assetId
            f.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fixture_reject_restore BEFORE INSERT ON MediaAsset WHEN NEW.id = '$id' BEGIN SELECT RAISE(ABORT, 'fixture restore failure'); END")
            val dao = f.database.galleryDao()
            val artists = dao.allArtists()
            val collections = dao.allCollections()
            val references = dao.allReferences()
            try { f.restorer.confirm(prepared); fail("Expected transaction failure") } catch (_: Exception) { }
            assertEquals(artists, dao.allArtists())
            assertEquals(collections, dao.allCollections())
            assertEquals(references, dao.allReferences())
            assertEquals(listOf(old), dao.allAssets())
            prepared.stagedAssets.values.forEach { assertFalse(f.files.hasOriginal(it.storageKey)); assertFalse(f.files.hasStaging(it.stagingKey)) }
            assertEquals(old.sha256, f.files.inspectOriginal(old.storageKey).sha256)
            assertTrue(source.exists())
            assertTrue(f.journalFiles().isEmpty())
        }
    }

    @Test fun changedStagingFailsAndLeavesOldGalleryIntact() = runBlocking {
        fixture { f ->
            val old = f.installOld()
            val prepared = f.restorer.prepare(Uri.fromFile(f.backup()), {})
            f.files.stagingFile(prepared.stagedAssets.values.first().stagingKey).writeBytes(byteArrayOf(1))
            rejects { f.restorer.confirm(prepared) }
            assertEquals(listOf(old), f.database.galleryDao().allAssets())
            assertTrue(f.files.hasOriginal(old.storageKey))
            assertTrue(f.journalFiles().isEmpty())
            assertTrue(f.stagingFiles().isEmpty())
        }
    }

    @Test fun sameLengthStagingTamperingFailsConfirmationAndKeepsOldGalleryAndSource() = runBlocking {
        fixture { f ->
            val old = f.installOld()
            val source = f.backup()
            val prepared = f.restorer.prepare(Uri.fromFile(source), {})
            val target = f.files.stagingFile(prepared.stagedAssets.values.first().stagingKey)
            val bytes = target.readBytes()
            target.writeBytes(bytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
            assertEquals(bytes.size.toLong(), target.length())
            rejects { f.restorer.confirm(prepared) }
            assertEquals(listOf(old), f.database.galleryDao().allAssets())
            assertEquals(old.sha256, f.files.inspectOriginal(old.storageKey).sha256)
            assertTrue(source.exists()); assertTrue(f.journalFiles().isEmpty()); assertTrue(f.stagingFiles().isEmpty())
            prepared.stagedAssets.values.forEach { assertFalse(f.files.hasOriginal(it.storageKey)) }
        }
    }

    @Test fun startupRecoveryDiscardsPreparedAndPromotedUncommittedFiles() = runBlocking {
        fixture { f ->
            val old = f.installOld()
            val source = f.backup()
            val prepared = f.restorer.prepare(Uri.fromFile(source), {})
            val target = prepared.stagedAssets.values.first()
            f.files.promote(target.stagingKey, target.storageKey)
            BackupRestorer(f.context, f.database, f.files, f.preferences).recover()
            prepared.stagedAssets.values.forEach { assertFalse(f.files.hasOriginal(it.storageKey)); assertFalse(f.files.hasStaging(it.stagingKey)) }
            assertEquals(listOf(old), f.database.galleryDao().allAssets())
            assertTrue(f.files.hasOriginal(old.storageKey))
            assertTrue(source.exists())
            assertFalse(prepared.archiveFile.exists())
            assertTrue(f.journalFiles().isEmpty())
        }
    }

    @Test fun committedJournalRecoversDurablePreferencesWithoutDuplicatingRows() = runBlocking {
        fixture { f ->
            f.installOld()
            val source = f.backup()
            val failing = TestPreferences(f.preferences.options.value, failSave = true)
            val restorer = BackupRestorer(f.context, f.database, f.files, failing)
            val prepared = restorer.prepare(Uri.fromFile(source), {})
            val outcome = restorer.confirm(prepared)
            assertEquals(RestoreOutcome(1, 2, 2, 3), outcome.copy(warning = null))
            assertTrue(outcome.warning?.contains("已恢复") == true)
            assertTrue(outcome.warning?.contains("下次启动") == true)
            assertFalse(f.journalFiles().isEmpty())
            val dao = f.database.galleryDao()
            val assets = dao.allAssets()
            val artists = dao.allArtists()
            f.restorer.recover()
            assertEquals(assets, dao.allAssets())
            assertEquals(artists, dao.allArtists())
            assertEquals(2, f.preferences.options.value.artistOrder.size)
            val restored = artists.single { it.name == "画师（2）" }
            assertEquals(ArtistImageLayout.Medium, f.preferences.options.value.artistLayouts[restored.id])
            assertTrue(f.journalFiles().isEmpty())
            val options = f.preferences.options.value
            f.restorer.recover()
            assertEquals(options, f.preferences.options.value)
            assets.forEach { assertEquals(it.sha256, f.files.inspectOriginal(it.storageKey).sha256) }
        }
    }

    @Test fun mergeRestoresArtistSortUnderNewIdAndKeepsExistingArtistSort() = runBlocking {
        fixture { f ->
            f.installOld()
            val oldArtist = f.database.galleryDao().allArtists().single().id
            f.preferences.save(f.preferences.options.value.copy(artistImageSorts = mapOf(oldArtist to MediaSort.SizeAscending)))
            val prepared = f.restorer.prepare(Uri.fromFile(f.backup(sort = MediaSort.NameDescending)), {})
            val sourceArtist = prepared.manifest.artists.single().id
            f.restorer.confirm(prepared)
            val restored = f.database.galleryDao().allArtists().single { it.id != oldArtist }.id
            assertEquals(mapOf(oldArtist to MediaSort.SizeAscending, restored to MediaSort.NameDescending), f.preferences.options.value.artistImageSorts)
            assertFalse(sourceArtist in f.preferences.options.value.artistImageSorts)
            assertTrue(f.journalFiles().isEmpty())
        }
    }

    @Test fun committedJournalReappliesSortWhenOrderAndLayoutWereAlreadySaved() = runBlocking {
        fixture { f ->
            f.installOld()
            val interruptedPreferences = object : BrowsePreferences {
                override val options get() = f.preferences.options
                override fun save(options: BrowseOptions) {
                    f.preferences.save(options.copy(artistImageSorts = emptyMap()))
                    throw IOException("fixture sort persistence interruption")
                }
            }
            val interrupted = BackupRestorer(f.context, f.database, f.files, interruptedPreferences)
            val prepared = interrupted.prepare(Uri.fromFile(f.backup(sort = MediaSort.AddedDescending)), {})
            assertNotNull(interrupted.confirm(prepared).warning)
            val restored = f.database.galleryDao().allArtists().single { it.name == "画师（2）" }.id
            assertTrue(restored in f.preferences.options.value.artistOrder)
            assertTrue(restored in f.preferences.options.value.artistLayouts)
            assertFalse(restored in f.preferences.options.value.artistImageSorts)
            f.restorer.recover()
            assertEquals(MediaSort.AddedDescending, f.preferences.options.value.artistImageSorts[restored])
            assertTrue(f.journalFiles().isEmpty())
        }
    }

    @Test fun mergeRestoresCollectionSortUnderNewIdsAndKeepsExistingChoice() = runBlocking {
        fixture { f ->
            f.installOld()
            val oldCollection = f.database.galleryDao().allCollections().single()
            f.preferences.save(f.preferences.options.value.copy(collectionMediaSorts = mapOf(oldCollection.id to MediaSort.SizeAscending)))
            val prepared = f.restorer.prepare(Uri.fromFile(f.backup(collectionSort = MediaSort.NameDescending)), {})
            val sourceIds = prepared.manifest.collections.map { it.id }.toSet()
            f.restorer.confirm(prepared)
            val restored = f.database.galleryDao().allCollections().filter { it.id != oldCollection.id }
            val first = restored.single { it.name == "作品" }
            val second = restored.single { it.name == "作品（2）" }
            assertEquals(mapOf(oldCollection.id to MediaSort.SizeAscending, first.id to MediaSort.NameDescending, second.id to MediaSort.Default),
                f.preferences.options.value.collectionMediaSorts)
            assertTrue(sourceIds.none { it in f.preferences.options.value.collectionMediaSorts })
            assertFalse(f.preferences.options.value.cardEffects)
            assertTrue(f.journalFiles().isEmpty())
        }
    }

    @Test fun backupWithoutCollectionChoicesCreatesExplicitManualDefaultForRestoredCollections() = runBlocking {
        fixture { f ->
            val prepared = f.restorer.prepare(Uri.fromFile(f.backup()), {})
            f.restorer.confirm(prepared)
            val collectionIds = f.database.galleryDao().allCollections().map { it.id }
            assertEquals(collectionIds.associateWith { MediaSort.Default }, f.preferences.options.value.collectionMediaSorts)
            assertTrue(f.journalFiles().isEmpty())
        }
    }

    @Test fun committedJournalReappliesCollectionSortWhenAllArtistPreferencesWereAlreadySaved() = runBlocking {
        fixture { f ->
            val interruptedPreferences = object : BrowsePreferences {
                override val options get() = f.preferences.options
                override fun save(options: BrowseOptions) {
                    f.preferences.save(options.copy(collectionMediaSorts = emptyMap()))
                    throw IOException("fixture collection sort persistence interruption")
                }
            }
            val interrupted = BackupRestorer(f.context, f.database, f.files, interruptedPreferences)
            val prepared = interrupted.prepare(Uri.fromFile(f.backup(collectionSort = MediaSort.AddedDescending)), {})
            assertNotNull(interrupted.confirm(prepared).warning)
            val restoredArtist = f.database.galleryDao().allArtists().single().id
            assertTrue(restoredArtist in f.preferences.options.value.artistOrder)
            assertTrue(restoredArtist in f.preferences.options.value.artistLayouts)
            assertTrue(restoredArtist in f.preferences.options.value.artistImageSorts)
            assertTrue(f.preferences.options.value.collectionMediaSorts.isEmpty())
            f.restorer.recover()
            val restored = f.database.galleryDao().allCollections()
            assertEquals(MediaSort.AddedDescending, f.preferences.options.value.collectionMediaSorts[restored.single { it.name == "作品" }.id])
            assertEquals(MediaSort.Default, f.preferences.options.value.collectionMediaSorts[restored.single { it.name == "作品（2）" }.id])
            assertTrue(f.journalFiles().isEmpty())
        }
    }

    @Test fun discardCleansPreparedFilesAndRepeatedConfirmCannotDuplicateRestore() = runBlocking {
        fixture { f ->
            f.installOld()
            val source = f.backup()
            val cancelled = f.restorer.prepare(Uri.fromFile(source), {})
            val journals = File(f.context.filesDir, "gallery-restore-journals")
            val ownedPending = File(journals, cancelled.archiveFile.name.removeSuffix(".zip") + ".pending").apply { writeBytes(byteArrayOf(1)) }
            val unrelatedPending = File(journals, UUID.randomUUID().toString() + ".pending").apply { writeBytes(byteArrayOf(2)) }
            f.restorer.discard(cancelled)
            assertFalse(ownedPending.exists())
            assertTrue(unrelatedPending.exists())
            assertTrue(f.stagingFiles().isEmpty())
            assertTrue(source.exists())
            val prepared = f.restorer.prepare(Uri.fromFile(source), {})
            f.restorer.confirm(prepared)
            val before = f.database.galleryDao().allAssets()
            rejects { f.restorer.confirm(prepared) }
            assertEquals(before, f.database.galleryDao().allAssets())
        }
    }

    @Test fun damagedJournalCannotAuthorizeCleanupOfAnyMedia() = runBlocking {
        fixture { f ->
            val old = f.installOld()
            val prepared = f.restorer.prepare(Uri.fromFile(f.backup()), {})
            val journal = f.journalFiles().single { it.name.endsWith(".journal") }
            val bytes = journal.readBytes()
            val target = prepared.stagedAssets.values.first().storageKey.toByteArray()
            val offset = (0..bytes.size - target.size).first { start -> target.indices.all { bytes[start + it] == target[it] } }
            // Still a valid UUID, but no longer the UUID whose ownership was durably recorded.
            bytes[offset] = if (bytes[offset] == '0'.code.toByte()) '1'.code.toByte() else '0'.code.toByte()
            journal.writeBytes(bytes)
            rejects { f.restorer.recover() }
            assertEquals(listOf(old), f.database.galleryDao().allAssets())
            assertTrue(f.files.hasOriginal(old.storageKey))
            prepared.stagedAssets.values.forEach { assertTrue(f.files.hasStaging(it.stagingKey)) }
            assertTrue(journal.exists())
        }
    }

    private suspend fun rejects(block: suspend () -> Unit) {
        try { block(); fail("Invalid restore accepted") } catch (_: IOException) { }
    }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val internal = File(base.filesDir, "restore-test-$id").apply { mkdirs() }
        val externalParent = base.getExternalFilesDir(null)!!
        val external = File(externalParent, "restore-test-$id").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = internal
            override fun getExternalFilesDir(type: String?): File = external
        }
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try { block(Fixture(context, database)) }
        finally {
            database.close()
            check(internal.canonicalFile.parentFile == base.filesDir.canonicalFile)
            check(external.canonicalFile.parentFile == externalParent.canonicalFile)
            internal.deleteRecursively(); external.deleteRecursively()
        }
    }

    private class Fixture(val context: Context, val database: GalleryDatabase) {
        val files = MediaFileStore(context)
        val preferences = TestPreferences(BrowseOptions(cardEffects = false, playGifs = true, swipeDelete = true))
        val restorer = BackupRestorer(context, database, files, preferences)
        fun journalFiles(): List<File> = File(context.filesDir, "gallery-restore-journals").listFiles()?.toList().orEmpty()
        fun stagingFiles(): List<File> = File(context.getExternalFilesDir(null), ".artistgallery-imports").listFiles()?.toList().orEmpty()

        suspend fun installOld(): MediaAssetEntity {
            val stage = uuid()
            val storage = uuid()
            val png = png()
            val written = files.writeToStaging(stage) { it.write(png) }
            files.promote(stage, storage)
            val asset = MediaAssetEntity(uuid(), "已存.png", "IMAGE", written.byteSize, 2, 2, written.sha256, storage, "READY", 1)
            val artist = ArtistEntity(uuid(), "画师", 1, 1, asset.id)
            val collection = CollectionEntity(uuid(), artist.id, "已有作品", false, 1, 1)
            val dao = database.galleryDao()
            dao.insertArtist(artist); dao.insertCollection(collection); dao.insertAsset(asset)
            dao.insertCollectionMedia(CollectionMediaEntity(collection.id, asset.id, 0, asset.originalName, null))
            preferences.save(preferences.options.value.copy(artistOrder = listOf(artist.id), artistLayouts = mapOf(artist.id to ArtistImageLayout.List)))
            return asset
        }

        fun backup(wrongHash: Boolean = false, media: ByteArray = png(), sort: MediaSort? = null, collectionSort: MediaSort? = null): File {
            val assetA = BackupAsset(uuid(), "第1页.png", "IMAGE", media.size.toLong(), 2, 2, if (wrongHash) "0".repeat(64) else sha(media), 25)
            val assetB = assetA.copy(id = uuid(), originalName = "第2页.png")
            val artist = ArtistEntity(uuid(), "画师", 17, 19, assetA.id)
            val first = CollectionEntity(uuid(), artist.id, "作品", false, 20, 21)
            val second = first.copy(id = uuid(), createdAt = 22, updatedAt = 23)
            val manifest = BackupManifest(createdAt = 100,
                assets = listOf(assetA, assetB), artists = listOf(artist), collections = listOf(first, second),
                references = listOf(
                    CollectionMediaEntity(first.id, assetA.id, 2, "封面.png", "来源/封面.png"),
                    CollectionMediaEntity(first.id, assetB.id, 7, "第2页.png", "来源/第2页.png"),
                    CollectionMediaEntity(second.id, assetA.id, 2, "共享.png", "来源/共享.png"),
                ), settings = BrowseOptions(cardEffects = true, playGifs = false, swipeDelete = false, artistOrder = listOf(artist.id),
                    artistLayouts = mapOf(artist.id to ArtistImageLayout.Medium), collectionOrders = mapOf(artist.id to listOf(second.id, first.id)),
                    artistImageSorts = sort?.let { mapOf(artist.id to it) }.orEmpty(),
                    collectionMediaSorts = collectionSort?.let { mapOf(first.id to it) }.orEmpty()),
            )
            val source = File(context.getExternalFilesDir(null), "selected-${uuid()}.zip")
            ZipOutputStream(source.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(BackupValidation.MANIFEST_NAME)); zip.write(BackupManifestCodec.encode(manifest)); zip.closeEntry()
                manifest.assets.forEach { zip.putNextEntry(ZipEntry(it.entryPath)); zip.write(media); zip.closeEntry() }
            }
            return source
        }

        companion object {
            private fun uuid() = UUID.randomUUID().toString()
            private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            private fun png(): ByteArray {
                val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                return try { ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() } }
                finally { bitmap.recycle() }
            }
        }
    }

    private class TestPreferences(initial: BrowseOptions, private val failSave: Boolean = false) : BrowsePreferences {
        override val options = MutableStateFlow(initial)
        override fun save(options: BrowseOptions) { if (failSave) throw IOException("fixture preference failure"); this.options.value = options }
    }
}
