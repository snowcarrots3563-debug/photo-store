package dev.local.artistgallery.data.backup

import dev.local.artistgallery.data.BrowseOptions
import dev.local.artistgallery.data.exporting.ExportPlanBuilder
import dev.local.artistgallery.data.exporting.GallerySnapshot
import dev.local.artistgallery.data.exporting.ZipExportWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupArchiveReaderTest {
    private val asset = BackupAsset("00000000-0000-0000-0000-000000000001", "image.png", "IMAGE", 3, 2, 2, "a".repeat(64), 1)
    private val manifest = BackupManifest(createdAt = 1, assets = listOf(asset), artists = emptyList(), collections = emptyList(), references = emptyList(), settings = BrowseOptions())

    @Test fun readsExactEntriesWithoutWritingZipSuppliedPaths() = withZip(listOf(BackupValidation.MANIFEST_NAME to "{}".toByteArray(), asset.entryPath to byteArrayOf(1, 2, 3))) { file ->
        BackupArchiveReader(file).use { reader ->
            assertArrayEquals("{}".toByteArray(), reader.manifestBytes {})
            reader.validateEntries(manifest)
            val output = ByteArrayOutputStream()
            reader.extract(asset.entryPath, output) {}
            assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        }
    }

    @Test fun rejectsTraversalAndUnknownPaths() {
        listOf("../outside.png", "/outside.png", "media\\${asset.id}", "extra.txt").forEach { path ->
            withZip(listOf(BackupValidation.MANIFEST_NAME to byteArrayOf(1), path to byteArrayOf(1))) { file -> rejects { BackupArchiveReader(file).close() } }
        }
    }

    @Test fun rejectsMissingAndExtraMediaEntries() = withZip(listOf(BackupValidation.MANIFEST_NAME to byteArrayOf(1), asset.entryPath to byteArrayOf(1, 2, 3))) { file ->
        BackupArchiveReader(file).use { reader ->
            rejects { reader.validateEntries(manifest.copy(assets = emptyList())) }
            rejects { reader.validateEntries(manifest.copy(assets = listOf(asset, asset.copy(id = "00000000-0000-0000-0000-000000000002")))) }
        }
    }

    @Test fun rejectsDirectoryEncryptedAndSplitEntries() {
        withZip(listOf(BackupValidation.MANIFEST_NAME to byteArrayOf(1), "media/other/" to byteArrayOf())) { file -> rejects { BackupArchiveReader(file).close() } }
        withZip(listOf(BackupValidation.MANIFEST_NAME to byteArrayOf(1))) { file ->
            val bytes = file.readBytes()
            bytes[6] = 1
            val central = signature(bytes, 0x01, 0x02)
            bytes[central + 8] = 1
            file.writeBytes(bytes)
            rejects { BackupArchiveReader(file).close() }
        }
        withZip(listOf(BackupValidation.MANIFEST_NAME to byteArrayOf(1))) { file ->
            val bytes = file.readBytes()
            bytes[signature(bytes, 0x05, 0x06) + 4] = 1
            file.writeBytes(bytes)
            rejects { BackupArchiveReader(file).close() }
        }
    }

    @Test fun rejectsDuplicateNamesBeforeReadingManifest() {
        val other = asset.entryPath.dropLast(1) + "2"
        withZip(listOf(BackupValidation.MANIFEST_NAME to byteArrayOf(1), asset.entryPath to byteArrayOf(1), other to byteArrayOf(1))) { file ->
            val bytes = file.readBytes()
            val from = other.toByteArray()
            val to = asset.entryPath.toByteArray()
            for (index in 0..bytes.size - from.size) if (from.indices.all { bytes[index + it] == from[it] }) to.copyInto(bytes, index)
            file.writeBytes(bytes)
            rejects { BackupArchiveReader(file).close() }
        }
    }

    @Test fun rejectsTruncationSizeMismatchAndBadCrc() {
        withZip(listOf(BackupValidation.MANIFEST_NAME to byteArrayOf(1), asset.entryPath to byteArrayOf(1, 2, 3))) { file ->
            BackupArchiveReader(file).use { reader -> rejects { reader.validateEntries(manifest.copy(assets = listOf(asset.copy(byteSize = 4)))) } }
            file.writeBytes(file.readBytes().dropLast(5).toByteArray())
            rejects { BackupArchiveReader(file).close() }
        }
        withZip(listOf(BackupValidation.MANIFEST_NAME to byteArrayOf(1))) { file ->
            val bytes = file.readBytes()
            bytes[30 + BackupValidation.MANIFEST_NAME.toByteArray().size] = 2
            file.writeBytes(bytes)
            BackupArchiveReader(file).use { reader -> rejects { reader.manifestBytes {} } }
        }
    }

    @Test fun rejectsOversizedManifestAndDirectoryBeforeAllocation() {
        withZip(listOf(BackupValidation.MANIFEST_NAME to byteArrayOf(1))) { file ->
            val bytes = file.readBytes()
            val end = signature(bytes, 0x05, 0x06)
            bytes[end + 14] = 0x7f
            file.writeBytes(bytes)
            rejects { BackupArchiveReader(file).close() }
        }
        withZip(listOf(BackupValidation.MANIFEST_NAME to byteArrayOf(1))) { file ->
            val bytes = file.readBytes()
            val central = signature(bytes, 0x01, 0x02)
            // Advertise 32 MiB even though only one byte is present.
            bytes[central + 24] = 0; bytes[central + 25] = 0; bytes[central + 26] = 0; bytes[central + 27] = 2
            file.writeBytes(bytes)
            rejects { BackupArchiveReader(file).close() }
        }
    }

    @Test fun generatedBackupsRoundTripThroughReaderIncludingEmptyBackup() = runBlocking {
        val directory = Files.createTempDirectory("backup-roundtrip-test").toFile()
        val source = File(directory, "source.png")
        val backup = File(directory, "backup.zip")
        try {
            val bytes = byteArrayOf(1, 2, 3)
            source.writeBytes(bytes)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            listOf(manifest.copy(assets = listOf(asset.copy(sha256 = sha))), manifest.copy(assets = emptyList())).forEach { value ->
                val snapshot = GallerySnapshot(value, value.assets.associate { it.id to "fixture" })
                val plan = ExportPlanBuilder.backup(snapshot)
                ZipExportWriter.write(plan, backup.outputStream(), "{}".toByteArray(), { source }, {})
                BackupArchiveReader(backup).use { reader ->
                    assertArrayEquals("{}".toByteArray(), reader.manifestBytes {})
                    reader.validateEntries(value)
                    value.assets.forEach { entry ->
                        val output = ByteArrayOutputStream()
                        reader.extract(entry.entryPath, output, {})
                        assertArrayEquals(bytes, output.toByteArray())
                    }
                }
            }
        } finally { backup.delete(); source.delete(); directory.delete() }
    }

    @Test fun actualZip64LocatorIsValidatedEvenWithoutLegacySentinels() = withZip(listOf(BackupValidation.MANIFEST_NAME to byteArrayOf(1))) { file ->
        addZip64Record(file)
        assertEquals(1, BackupArchiveReader.checkDirectory(file))
        BackupArchiveReader(file).use { assertArrayEquals(byteArrayOf(1), it.manifestBytes {}) }
        val bytes = file.readBytes()
        val record = signature(bytes, 0x06, 0x06)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(record + 4, Int.MAX_VALUE.toLong())
        file.writeBytes(bytes)
        // Check only the bounded precheck: malicious SIZE must never reach Zip4j allocation.
        rejects { BackupArchiveReader.checkDirectory(file) }
    }

    @Test fun zip64RecordSizesAreBoundedBeforeAnyZipLibraryAllocation() = withZip(listOf(BackupValidation.MANIFEST_NAME to byteArrayOf(1))) { file ->
        addZip64Record(file)
        val valid = file.readBytes()
        val record = signature(valid, 0x06, 0x06)
        listOf(-1L, 43L, 4097L, Int.MAX_VALUE.toLong(), Long.MAX_VALUE).forEach { maliciousSize ->
            val bytes = valid.copyOf()
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(record + 4, maliciousSize)
            file.writeBytes(bytes)
            rejects { BackupArchiveReader.checkDirectory(file) }
        }
    }

    @Test fun zip64TruncationAndLocatorOverlapFailPrecheck() = withZip(listOf(BackupValidation.MANIFEST_NAME to byteArrayOf(1))) { file ->
        addZip64Record(file)
        val valid = file.readBytes()
        val record = signature(valid, 0x06, 0x06)
        val locator = signature(valid, 0x06, 0x07)
        val overlap = valid.copyOf()
        ByteBuffer.wrap(overlap).order(ByteOrder.LITTLE_ENDIAN).putLong(record + 4, 45)
        file.writeBytes(overlap)
        rejects { BackupArchiveReader.checkDirectory(file) }
        val shortRecord = valid.copyOf()
        ByteBuffer.wrap(shortRecord).order(ByteOrder.LITTLE_ENDIAN).putLong(locator + 8, record.toLong() + 1)
        file.writeBytes(shortRecord)
        rejects { BackupArchiveReader.checkDirectory(file) }
        file.writeBytes(valid.copyOfRange(0, locator - 1) + valid.copyOfRange(locator, valid.size))
        rejects { BackupArchiveReader.checkDirectory(file) }
    }

    @Test fun zip64CountAndCentralDirectoryRangesMustMatchAndStayBeforeEndRecord() = withZip(listOf(BackupValidation.MANIFEST_NAME to byteArrayOf(1))) { file ->
        addZip64Record(file)
        val valid = file.readBytes()
        val record = signature(valid, 0x06, 0x06)
        listOf(32 to 100_003L, 40 to 33L * 1024 * 1024, 48 to record.toLong()).forEach { (field, value) ->
            val bytes = valid.copyOf()
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(record + field, value)
            file.writeBytes(bytes)
            rejects { BackupArchiveReader.checkDirectory(file) }
        }
    }

    @Test fun maximumZipCommentCannotHideZip64LocatorFromBoundedPrecheck() = withZip(listOf(BackupValidation.MANIFEST_NAME to byteArrayOf(1))) { file ->
        addZip64Record(file)
        val withoutComment = file.readBytes()
        val end = signature(withoutComment, 0x05, 0x06)
        ByteBuffer.wrap(withoutComment).order(ByteOrder.LITTLE_ENDIAN).putShort(end + 20, (-1).toShort())
        val bytes = withoutComment + ByteArray(65_535) { 'x'.code.toByte() }
        file.writeBytes(bytes)
        assertEquals(1, BackupArchiveReader.checkDirectory(file))
        val record = signature(bytes, 0x06, 0x06)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(record + 4, Int.MAX_VALUE.toLong())
        file.writeBytes(bytes)
        rejects { BackupArchiveReader.checkDirectory(file) }
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Invalid backup was accepted") } catch (_: IOException) { }
    }

    private fun signature(bytes: ByteArray, third: Int, fourth: Int): Int = (0 until bytes.size - 3).first {
        bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() && bytes[it + 2] == third.toByte() && bytes[it + 3] == fourth.toByte()
    }

    private fun addZip64Record(file: File) {
        val bytes = file.readBytes()
        val end = signature(bytes, 0x05, 0x06)
        val legacy = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = legacy.getShort(end + 10).toLong() and 65_535
        val size = legacy.getInt(end + 12).toLong() and 0xffffffffL
        val offset = legacy.getInt(end + 16).toLong() and 0xffffffffL
        val record = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, 0x06064b50); putLong(4, 44)
            putShort(12, 45); putShort(14, 45)
            putLong(24, count); putLong(32, count); putLong(40, size); putLong(48, offset)
        }.array()
        val locator = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, 0x07064b50); putLong(8, end.toLong()); putInt(16, 1)
        }.array()
        // Legacy count, size and offset deliberately remain ordinary non-sentinel values.
        file.writeBytes(bytes.copyOfRange(0, end) + record + locator + bytes.copyOfRange(end, bytes.size))
    }

    private fun withZip(entries: List<Pair<String, ByteArray>>, block: (File) -> Unit) {
        val directory = Files.createTempDirectory("backup-reader-test").toFile()
        val file = File(directory, "backup.zip")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name).apply {
                        method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
                        crc = CRC32().apply { update(bytes) }.value
                    })
                    zip.write(bytes); zip.closeEntry()
                }
            }
            block(file)
        } finally { file.delete(); directory.delete() }
    }
}
