package dev.local.artistgallery.data.archive

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader

/** Reads only files copied from the user's explicit SAF selection into an isolated bundle. */
class ZipArchiveReader(mainFile: File, selectedFiles: List<File>, availableBytes: Long) : ArchiveReader {
    private val archive = ZipFile(mainFile)
    private val allowed = selectedFiles.map { it.canonicalFile }.toSet()
    private var password: CharArray? = null
    private var headers: List<FileHeader>? = null
    private var remaining = availableBytes.coerceAtLeast(0)
    private val openedAt = System.nanoTime()
    private val main = mainFile.canonicalFile

    init {
        require(mainFile.canonicalFile in allowed) { "主压缩包不在已选择的文件中" }
    }

    override fun entries(): List<ArchiveEntry> = read {
        val files = loadHeaders()
        files.mapIndexed { index, header ->
            ArchiveEntry(index, ArchivePaths.normalize(header.fileName), header.uncompressedSize, header.isDirectory, header.isEncrypted)
        }
    }

    override fun setPassword(password: CharArray) {
        this.password?.fill('\u0000')
        this.password = password.copyOf()
        archive.setPassword(this.password)
    }

    override fun extract(index: Int, output: OutputStream, checkCancelled: () -> Unit): Long = read {
        val header = loadHeaders().getOrNull(index) ?: throw ArchiveReadException(ArchiveError.CORRUPT, "压缩条目不存在。")
        ArchivePaths.normalize(header.fileName)
        if (header.isDirectory) return@read 0L
        if (header.uncompressedSize < 0 || header.uncompressedSize > MAX_ENTRY_BYTES || header.uncompressedSize > remaining) {
            throw ArchiveReadException(ArchiveError.LIMIT, "条目大小超过导入限制或可用空间。")
        }
        var count = 0L
        checkCancelled()
        archive.getInputStream(header).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkCancelled()
                if (System.nanoTime() - openedAt > 30L * 60 * 1_000_000_000) throw ArchiveReadException(ArchiveError.LIMIT, "压缩读取超过时间限制，请拆分压缩包后再导入。")
                val size = input.read(buffer)
                if (size < 0) break
                if (size > remaining || size > MAX_ENTRY_BYTES - count || size > header.uncompressedSize - count) {
                    throw ArchiveReadException(ArchiveError.LIMIT, "解压数据超过声明大小或导入限制。")
                }
                remaining -= size
                count += size
                output.write(buffer, 0, size)
            }
        }
        if (count != header.uncompressedSize) throw ArchiveReadException(ArchiveError.CORRUPT, "解压条目大小核验失败。")
        count
    }

    override fun close() {
        try { archive.close() } finally { password?.fill('\u0000'); password = null }
    }

    private fun loadHeaders(): List<FileHeader> {
        headers?.let { return it }
        checkDirectorySize()
        val loaded = archive.fileHeaders
        if (loaded.size > MAX_ENTRIES) throw ArchiveReadException(ArchiveError.LIMIT, "压缩条目数量超过导入限制。")
        archive.splitZipFiles.forEach { volume ->
            if (volume.canonicalFile !in allowed || !volume.isFile) {
                throw ArchiveReadException(ArchiveError.MISSING_VOLUME, "分卷不完整，请重新选择全部分卷。")
            }
        }
        loaded.forEach { ArchivePaths.normalize(it.fileName) }
        headers = loaded
        return loaded
    }

    /** Reject oversized directory counts before Zip4j allocates all FileHeader objects. */
    private fun checkDirectorySize() {
        RandomAccessFile(main, "r").use { file ->
            val tailSize = minOf(file.length(), 65557L).toInt()
            val tail = ByteArray(tailSize)
            file.seek(file.length() - tailSize); file.readFully(tail)
            fun word(offset: Int) = (tail[offset].toInt() and 255) or ((tail[offset + 1].toInt() and 255) shl 8)
            val end = (tailSize - 22 downTo 0).firstOrNull { offset ->
                tail[offset] == 0x50.toByte() && tail[offset + 1] == 0x4b.toByte() && word(offset + 2) == 0x0605 && offset + 22 + word(offset + 20) == tailSize
            } ?: return
            val count = word(end + 10)
            if (count != 65535) {
                if (count > MAX_ENTRIES) throw ArchiveReadException(ArchiveError.LIMIT, "压缩条目数量超过导入限制。")
                return
            }
            val locator = end - 20
            if (locator < 0 || word(locator) != 0x4b50 || word(locator + 2) != 0x0706) return
            val buffer = java.nio.ByteBuffer.wrap(tail).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val disk = buffer.getInt(locator + 4)
            val recordOffset = buffer.getLong(locator + 8)
            val finalDisk = word(end + 4)
            val volume = if (disk == finalDisk) main else File(main.parentFile, main.name.substringBeforeLast('.') + ".z" + (disk + 1).toString().padStart(2, '0')).canonicalFile
            if (volume !in allowed || !volume.isFile) throw ArchiveReadException(ArchiveError.MISSING_VOLUME, "缺少 ZIP64 目录所在分卷。")
            RandomAccessFile(volume, "r").use { directory ->
                if (recordOffset < 0 || recordOffset > directory.length() - 56) return
                val record = ByteArray(56)
                directory.seek(recordOffset); directory.readFully(record)
                val metadata = java.nio.ByteBuffer.wrap(record).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                if (metadata.getInt(0) != 0x06064b50) return
                val total = metadata.getLong(32)
                if (total < 0 || total > MAX_ENTRIES) throw ArchiveReadException(ArchiveError.LIMIT, "ZIP64 条目数量超过导入限制。")
            }
        }
    }

    private inline fun <T> read(block: () -> T): T = try {
        block()
    } catch (error: ArchiveReadException) {
        throw error
    } catch (error: ZipException) {
        val reason = if (error.type == ZipException.Type.WRONG_PASSWORD) ArchiveError.PASSWORD else ArchiveError.CORRUPT
        val message = if (reason == ArchiveError.PASSWORD) "密码错误或尚未输入密码，请重试。" else "压缩包损坏或使用了不支持的 ZIP 变体。"
        throw ArchiveReadException(reason, message, error)
    } catch (error: IOException) {
        throw ArchiveReadException(ArchiveError.IO, "读取压缩包或写入暂存文件失败。", error)
    }

    companion object {
        const val MAX_ENTRIES = 20_000
        const val MAX_ENTRY_BYTES = 2L * 1024 * 1024 * 1024
    }
}
