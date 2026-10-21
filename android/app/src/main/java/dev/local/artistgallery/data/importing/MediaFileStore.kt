package dev.local.artistgallery.data.importing

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class StagedFile(val byteSize: Long, val sha256: String)

class MediaFileStore(context: Context) {
    private val root = context.getExternalFilesDir(null)
    private val gaussianPreviews = File(context.cacheDir, "gaussian-previews-v1")
    private val contentResolver = context.contentResolver

    fun availableBytes(): Long = StatFs(requireRoot().absolutePath).availableBytes

    suspend fun writeToStaging(stagingKey: String, hashWhileWriting: Boolean = false, writer: (OutputStream) -> Unit): StagedFile = withContext(Dispatchers.IO) {
        val target = stagingFile(stagingKey)
        if (!stagingDirectory().exists() && !stagingDirectory().mkdirs()) throw IOException("无法创建导入暂存目录")
        val digest = if (hashWhileWriting) MessageDigest.getInstance("SHA-256") else null
        var written = 0L
        try {
            FileOutputStream(target).use { output ->
                BufferedOutputStream(output, BUFFER_SIZE).use { buffered ->
                    val writerOutput = if (digest == null) buffered else object : OutputStream() {
                        override fun write(value: Int) {
                            buffered.write(value)
                            digest.update(value.toByte())
                            written++
                        }
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            buffered.write(bytes, offset, length)
                            digest.update(bytes, offset, length)
                            written += length
                        }
                        override fun flush() = buffered.flush()
                        override fun close() = buffered.close()
                    }
                    writer(writerOutput)
                    buffered.flush()
                    output.fd.sync()
                }
            }
            if (digest == null) inspectStaging(stagingKey)
            else StagedFile(written, digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    suspend fun validateStagedMedia(stagingKey: String, mediaType: String) = withContext(Dispatchers.IO) {
        val source = stagingFile(stagingKey)
        if (mediaType == "VIDEO") {
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(source.absolutePath)
                if ((metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) <= 0) throw IOException("视频文件无法识别或已经损坏。")
            } finally { metadata.release() }
        } else {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("图片文件无法识别或已经损坏。")
        }
    }

    suspend fun copyToStaging(uri: Uri, stagingKey: String, expectedBytes: Long, onProgress: (Long) -> Unit): StagedFile = withContext(Dispatchers.IO) {
        val target = stagingFile(stagingKey)
        val stageDirectory = stagingDirectory()
        if (!stageDirectory.exists() && !stageDirectory.mkdirs()) throw IOException("无法创建导入暂存目录")
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        var lastReported = 0L
        try {
            val input = contentResolver.openInputStream(uri) ?: throw IOException("无法读取所选文件")
            input.use { source ->
                BufferedInputStream(source, BUFFER_SIZE).use { bufferedSource ->
                    FileOutputStream(target).use { output ->
                        BufferedOutputStream(output, BUFFER_SIZE).use { bufferedTarget ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = bufferedSource.read(buffer)
                                if (read < 0) break
                                bufferedTarget.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                copied += read
                                if (copied - lastReported >= REPORT_INTERVAL) {
                                    lastReported = copied
                                    onProgress(copied)
                                }
                            }
                            bufferedTarget.flush()
                            output.fd.sync()
                        }
                    }
                }
            }
            if (expectedBytes >= 0 && copied != expectedBytes) throw IOException("来源文件大小与复制结果不一致")
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
        onProgress(copied)
        StagedFile(copied, digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }

    suspend fun matchesOriginal(stagingKey: String, storageKey: String): Boolean = withContext(Dispatchers.IO) {
        val staged = stagingFile(stagingKey)
        val original = originalFile(storageKey)
        if (!staged.isFile || !original.isFile || staged.length() != original.length()) return@withContext false
        BufferedInputStream(FileInputStream(staged), BUFFER_SIZE).use { left ->
            BufferedInputStream(FileInputStream(original), BUFFER_SIZE).use { right ->
                val a = ByteArray(BUFFER_SIZE)
                val b = ByteArray(BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val an = left.read(a)
                    val bn = right.read(b)
                    if (an != bn) return@withContext false
                    if (an < 0) return@withContext true
                    for (i in 0 until an) if (a[i] != b[i]) return@withContext false
                }
                @Suppress("UNREACHABLE_CODE") false
            }
        }
    }

    suspend fun inspectStaging(stagingKey: String): StagedFile = inspect(stagingFile(stagingKey))

    suspend fun inspectOriginal(storageKey: String): StagedFile = inspect(originalFile(storageKey))

    fun hasStaging(stagingKey: String): Boolean = stagingFile(stagingKey).isFile

    fun hasOriginal(storageKey: String): Boolean = originalFile(storageKey).isFile

    suspend fun promote(stagingKey: String, storageKey: String) = withContext(Dispatchers.IO) {
        val originalDirectory = originalsDirectory()
        if (!originalDirectory.exists() && !originalDirectory.mkdirs()) throw IOException("无法创建图库原图目录")
        val source = stagingFile(stagingKey)
        val target = originalFile(storageKey)
        if (!source.isFile) throw IOException("导入暂存文件不存在")
        if (target.exists()) throw IOException("目标原图已存在")
        if (!source.renameTo(target)) throw IOException("无法提交图库原图文件")
    }

    suspend fun deleteStaging(stagingKey: String) = withContext(Dispatchers.IO) {
        val file = stagingFile(stagingKey)
        !file.exists() || file.delete()
    }

    suspend fun deleteOriginal(storageKey: String) = withContext(Dispatchers.IO) {
        val file = originalFile(storageKey)
        val deleted = !file.exists() || file.delete()
        if (deleted) {
            try { gaussianPreviewFile(storageKey).delete() } catch (_: SecurityException) { }
        }
        deleted
    }

    fun originalFile(storageKey: String): File = File(originalsDirectory(), uuid(storageKey))

    fun gaussianPreviewFile(storageKey: String): File = File(gaussianPreviews, uuid(storageKey) + ".png")

    fun stagingFile(stagingKey: String): File = File(stagingDirectory(), uuid(stagingKey) + ".part")

    private fun requireRoot(): File = root ?: throw IOException("应用专属存储暂不可用")

    private fun originalsDirectory(): File = File(requireRoot(), "originals")

    private fun stagingDirectory(): File = File(requireRoot(), ".artistgallery-imports")

    private suspend fun inspect(file: File): StagedFile = withContext(Dispatchers.IO) {
        if (!file.isFile) throw IOException("媒体文件不存在")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        BufferedInputStream(FileInputStream(file), BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                size += read
            }
        }
        StagedFile(size, digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }

    private fun uuid(value: String): String = try {
        UUID.fromString(value).toString()
    } catch (_: IllegalArgumentException) {
        throw IOException("图库文件标识无效")
    }

    private companion object {
        const val BUFFER_SIZE = 128 * 1024
        const val REPORT_INTERVAL = 1024 * 1024
    }
}
