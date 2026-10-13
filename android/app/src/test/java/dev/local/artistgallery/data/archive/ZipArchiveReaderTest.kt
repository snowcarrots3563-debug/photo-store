package dev.local.artistgallery.data.archive

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZipArchiveReaderTest {
    @get:Rule val folder = TemporaryFolder()
    private val payload = ByteArray(4096) { (it % 251).toByte() }

    private fun zip(name: String = "作品/第1页.png", password: CharArray? = null): File {
        val file = File(folder.root, "${UUID.randomUUID()}.zip")
        val parameters = ZipParameters().apply {
            fileNameInZip = name
            if (password != null) {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
            }
        }
        ZipFile(file, password).use { it.addStream(ByteArrayInputStream(payload), parameters) }
        return file
    }

    @Test fun readsOriginalBytesAndPreservesUnicodeName() {
        val file = zip()
        ZipArchiveReader(file, listOf(file), payload.size.toLong()).use { reader ->
            val entry = reader.entries().single()
            assertEquals("作品/第1页.png", entry.path)
            val output = ByteArrayOutputStream()
            reader.extract(entry.index, output) {}
            assertArrayEquals(payload, output.toByteArray())
        }
    }

    @Test fun wrongPasswordIsDistinctAndCanRetryWithCorrectPassword() {
        val password = UUID.randomUUID().toString().toCharArray()
        val file = zip(password = password)
        ZipArchiveReader(file, listOf(file), payload.size * 2L).use { reader ->
            reader.entries()
            reader.setPassword(UUID.randomUUID().toString().toCharArray())
            expect(ArchiveError.PASSWORD) { reader.extract(0, ByteArrayOutputStream()) {} }
            reader.setPassword(password)
            val output = ByteArrayOutputStream()
            reader.extract(0, output) {}
            assertArrayEquals(payload, output.toByteArray())
        }
    }

    @Test fun declaredSizeExceedingBudgetIsRejectedBeforeWriting() {
        val file = zip()
        ZipArchiveReader(file, listOf(file), 100).use { reader ->
            val output = ByteArrayOutputStream()
            expect(ArchiveError.LIMIT) { reader.extract(0, output) {} }
            assertEquals(0, output.size())
        }
    }

    @Test fun cancellationPropagatesWithoutBecomingAnArchiveError() {
        val file = zip()
        ZipArchiveReader(file, listOf(file), payload.size.toLong()).use { reader ->
            try {
                reader.extract(0, ByteArrayOutputStream()) { throw java.util.concurrent.CancellationException() }
                fail("Cancellation swallowed")
            } catch (_: java.util.concurrent.CancellationException) { }
        }
    }

    @Test fun splitReaderRequiresExplicitlySelectedVolumes() {
        val file = File(folder.root, "split.zip")
        val input = folder.newFile("neutral.bin").apply { writeBytes(ByteArray(150000).also { java.util.Random(37).nextBytes(it) }) }
        ZipFile(file).use { it.createSplitZipFile(listOf(input), ZipParameters(), true, 65536) }
        ZipArchiveReader(file, listOf(file), 200000).use { reader ->
            expect(ArchiveError.MISSING_VOLUME) { reader.entries() }
        }
        ZipArchiveReader(file, folder.root.listFiles()!!.filter { it.extension in setOf("zip", "z01", "z02") }, 200000).use { reader ->
            reader.entries()
            val output = ByteArrayOutputStream()
            reader.extract(0, output) {}
            assertArrayEquals(input.readBytes(), output.toByteArray())
        }
    }

    @Test fun pathTraversalIsRejectedDuringPreview() {
        val file = zip("../a.png")
        ZipArchiveReader(file, listOf(file), 10000).use { reader ->
            expect(ArchiveError.UNSAFE_PATH) { reader.entries() }
        }
    }

    @Test fun missingPasswordDoesNotProduceSuccess() {
        val file = zip(password = UUID.randomUUID().toString().toCharArray())
        ZipArchiveReader(file, listOf(file), 10000).use { reader ->
            expect(ArchiveError.PASSWORD) { reader.extract(0, ByteArrayOutputStream()) {} }
        }
    }

    @Test fun cumulativeOutputCannotExceedSessionBudget() {
        val file = zip()
        ZipArchiveReader(file, listOf(file), payload.size.toLong()).use { reader ->
            reader.extract(0, ByteArrayOutputStream()) {}
            expect(ArchiveError.LIMIT) { reader.extract(0, ByteArrayOutputStream()) {} }
        }
    }

    @Test fun outputFailureIsReportedAsIo() {
        val file = zip()
        ZipArchiveReader(file, listOf(file), 10000).use { reader ->
            expect(ArchiveError.IO) {
                reader.extract(0, object : java.io.OutputStream() {
                    override fun write(value: Int) { throw java.io.IOException("synthetic write failure") }
                }) {}
            }
        }
    }

    private fun expect(reason: ArchiveError, block: () -> Unit) {
        try { block(); fail("Expected $reason") }
        catch (error: ArchiveReadException) { assertEquals(reason, error.reason) }
    }
}
