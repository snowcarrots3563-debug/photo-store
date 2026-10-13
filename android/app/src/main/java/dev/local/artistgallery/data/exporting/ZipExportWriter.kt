package dev.local.artistgallery.data.exporting

import dev.local.artistgallery.data.backup.BackupValidation
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.Zip4jConfig
import net.lingala.zip4j.model.ZipModel
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionMethod

object ZipExportWriter {
    private const val BUFFER_SIZE = 128 * 1024

    suspend fun write(
        plan: ExportPlan,
        output: OutputStream,
        manifestBytes: ByteArray?,
        originalFile: (String) -> File,
        onProgress: (String) -> Unit,
    ): ExportOutcome {
        validate(plan, manifestBytes)
        val context = currentCoroutineContext()
        context.ensureActive()
        val assets = plan.assets.associateBy { it.id }
        val digest = MessageDigest.getInstance("SHA-256")
        val counted = DigestOutput(output, digest)
        val buffer = ByteArray(BUFFER_SIZE)
        val fileCount = plan.fileCount
        var completed = 0
        var lastProgress = 0L
        fun progress(path: String, force: Boolean = false) {
            val now = System.nanoTime()
            if (force || lastProgress == 0L || now - lastProgress >= 100_000_000L) {
                onProgress("$completed / $fileCount · $path")
                lastProgress = now
            }
        }
        ZipOutputStream(
            BufferedOutputStream(counted, BUFFER_SIZE), null,
            Zip4jConfig(Charsets.UTF_8, BUFFER_SIZE, true), ZipModel().apply { isZip64Format = true },
        ).use { zip ->
            plan.entries.forEach { entry ->
                context.ensureActive()
                val asset = entry.assetId?.let { assets.getValue(it) }
                val file = asset?.let { originalFile(plan.storageKeys.getValue(it.id)) }
                if (asset != null && file != null && (!file.isFile || file.length() != asset.byteSize)) {
                    throw IOException("原图缺失或大小已改变：${asset.originalName}")
                }
                zip.putNextEntry(parameters(entry.path, asset?.byteSize ?: 0L, asset?.createdAt ?: 0L))
                if (asset != null && file != null) {
                    val sourceDigest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    file.inputStream().buffered(BUFFER_SIZE).use { input ->
                        while (true) {
                            context.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            size = Math.addExact(size, read.toLong())
                            if (size > asset.byteSize) throw IOException("原图大小已改变：${asset.originalName}")
                            sourceDigest.update(buffer, 0, read)
                            zip.write(buffer, 0, read)
                            progress(entry.path)
                        }
                    }
                    if (size != asset.byteSize || hex(sourceDigest.digest()) != asset.sha256) {
                        throw IOException("原图校验失败：${asset.originalName}")
                    }
                    completed++
                }
                zip.closeEntry()
            }
            if (manifestBytes != null) {
                context.ensureActive()
                zip.putNextEntry(parameters(BackupValidation.MANIFEST_NAME, manifestBytes.size.toLong(), 0L))
                var offset = 0
                while (offset < manifestBytes.size) {
                    context.ensureActive()
                    val size = minOf(BUFFER_SIZE, manifestBytes.size - offset)
                    zip.write(manifestBytes, offset, size)
                    offset += size
                }
                zip.closeEntry()
            }
            context.ensureActive()
        }
        context.ensureActive()
        progress("完成", true)
        return ExportOutcome(completed, counted.size, hex(digest.digest()))
    }

    private fun parameters(path: String, size: Long, createdAt: Long) = ZipParameters().apply {
        fileNameInZip = path
        entrySize = size
        compressionMethod = CompressionMethod.STORE
        lastModifiedFileTime = createdAt
    }

    private fun validate(plan: ExportPlan, manifestBytes: ByteArray?) {
        fun check(condition: Boolean, message: String) { if (!condition) throw IOException(message) }
        check((plan.manifest == null) == (manifestBytes == null), "备份清单缺失或导出类型错误。")
        if (manifestBytes != null) check(manifestBytes.isNotEmpty() && manifestBytes.size <= BackupValidation.MAX_MANIFEST_BYTES, "备份清单大小无效。")
        val assets = plan.assets.associateBy { it.id }
        check(assets.size == plan.assets.size, "导出图片标识重复。")
        val paths = HashMap<String, Boolean>()
        fun register(entryPath: String): Boolean {
            val directory = entryPath.endsWith('/')
            val path = entryPath.removeSuffix("/")
            check(path.isNotBlank() && path.toByteArray(Charsets.UTF_8).size <= 65535, "导出路径无效。")
            path.split('/').forEach { part ->
                check(part.isNotBlank() && part !in setOf(".", "..") && part == part.trimEnd('.', ' ') &&
                    part.toByteArray(Charsets.UTF_8).size <= 255 && part.none { it.isISOControl() || it in "\\:*?\"<>|" } &&
                    !part.substringBefore('.').matches(Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])")), "导出路径不安全。")
            }
            val canonical = Normalizer.normalize(path, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            check(paths.put(canonical, directory) == null, "导出路径重复。")
            return directory
        }
        plan.entries.forEach { entry ->
            val directory = register(entry.path)
            check(directory == (entry.assetId == null), "导出文件类型错误。")
            entry.assetId?.let { id ->
                val asset = assets[id]
                check(asset != null && id in plan.storageKeys && asset.byteSize > 0 && asset.sha256.matches(Regex("[0-9a-f]{64}")), "导出原图信息无效。")
            }
        }
        if (manifestBytes != null) register(BackupValidation.MANIFEST_NAME)
        paths.keys.forEach { path ->
            var parent = path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                check(paths[parent] != false, "导出文件和目录冲突。")
                parent = parent.substringBeforeLast('/', "")
            }
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private class DigestOutput(private val output: OutputStream, private val digest: MessageDigest) : OutputStream() {
        var size = 0L
            private set
        override fun write(value: Int) {
            output.write(value)
            digest.update(value.toByte())
            size = Math.addExact(size, 1L)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            output.write(bytes, offset, length)
            digest.update(bytes, offset, length)
            size = Math.addExact(size, length.toLong())
        }
        override fun flush() = output.flush()
        override fun close() = output.close()
    }
}
