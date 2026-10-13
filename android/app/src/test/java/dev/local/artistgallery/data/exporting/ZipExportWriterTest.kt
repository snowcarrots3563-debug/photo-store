package dev.local.artistgallery.data.exporting

import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.data.backup.BackupAsset
import dev.local.artistgallery.data.backup.BackupManifest
import dev.local.artistgallery.data.backup.BackupValidation
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.enums.CompressionMethod
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZipExportWriterTest {
    @get:Rule val folder = TemporaryFolder()
    private val payload = ByteArray(300000) { (it % 251).toByte() }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun asset() = BackupAsset(UUID.randomUUID().toString(), "第1页.png", "IMAGE", payload.size.toLong(), 200, 300, hash(payload), 1)
    private fun plan(item: BackupAsset = asset()) = ExportPlan("图片导出", listOf(ExportEntry("作品/"), ExportEntry("作品/第1页.png", item.id)), listOf(item), mapOf(item.id to "original"))
    private fun original(bytes: ByteArray = payload) = folder.newFile().apply { writeBytes(bytes) }
    private fun readZip(bytes: ByteArray, block: (ZipFile) -> Unit) {
        val archive = folder.newFile().apply { writeBytes(bytes) }
        ZipFile(archive).use(block)
    }

    @Test fun realZipRoundtripPreservesUtf8StoredBytesAndIncludesCentralDirectoryInOutcome() = runBlocking {
        val source = original()
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<String>()
        val result = ZipExportWriter.write(plan(), output, null, { source }, progress::add)
        val bytes = output.toByteArray()
        assertEquals(1, result.fileCount)
        assertEquals(bytes.size.toLong(), result.byteSize)
        assertEquals(hash(bytes), result.sha256)
        assertTrue(bytes.size > payload.size)
        assertTrue(progress.last().startsWith("1 / 1"))
        readZip(bytes) { zip ->
            assertTrue(zip.isValidZipFile)
            assertEquals(listOf("作品/", "作品/第1页.png"), zip.fileHeaders.map { it.fileName })
            val entry = zip.getFileHeader("作品/第1页.png")
            assertEquals(CompressionMethod.STORE, entry.compressionMethod)
            assertArrayEquals(payload, zip.getInputStream(entry).use { it.readBytes() })
        }
        val zip64Signature = byteArrayOf(0x50, 0x4b, 0x06, 0x06)
        assertTrue((bytes.size - 200 until bytes.size - 4).any { offset -> bytes.copyOfRange(offset, offset + 4).contentEquals(zip64Signature) })
    }

    @Test fun backupAddsManifestAndEachOriginalOnlyOnce() = runBlocking {
        val item = asset()
        val snapshot = GallerySnapshot(BackupManifest(createdAt = 1, assets = listOf(item), artists = emptyList(), collections = emptyList(), references = emptyList(), settings = BrowseOptions()), mapOf(item.id to "original"))
        val source = original()
        val output = ByteArrayOutputStream()
        val manifestBytes = "{\"version\":1}".toByteArray()
        val result = ZipExportWriter.write(ExportPlanBuilder.backup(snapshot), output, manifestBytes, { source }, {})
        assertEquals(1, result.fileCount)
        readZip(output.toByteArray()) { zip ->
            assertEquals(listOf("media/", item.entryPath, BackupValidation.MANIFEST_NAME), zip.fileHeaders.map { it.fileName })
            assertArrayEquals(manifestBytes, zip.getInputStream(zip.getFileHeader(BackupValidation.MANIFEST_NAME)).use { it.readBytes() })
            assertArrayEquals(payload, zip.getInputStream(zip.getFileHeader(item.entryPath)).use { it.readBytes() })
        }
    }

    @Test fun sameSizedCorruptionDoesNotProduceSuccess() = runBlocking {
        val source = original(payload.copyOf().apply { this[80000] = 42 })
        expectIo { ZipExportWriter.write(plan(), ByteArrayOutputStream(), null, { source }, {}) }
    }

    @Test fun missingTruncatedAndLargerOriginalsDoNotProduceSuccess() = runBlocking {
        listOf(File(folder.root, "missing"), original(payload.copyOf(100)), original(payload + byteArrayOf(1))).forEach { source ->
            expectIo { ZipExportWriter.write(plan(), ByteArrayOutputStream(), null, { source }, {}) }
        }
    }

    @Test fun truncatingOriginalAfterFirstBlockIsDetectedByActualSize() = runBlocking {
        val source = original()
        var truncated = false
        expectIo {
            ZipExportWriter.write(plan(), ByteArrayOutputStream(), null, { source }, {
                if (!truncated) { truncated = true; java.io.RandomAccessFile(source, "rw").use { it.setLength(128 * 1024L) } }
            })
        }
        assertTrue(truncated)
    }

    @Test fun cancellationDuringCopyPropagatesAndStopsBeforeWholeOriginal() {
        val source = original()
        val output = ByteArrayOutputStream()
        val job = Job()
        try {
            runBlocking(job) {
                ZipExportWriter.write(plan(), output, null, { source }, { job.cancel() })
            }
            fail("Cancellation must propagate")
        } catch (_: java.util.concurrent.CancellationException) { }
        assertTrue(output.size() < payload.size)
    }

    @Test fun outputFailureAndCloseFailureDoNotProduceSuccess() = runBlocking {
        val source = original()
        expectIo {
            ZipExportWriter.write(plan(), object : OutputStream() {
                override fun write(value: Int) { throw IOException("write failed") }
            }, null, { source }, {})
        }
        expectIo {
            ZipExportWriter.write(plan(), object : ByteArrayOutputStream() {
                override fun close() { throw IOException("close failed") }
            }, null, { source }, {})
        }
    }

    @Test fun unsafeDuplicateAndFileParentPathsFailBeforeAnyOutput() = runBlocking {
        val base = plan()
        val item = base.assets.single()
        val invalid = listOf(
            listOf(ExportEntry("../a.png", item.id)), listOf(ExportEntry("/a.png", item.id)),
            listOf(ExportEntry("a\\b.png", item.id)), listOf(ExportEntry("CON.png", item.id)),
            listOf(ExportEntry("a.png", item.id), ExportEntry("A.PNG", item.id)),
            listOf(ExportEntry("é.png", item.id), ExportEntry("e\u0301.png", item.id)),
            listOf(ExportEntry("parent", item.id), ExportEntry("parent/a.png", item.id)),
            listOf(ExportEntry("a.png")), listOf(ExportEntry("folder/", item.id)),
        )
        invalid.forEach { entries ->
            val output = ByteArrayOutputStream()
            expectIo { ZipExportWriter.write(base.copy(entries = entries), output, null, { original() }, {}) }
            assertEquals(0, output.size())
        }
    }

    @Test fun manifestPresenceSizeAndReservedPathCollisionsFailBeforeWriting() = runBlocking {
        val base = plan()
        val manifest = BackupManifest(createdAt = 1, assets = base.assets, artists = emptyList(), collections = emptyList(), references = emptyList(), settings = BrowseOptions())
        val output = ByteArrayOutputStream()
        expectIo { ZipExportWriter.write(base, output, byteArrayOf(1), { original() }, {}) }
        expectIo { ZipExportWriter.write(base.copy(manifest = manifest), output, null, { original() }, {}) }
        expectIo { ZipExportWriter.write(base.copy(manifest = manifest), output, byteArrayOf(), { original() }, {}) }
        expectIo { ZipExportWriter.write(base.copy(manifest = manifest, entries = listOf(ExportEntry(BackupValidation.MANIFEST_NAME, base.assets.single().id))), output, byteArrayOf(1), { original() }, {}) }
        assertEquals(0, output.size())
    }

    private suspend fun expectIo(block: suspend () -> Unit) {
        try { block(); fail("Expected IOException") }
        catch (_: IOException) { }
    }
}
