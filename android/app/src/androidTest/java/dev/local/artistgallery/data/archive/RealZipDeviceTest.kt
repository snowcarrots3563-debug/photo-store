package dev.local.artistgallery.data.archive

import android.net.Uri
import android.media.MediaMetadataRetriever
import android.graphics.drawable.AnimatedImageDrawable
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.artistgallery.data.GalleryDatabase
import dev.local.artistgallery.data.RoomGalleryRepository
import dev.local.artistgallery.data.importing.MediaFileStore
import dev.local.artistgallery.data.importing.MediaImportCoordinator
import dev.local.artistgallery.data.preview.AssetReader
import dev.local.artistgallery.data.preview.PreviewDecoder
import dev.local.artistgallery.model.ImportItemStatus
import dev.local.artistgallery.model.MediaType
import dev.local.artistgallery.model.SaveResult
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Tests the selected real archive in an isolated package and random in-memory database. */
@RunWith(AndroidJUnit4::class)
class RealZipDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()

    private fun directory(): File {
        assertEquals("dev.local.artistgallery.phase2test", context.packageName)
        val external = requireNotNull(context.getExternalFilesDir(null)).canonicalFile
        val directory = File(external, requireNotNull(arguments.getString("archiveFolder"))).canonicalFile
        require(directory.path.startsWith(external.path + File.separator))
        return directory
    }

    @Test fun missingWrongPasswordAndCancellationAreReported() {
        val directory = directory()
        val file = File(directory, "input.zip")
        val password = requireNotNull(arguments.getString("archivePassword")).toCharArray()
        try {
            ZipArchiveReader(file, listOf(file), Long.MAX_VALUE).use { reader ->
                val index = reader.entries().first { !it.isDirectory }.index
                expect(ArchiveError.PASSWORD) { reader.extract(index, discard()) {} }
                reader.setPassword(UUID.randomUUID().toString().toCharArray())
                expect(ArchiveError.PASSWORD) { reader.extract(index, discard()) {} }
                reader.setPassword(password)
                var checks = 0
                try {
                    reader.extract(index, discard()) {
                        if (++checks == 3) throw CancellationException()
                    }
                    fail("Cancellation was swallowed")
                } catch (_: CancellationException) { }
                assertEquals(3, checks)
            }
        } finally { password.fill('\u0000') }
    }

    @Test(timeout = 600_000) fun realArchiveMatchesIndependentHashesAndImportsSupportedMedia() = runBlocking {
        val started = SystemClock.elapsedRealtime()
        val directory = directory()
        val file = File(directory, "input.zip")
        val reference = JSONObject(File(directory, "reference-manifest.json").readText())
        val expected = reference.getJSONArray("entries")
        val summary = reference.getJSONObject("summary")
        assertEquals(summary.getLong("archiveBytes"), file.length())
        assertEquals(summary.getString("archiveSha256"), hash(file))
        val database = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        val repository = RoomGalleryRepository(database)
        val files = MediaFileStore(context)
        val importer = MediaImportCoordinator(context, repository, files)
        val assetReader = AssetReader(context, files)
        val decoder = PreviewDecoder(context.contentResolver, assetReader)
        val batchIds = mutableListOf<String>()
        val originalKeys = mutableSetOf<String>()
        val work = File(directory, "work-${UUID.randomUUID()}").apply { check(mkdir()) }
        val password = requireNotNull(arguments.getString("archivePassword")).toCharArray()
        val results = JSONArray()
        val report = JSONObject().put("inputSha256", summary.getString("archiveSha256"))
            .put("normalGalleryTouched", false).put("database", "random in-memory Room database")
        var imported = 0
        var reused = 0
        var skipped = 0
        try {
            ZipArchiveReader(file, listOf(file), summary.getLong("expandedBytes")).use { reader ->
                reader.setPassword(password)
                val entries = reader.entries()
                assertEquals(summary.getInt("entries"), entries.size)
                entries.forEachIndexed { index, entry ->
                    val item = expected.getJSONObject(index)
                    assertEquals(item.getString("path"), entry.path)
                    assertEquals(item.getLong("byteSize"), entry.byteSize)
                    assertEquals(item.getBoolean("directory"), entry.isDirectory)
                }
                val groups = ArchivePreview.build(entries, reference.getString("sourceName"), emptyList())
                assertEquals(summary.getInt("groups"), groups.size)
                val expectedGroups = reference.getJSONObject("groups")
                groups.forEach { group ->
                    val counts = expectedGroups.getJSONObject(group.relativeDirectory)
                    assertEquals(counts.getInt("supported"), group.items.count { it.mediaType != null })
                    assertEquals(counts.getInt("skipped"), group.items.count { it.skipReason != null })
                }
                val previews = groups.flatMap { it.items }.associateBy { it.entry.index }
                val artistId = savedId(repository.createArtist("真实压缩包隔离测试"))
                val collections = mutableMapOf<String, String>()
                val groupByEntry = groups.flatMap { group -> group.items.map { it.entry.index to group } }.toMap()
                for (entry in entries.filterNot { it.isDirectory }) {
                    val item = expected.getJSONObject(entry.index)
                    val preview = previews.getValue(entry.index)
                    val digest = MessageDigest.getInstance("SHA-256")
                    val extracted = if (preview.mediaType == null) null else {
                        val parent = File(work, entry.index.toString()).apply { check(mkdir()) }
                        File(parent, entry.path.substringAfterLast('/'))
                    }
                    val output = extracted?.outputStream()
                    val bytes = try {
                        reader.extract(entry.index, object : OutputStream() {
                            override fun write(value: Int) {
                                digest.update(value.toByte())
                                output?.write(value)
                            }
                            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                                digest.update(buffer, offset, length)
                                output?.write(buffer, offset, length)
                            }
                        }) {}
                    } finally { output?.close() }
                    assertEquals(entry.byteSize, bytes)
                    assertEquals(item.getString("sha256"), digest.digest().hex())
                    val result = JSONObject().put("index", entry.index).put("path", entry.path)
                        .put("byteSize", bytes).put("sha256", item.getString("sha256")).put("hashMatched", true)
                    if (extracted == null) {
                        skipped++
                        result.put("status", "SKIPPED").put("reason", preview.skipReason)
                    } else {
                        // Explicit test wiring: the formal archive-to-import UI is not implemented yet.
                        val group = groupByEntry.getValue(entry.index)
                        val collectionId = collections[group.relativeDirectory]
                        val batch = importer.importUris(listOf(Uri.fromFile(extracted)), collectionId) {}
                        batchIds += batch.id
                        val importItem = batch.items.single()
                        assertTrue(importItem.error, importItem.status in setOf(ImportItemStatus.COMPLETE, ImportItemStatus.REUSED))
                        val storedItem = repository.importItems(batch.id).single()
                        val asset = repository.findAssetsByContent(item.getString("sha256"), bytes).single()
                        originalKeys += asset.storageKey
                        assertEquals(item.getString("sha256"), files.inspectOriginal(asset.storageKey).sha256)
                        assertEquals(item.getString("sha256"), hash(files.originalFile(asset.storageKey)))
                        assertEquals(entry.path.substringAfterLast('/'), asset.originalName)
                        assertEquals(preview.mediaType!!.name, asset.mediaType)
                        when (preview.mediaType) {
                            MediaType.IMAGE -> {
                                val bitmap = requireNotNull(decoder.decodeBitmap(asset.storageKey, 256, 256))
                                try {
                                    assertTrue(bitmap.width > 0 && bitmap.height > 0)
                                    result.put("previewWidth", bitmap.width).put("previewHeight", bitmap.height)
                                } finally { bitmap.recycle() }
                            }
                            MediaType.GIF -> {
                                val drawable = decoder.decodeAnimated(asset.storageKey, 256, 256)
                                assertTrue(drawable is AnimatedImageDrawable)
                                (drawable as AnimatedImageDrawable).stop()
                                result.put("animatedGifDecoded", true)
                            }
                            MediaType.VIDEO -> {
                                MediaMetadataRetriever().use { retriever ->
                                    retriever.setDataSource(context, assetReader.contentUri(asset.storageKey))
                                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                                    assertTrue(duration != null && duration > 0)
                                    result.put("videoDurationMs", duration)
                                }
                            }
                            null -> error("Unsupported media reached import")
                        }
                        if (collectionId == null) {
                            val created = savedId(repository.createCollection(artistId, group.name))
                            collections[group.relativeDirectory] = created
                            repository.addReference(created, asset.id, entry.path.substringAfterLast('/'))
                        }
                        assertEquals(item.getString("sha256"), storedItem.sha256)
                        if (importItem.status == ImportItemStatus.REUSED) reused++ else imported++
                        result.put("status", importItem.status.name)
                        result.put("collection", group.name).put("storedHashMatched", true)
                        assertTrue(extracted.delete())
                    }
                    results.put(result)
                    if (results.length() % 10 == 0) println("phase4 real archive checked=${results.length()}/144 elapsedMs=${SystemClock.elapsedRealtime() - started}")
                }
                val artist = requireNotNull(repository.observeArtist(artistId).first())
                assertEquals(summary.getInt("importableGroups"), artist.collections.size)
                groups.filter { it.canImport }.forEach { group ->
                    val media = repository.observeCollectionMedia(collections.getValue(group.relativeDirectory)).first()
                    assertEquals(group.items.count { it.mediaType != null }, media.size)
                }
                assertEquals(summary.getInt("supportedMedia"), imported + reused)
                assertEquals(summary.getInt("skippedFiles"), skipped)
                assertEquals(summary.getInt("files"), results.length())
                report.put("status", "PASSED").put("entries", entries.size).put("checkedFiles", results.length())
                    .put("previewGroups", groups.size).put("createdCollections", artist.collections.size)
                    .put("newAssets", imported).put("reusedAssets", reused).put("skippedFiles", skipped)
                    .put("elapsedMs", SystemClock.elapsedRealtime() - started).put("items", results)
                assertEquals(summary.getString("archiveSha256"), hash(file))
                File(directory, "device-results.json").writeText(report.toString(2))
            }
        } finally {
            password.fill('\u0000')
            batchIds.forEach { batchId -> repository.importItems(batchId).forEach {
                assertTrue(files.deleteStaging(it.stagingKey))
                assertTrue(files.deleteOriginal(it.storageKey))
            } }
            originalKeys.forEach { assertTrue(files.deleteOriginal(it)) }
            database.close()
            assertTrue(work.deleteRecursively())
        }
    }

    private fun savedId(result: SaveResult): String = (result as? SaveResult.Saved)?.id
        ?: throw AssertionError("Test collection creation failed: $result")

    private fun discard() = object : OutputStream() { override fun write(value: Int) {} }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().hex()
    }

    private fun expect(reason: ArchiveError, block: () -> Unit) {
        try { block(); fail("Expected $reason") }
        catch (error: ArchiveReadException) { assertEquals(reason, error.reason) }
    }
}
