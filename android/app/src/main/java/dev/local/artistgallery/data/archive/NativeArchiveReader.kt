package dev.local.artistgallery.data.archive

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import net.sf.sevenzipjbinding.*
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream

/** RAR/7z streams are restricted to the files explicitly selected for this bundle. */
class NativeArchiveReader(
    private val mainFile: File,
    selectedFiles: List<File>,
    private var remaining: Long,
    private val checkCancelled: () -> Unit = {},
    private val expectedFormat: String? = null,
) : ArchiveReader {
    private val allowed = selectedFiles.associateBy { it.name.lowercase() }
    private val streams = mutableListOf<RandomAccessFileInStream>()
    private var input: IInStream? = null
    private var archive: IInArchive? = null
    private var password: CharArray? = null
    private var callbackFailure: Throwable? = null
    private var missing: String? = null
    private var started = 0L

    private fun checkpoint() {
        checkCancelled()
        if (System.nanoTime() - started > 30L * 60 * 1_000_000_000) throw ArchiveReadException(ArchiveError.LIMIT, "压缩读取超过时间限制，请拆分压缩包后再导入。")
    }

    private val callback = object : IArchiveOpenCallback, IArchiveOpenVolumeCallback, ICryptoGetTextPassword {
        override fun setTotal(files: Long?, bytes: Long?) { guarded { checkpoint() } }
        override fun setCompleted(files: Long?, bytes: Long?) { guarded { checkpoint() } }
        override fun getProperty(propID: PropID): Any? = when (propID) { PropID.NAME -> mainFile.name; PropID.SIZE -> mainFile.length(); else -> null }
        override fun getStream(filename: String): IInStream? = guarded {
            checkpoint()
            ArchiveBundle.safeName(filename)
            val file = allowed[filename.lowercase()]
            if (file == null || !file.isFile) { missing = filename; null }
            else RandomAccessFileInStream(RandomAccessFile(file, "r")).also { streams.add(it) }
        }
        override fun cryptoGetTextPassword(): String = guarded {
            password?.concatToString() ?: throw ArchiveReadException(ArchiveError.PASSWORD, "请输入压缩包密码。")
        }
    }

    override fun setPassword(password: CharArray) {
        closeArchive()
        this.password?.fill('\u0000')
        this.password = password.copyOf()
    }

    private fun open(): IInArchive {
        archive?.let { return it }
        started = System.nanoTime()
        synchronized(SevenZip::class.java) {
            if (!SevenZip.isInitializedSuccessfully()) {
                System.loadLibrary("7-Zip-JBinding")
                SevenZip.initLoadedLibraries()
            }
        }
        val stream = if (mainFile.name.endsWith(".001", true)) SelectedVolumesInStream(allowed.values.toList()) { guarded { checkpoint() } }
        else callback.getStream(mainFile.name) ?: throw ArchiveReadException(ArchiveError.MISSING_VOLUME, "主压缩包不可用。")
        input = stream
        // The stream already joins numeric volumes; bypass the native SPLIT wrapper selected by .001 names.
        val format = if (mainFile.name.endsWith(".001", true)) when (expectedFormat) {
            "ZIP" -> ArchiveFormat.ZIP
            "7z" -> ArchiveFormat.SEVEN_ZIP
            else -> null
        } else null
        return SevenZip.openInArchive(format, stream, callback).also {
            val formats = when (expectedFormat) { "ZIP" -> setOf("ZIP"); "RAR" -> setOf("RAR", "RAR5"); "7z" -> setOf("SEVEN_ZIP"); else -> setOf("RAR", "RAR5", "SEVEN_ZIP") }
            if (it.archiveFormat.name !in formats) {
                it.close()
                throw ArchiveReadException(ArchiveError.CORRUPT, "文件内容与所选压缩格式不一致。")
            }
            archive = it
        }
    }

    override fun entries(): List<ArchiveEntry> = read {
        val current = open()
        if (current.numberOfItems > ZipArchiveReader.MAX_ENTRIES) throw ArchiveReadException(ArchiveError.LIMIT, "压缩条目数量超过导入限制。")
        val entries = (0 until current.numberOfItems).map { index ->
            checkpoint()
            val dictionary = (current.getProperty(index, PropID.DICTIONARY_SIZE) as? Number)?.toLong() ?: 0
            if (dictionary > 256L * 1024 * 1024) throw ArchiveReadException(ArchiveError.LIMIT, "压缩字典超过 256 MiB 限制。")
            if (current.getProperty(index, PropID.SYM_LINK) != null || current.getProperty(index, PropID.HARD_LINK) != null) throw ArchiveReadException(ArchiveError.UNSAFE_PATH, "压缩包包含链接条目，不予导入。")
            ArchiveEntry(index, ArchivePaths.normalize(current.getStringProperty(index, PropID.PATH)),
                (current.getProperty(index, PropID.SIZE) as? Number)?.toLong() ?: 0,
                current.getProperty(index, PropID.IS_FOLDER) == true, current.getProperty(index, PropID.ENCRYPTED) == true)
        }
        if (missing != null && current.archiveFormat.name in setOf("RAR", "RAR5")) throw ArchiveReadException(ArchiveError.MISSING_VOLUME, "缺少分卷：$missing")
        entries
    }

    override fun extract(index: Int, output: OutputStream, checkCancelled: () -> Unit): Long = read {
        val current = open()
        val expected = (current.getProperty(index, PropID.SIZE) as? Number)?.toLong() ?: throw ArchiveReadException(ArchiveError.CORRUPT, "条目大小不可用。")
        if (expected < 0 || expected > remaining || expected > ZipArchiveReader.MAX_ENTRY_BYTES) throw ArchiveReadException(ArchiveError.LIMIT, "条目大小超过限制或可用空间。")
        var count = 0L
        val writer = ISequentialOutStream { bytes -> guarded {
            checkpoint()
            checkCancelled()
            if (bytes.size > remaining || bytes.size > expected - count) throw ArchiveReadException(ArchiveError.LIMIT, "解压数据超过声明大小或可用空间。")
            output.write(bytes)
            count += bytes.size
            remaining -= bytes.size
            bytes.size
        } }
        var result: ExtractOperationResult? = null
        current.extract(intArrayOf(index), false, object : IArchiveExtractCallback, ICryptoGetTextPassword {
            override fun setTotal(total: Long) { guarded { checkpoint(); checkCancelled() } }
            override fun setCompleted(complete: Long) { guarded { checkpoint(); checkCancelled() } }
            override fun getStream(itemIndex: Int, mode: ExtractAskMode): ISequentialOutStream? = if (itemIndex == index && mode == ExtractAskMode.EXTRACT) writer else null
            override fun prepareOperation(mode: ExtractAskMode) { guarded { checkpoint(); checkCancelled() } }
            override fun setOperationResult(value: ExtractOperationResult) { result = value }
            override fun cryptoGetTextPassword(): String = callback.cryptoGetTextPassword()
        })
        if (result != ExtractOperationResult.OK) {
            val encrypted = current.getProperty(index, PropID.ENCRYPTED) == true
            throw ArchiveReadException(if (missing != null) ArchiveError.MISSING_VOLUME else if (encrypted) ArchiveError.PASSWORD else ArchiveError.CORRUPT,
                if (missing != null) "缺少分卷：$missing" else if (encrypted) "密码错误或加密条目损坏，请重试密码或重新选择文件。" else "条目损坏或压缩方式不受支持。")
        }
        if (count != expected) throw ArchiveReadException(ArchiveError.CORRUPT, "条目大小核验失败。")
        count
    }

    private fun <T> guarded(block: () -> T): T = try { block() } catch (error: Throwable) {
        callbackFailure = error
        throw SevenZipException("Archive callback failed")
    }

    private fun <T> read(block: () -> T): T {
        callbackFailure = null
        missing = null
        return try { block() } catch (error: SevenZipException) {
            callbackFailure?.let { throw it }
            throw ArchiveReadException(if (missing != null || mainFile.name.endsWith(".001", true) && password == null) ArchiveError.MISSING_VOLUME else if (password != null) ArchiveError.PASSWORD else ArchiveError.CORRUPT,
                if (missing != null) "缺少分卷：$missing" else "无法打开压缩包；请检查密码、分卷及文件完整性。")
        } catch (error: UnsatisfiedLinkError) {
            throw ArchiveReadException(ArchiveError.IO, "当前设备无法加载压缩读取库。", error)
        }
    }

    private fun closeArchive() {
        try { archive?.close() } finally {
            archive = null
            try { input?.close() } finally { input = null; streams.forEach { it.close() }; streams.clear() }
        }
    }

    override fun close() { try { closeArchive() } finally { password?.fill('\u0000'); password = null } }
}
