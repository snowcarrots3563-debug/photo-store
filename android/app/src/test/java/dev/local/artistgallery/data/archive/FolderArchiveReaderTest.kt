package dev.local.artistgallery.data.archive

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FolderArchiveReaderTest {
    @get:Rule val folder = TemporaryFolder()
    private val payload = ByteArray(6000) { (it % 251).toByte() }
    private data class Row(val entry: ArchiveEntry, val data: ByteArray? = null, val filename: String? = null, val hash: String? = null)

    @Test fun stableIndexesRetainDirectoriesUnicodeAndDuplicateSourceNames() {
        val rows = listOf(Row(ArchiveEntry(0, "作品", 0, true, false)),
            row(1, "作品/第1页.png"), row(2, "作品/第1页.png", payload.reversedArray()),
            Row(ArchiveEntry(3, "作品/readme.txt", -1, false, false, "TXT 暂不导入。")))
        index(rows)
        FolderArchiveReader(folder.root).use { reader ->
            assertEquals(rows.map { it.entry }, reader.entries())
            assertEquals(0L, reader.extract(0, ByteArrayOutputStream()) {})
            assertArrayEquals(payload, extract(reader, 1))
            assertArrayEquals(payload.reversedArray(), extract(reader, 2))
        }
    }

    @Test fun sourceNamedLikeManifestNeverBecomesAPhysicalPath() {
        index(listOf(row(0, "folder.index")))
        assertArrayEquals(payload, extract(FolderArchiveReader(folder.root), 0))
        assertTrue(File(folder.root, "folder.index").length() != payload.size.toLong())
    }

    @Test fun reopeningReadsTheFrozenIndexAndIgnoresNewFiles() {
        index(listOf(row(0, "作品/a.png")))
        val original = FolderArchiveReader(folder.root).entries()
        File(folder.root, "another.png").writeBytes(payload.reversedArray())
        FolderArchiveReader(folder.root).use { reader ->
            assertEquals(original, reader.entries())
            assertArrayEquals(payload, extract(reader, 0))
        }
    }

    @Test fun sameLengthPayloadTamperingFailsContentVerification() {
        index(listOf(row(0)))
        File(folder.root, "data-0").writeBytes(payload.copyOf().also { it[123] = (it[123].toInt() xor 1).toByte() })
        expect(ArchiveError.CORRUPT) { extract(FolderArchiveReader(folder.root), 0) }
    }

    @Test fun missingAndTruncatedPayloadsFailBeforeWriting() {
        index(listOf(row(0)))
        val source = File(folder.root, "data-0")
        source.writeBytes(payload.copyOf(5999))
        val output = ByteArrayOutputStream()
        expect(ArchiveError.CORRUPT) { FolderArchiveReader(folder.root).extract(0, output) {} }
        assertEquals(0, output.size())
        assertTrue(source.delete())
        expect(ArchiveError.CORRUPT) { FolderArchiveReader(folder.root).extract(0, output) {} }
        assertEquals(0, output.size())
    }

    @Test fun extractionCancellationPropagates() {
        index(listOf(row(0)))
        val output = ByteArrayOutputStream()
        try {
            FolderArchiveReader(folder.root).extract(0, output) { throw CancellationException("fixture") }
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { assertEquals(0, output.size()) }
    }

    @Test fun directoryAndSkippedItemsNeverOpenPayloads() {
        index(listOf(Row(ArchiveEntry(0, "empty", 0, true, false)),
            Row(ArchiveEntry(1, "empty/readme.txt", 999_999, false, false, "TXT 暂不导入。"))))
        FolderArchiveReader(folder.root).use { reader ->
            assertEquals(2, reader.entries().size)
            assertEquals(0L, reader.extract(0, ByteArrayOutputStream()) {})
            expect(ArchiveError.IO) { extract(reader, 1) }
        }
        assertFalse(File(folder.root, "data-1").exists())
    }

    @Test fun unsafeSourcePathsAndForeignPhysicalNamesAreRejected() {
        index(listOf(row(0, "../a.png")))
        expect(ArchiveError.UNSAFE_PATH) { FolderArchiveReader(folder.root).entries() }
        index(listOf(row(0, filename = "../data-0")))
        expect(ArchiveError.CORRUPT) { FolderArchiveReader(folder.root).entries() }
    }

    @Test fun nonContiguousIndexAndInvalidHashesAreRejected() {
        index(listOf(row(7)))
        expect(ArchiveError.CORRUPT) { FolderArchiveReader(folder.root).entries() }
        index(listOf(row(0, hash = "invalid")))
        expect(ArchiveError.CORRUPT) { FolderArchiveReader(folder.root).entries() }
    }

    @Test fun invalidVersionEntryCountAndTrailingDataAreRejected() {
        index(emptyList(), version = "future-version")
        expect(ArchiveError.CORRUPT) { FolderArchiveReader(folder.root).entries() }
        index(emptyList(), count = ZipArchiveReader.MAX_ENTRIES + 1)
        expect(ArchiveError.CORRUPT) { FolderArchiveReader(folder.root).entries() }
        index(emptyList(), extra = true)
        expect(ArchiveError.CORRUPT) { FolderArchiveReader(folder.root).entries() }
    }

    @Test fun truncatedIndexAndOversizedMediaAreRejected() {
        File(folder.root, "folder.index").writeBytes(byteArrayOf(0, 30, 1))
        expect(ArchiveError.IO) { FolderArchiveReader(folder.root).entries() }
        index(listOf(row(0).copy(entry = ArchiveEntry(0, "a.png", ZipArchiveReader.MAX_ENTRY_BYTES + 1, false, false))))
        expect(ArchiveError.CORRUPT) { FolderArchiveReader(folder.root).entries() }
    }

    private fun row(index: Int, path: String = "a.png", data: ByteArray = payload, filename: String? = null, hash: String? = null) =
        Row(ArchiveEntry(index, path, data.size.toLong(), false, false), data, filename, hash)

    private fun index(rows: List<Row>, version: String = "artist-gallery-folder-v1", count: Int = rows.size, extra: Boolean = false) {
        DataOutputStream(File(folder.root, "folder.index").outputStream()).use { output ->
            output.writeUTF(version); output.writeInt(count)
            rows.forEach { row ->
                val filename = row.filename ?: if (row.data == null) "" else "data-${row.entry.index}"
                row.data?.let { File(folder.root, "data-${row.entry.index}").writeBytes(it) }
                output.writeInt(row.entry.index); output.writeUTF(row.entry.path); output.writeLong(row.entry.byteSize)
                output.writeBoolean(row.entry.isDirectory); output.writeUTF(row.entry.skipReason.orEmpty()); output.writeUTF(filename)
                output.writeUTF(row.hash ?: row.data?.let { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { byte -> "%02x".format(byte) } }.orEmpty())
            }
            if (extra) output.writeByte(1)
        }
    }

    private fun extract(reader: ArchiveReader, index: Int): ByteArray = ByteArrayOutputStream().also { reader.extract(index, it) {} }.toByteArray()
    private fun expect(reason: ArchiveError, block: () -> Unit) {
        try { block(); fail("Expected $reason") } catch (error: ArchiveReadException) { assertEquals(reason, error.reason) }
    }
}
