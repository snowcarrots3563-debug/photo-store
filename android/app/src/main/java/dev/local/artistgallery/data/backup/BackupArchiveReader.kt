package dev.local.artistgallery.data.backup

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.FileHeader

/** Reads fixed backup entries into caller-owned files, never ZIP-supplied paths. */
internal class BackupArchiveReader(file: File) : Closeable {
    private val archive = ZipFile(file)
    private val headers: Map<String, FileHeader>

    init {
        try {
            val expectedCount = checkDirectory(file)
            if (archive.isSplitArchive) throw IOException("不支持分卷备份，请选择完整的单个 ZIP 备份。")
            val entries = archive.fileHeaders
            if (entries.size != expectedCount || entries.any { it.isEncrypted || it.diskNumberStart != 0 || it.isDirectory && (it.fileName != "media/" || it.uncompressedSize != 0L) }) {
                throw IOException("备份包含加密、分卷或无效条目。")
            }
            if (entries.map { it.fileName }.toSet().size != entries.size) throw IOException("备份包含重复文件。")
            if (entries.any { it.fileName != BackupValidation.MANIFEST_NAME && it.fileName != "media/" && !it.fileName.matches(MEDIA_PATH) }) {
                throw IOException("备份包含未知或不安全的文件路径。")
            }
            if (entries.any { it.fileName == "media/" && !it.isDirectory }) throw IOException("备份媒体目录无效。")
            headers = entries.filterNot { it.isDirectory }.associateBy { it.fileName }
            val manifest = headers[BackupValidation.MANIFEST_NAME] ?: throw IOException("所选 ZIP 不是完整图库备份。")
            if (manifest.uncompressedSize !in 1L..BackupValidation.MAX_MANIFEST_BYTES.toLong()) throw IOException("备份清单大小无效。")
        } catch (error: Throwable) {
            archive.close()
            throw error
        }
    }

    fun manifestBytes(checkCancelled: () -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        extract(BackupValidation.MANIFEST_NAME, bytes, checkCancelled)
        return bytes.toByteArray()
    }

    fun validateEntries(manifest: BackupManifest) {
        val expected = manifest.assets.associate { it.entryPath to it.byteSize }
        if (headers.keys != expected.keys + BackupValidation.MANIFEST_NAME) throw IOException("备份原图与清单不一致，存在缺失或多余文件。")
        expected.forEach { (path, size) ->
            if (headers.getValue(path).uncompressedSize != size) throw IOException("备份原图大小与清单不一致。")
        }
    }

    fun extract(path: String, output: OutputStream, checkCancelled: () -> Unit) {
        val header = headers[path] ?: throw IOException("备份文件缺失。")
        if (header.uncompressedSize < 0 || header.compressedSize < 0) throw IOException("备份文件大小无效。")
        var copied = 0L
        archive.getInputStream(header).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkCancelled()
                val read = input.read(buffer)
                if (read < 0) break
                if (read.toLong() > header.uncompressedSize - copied) throw IOException("备份文件超过声明大小。")
                output.write(buffer, 0, read)
                copied += read
            }
        }
        if (copied != header.uncompressedSize) throw IOException("备份文件不完整。")
    }

    override fun close() = archive.close()

    companion object {
        private val MEDIA_PATH = Regex("media/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private const val MAX_DIRECTORY_BYTES = 32L * 1024 * 1024
        private const val MAX_ZIP64_RECORD_SIZE = 4096L

        /** Bound the central directory before Zip4j allocates its file-header objects. */
        internal fun checkDirectory(file: File): Int = RandomAccessFile(file, "r").use { source ->
            // EOCD + maximum comment + the preceding ZIP64 locator.
            val tailSize = minOf(source.length(), 65_577L).toInt()
            if (tailSize < 22) throw IOException("备份 ZIP 已损坏。")
            val tail = ByteArray(tailSize)
            source.seek(source.length() - tailSize)
            source.readFully(tail)
            val data = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
            fun word(offset: Int) = data.getShort(offset).toInt() and 65_535
            val end = (tailSize - 22 downTo 0).firstOrNull { offset ->
                data.getInt(offset) == 0x06054b50 && offset + 22 + word(offset + 20) == tailSize
            } ?: throw IOException("备份 ZIP 目录已损坏。")
            if (word(end + 4) != 0 || word(end + 6) != 0 || word(end + 8) != word(end + 10)) {
                throw IOException("不支持分卷备份。")
            }
            var count = word(end + 10).toLong()
            var size = data.getInt(end + 12).toLong() and 0xffffffffL
            var offset = data.getInt(end + 16).toLong() and 0xffffffffL
            val endPosition = source.length() - tailSize + end
            val locator = end - 20
            val hasLocator = locator >= 0 && data.getInt(locator) == 0x07064b50
            val needsZip64 = count == 65_535L || size == 0xffffffffL || offset == 0xffffffffL
            var directoryEnd = endPosition
            if (hasLocator || needsZip64) {
                if (!hasLocator || data.getInt(locator + 4) != 0 || data.getInt(locator + 16) != 1) {
                    throw IOException("ZIP64 备份目录无效。")
                }
                val recordOffset = data.getLong(locator + 8)
                val locatorPosition = endPosition - 20
                if (recordOffset < 0 || recordOffset > locatorPosition - 56) throw IOException("ZIP64 备份目录无效。")
                val record = ByteArray(56)
                source.seek(recordOffset)
                source.readFully(record)
                val extended = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
                val recordSize = extended.getLong(4)
                if (recordSize !in 44L..MAX_ZIP64_RECORD_SIZE || recordSize > locatorPosition - recordOffset - 12) {
                    throw IOException("ZIP64 目录记录过大、不完整或与定位记录重叠。")
                }
                if (extended.getInt(0) != 0x06064b50 || extended.getInt(16) != 0 || extended.getInt(20) != 0 || extended.getLong(24) != extended.getLong(32)) {
                    throw IOException("ZIP64 分卷或目录无效。")
                }
                val actualCount = extended.getLong(32)
                val actualSize = extended.getLong(40)
                val actualOffset = extended.getLong(48)
                if (count != 65_535L && count != actualCount || size != 0xffffffffL && size != actualSize || offset != 0xffffffffL && offset != actualOffset) {
                    throw IOException("ZIP64 目录与普通目录信息不一致。")
                }
                count = actualCount
                size = actualSize
                offset = actualOffset
                directoryEnd = recordOffset
            }
            if (count !in 1L..(BackupValidation.MAX_RECORDS + 2).toLong() || size !in 1L..MAX_DIRECTORY_BYTES || offset < 0 || offset > directoryEnd || size > directoryEnd - offset) {
                throw IOException("备份 ZIP 目录超出限制或已损坏。")
            }
            count.toInt()
        }
    }
}
