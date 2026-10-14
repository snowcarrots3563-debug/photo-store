package dev.local.artistgallery.data.archive

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Adds deterministic PNG pages to an archive without changing ordinary media extraction. */
class PdfArchiveReader(
    private val delegate: ArchiveReader,
    cacheDirectory: File,
    private val availableBytes: () -> Long,
    private val checkCancelled: () -> Unit,
    private val skippedPdfIndices: Set<Int> = emptySet(),
    private val onProgress: (String) -> Unit = {},
) : ArchiveReader {
    private val root by lazy { cacheDirectory.canonicalFile }
    private var resolved: List<ArchiveEntry>? = null
    private val virtualPages = mutableMapOf<Int, CachedPage>()

    override fun entries(): List<ArchiveEntry> {
        resolved?.let { return it }
        checkCancelled()
        val original = delegate.entries().map { entry ->
            if (entry.index in skippedPdfIndices && !entry.isDirectory && entry.path.endsWith(".pdf", true))
                entry.copy(skipReason = "此任务已跳过 PDF；重新导入压缩包可按页转图。")
            else entry
        }
        if (original.size > ZipArchiveReader.MAX_ENTRIES) limit("压缩条目数量超过导入限制。")
        original.forEach { ArchivePaths.normalize(it.path) }
        if (original.none { !it.isDirectory && it.path.endsWith(".pdf", true) && it.skipReason == null }) {
            resolved = original
            return original
        }
        val occupied = mutableSetOf<String>()
        original.forEach { entry ->
            val path = ArchivePaths.normalize(entry.path)
            occupied.add(path)
            var directory = path.substringBeforeLast('/', "")
            while (directory.isNotEmpty()) {
                occupied.add(directory)
                directory = directory.substringBeforeLast('/', "")
            }
        }
        val maximum = original.maxOfOrNull { it.index } ?: -1
        if (maximum < 0 || maximum > Int.MAX_VALUE - ZipArchiveReader.MAX_ENTRIES || original.map { it.index }.toSet().size != original.size) {
            throw ArchiveReadException(ArchiveError.CORRUPT, "压缩条目索引无效。")
        }
        var nextIndex = maximum + 1
        val output = mutableListOf<ArchiveEntry>()
        virtualPages.clear()
        try {
            for (entry in original) {
                checkCancelled()
                if (entry.isDirectory || !entry.path.endsWith(".pdf", true)) {
                    output.add(entry)
                    continue
                }
                val path = ArchivePaths.normalize(entry.path)
                val parent = path.substringBeforeLast('/', "")
                val name = path.substringAfterLast('/').dropLast(4).ifBlank { "PDF" }
                val base = if (parent.isEmpty()) name else "$parent/$name"
                var directory = base
                var suffix = 2
                while (directory in occupied) directory = "$base (${suffix++})"
                occupied.add(directory)
                if (entry.skipReason != null) {
                    output.add(entry)
                    continue
                }
                onProgress("正在准备 PDF · ${path.substringAfterLast('/')}")
                val pages = convert(entry, ZipArchiveReader.MAX_ENTRIES - original.size - virtualPages.size)
                if (pages == null) {
                    output.add(entry.copy(skipReason = failedPdfReason))
                    continue
                }
                pages.forEachIndexed { pageIndex, page ->
                    val index = nextIndex++
                    virtualPages[index] = page
                    output.add(ArchiveEntry(index, "$directory/第${(pageIndex + 1).toString().padStart(4, '0')}页.png", page.length, false, entry.isEncrypted))
                }
            }
        } catch (error: Exception) {
            virtualPages.clear()
            throw error
        }
        resolved = output
        return output
    }

    override fun setPassword(password: CharArray) {
        delegate.setPassword(password)
        resolved = null
        virtualPages.clear()
    }

    fun isOrdinaryZipEntry(index: Int): Boolean {
        if (delegate !is ZipArchiveReader) return false
        entries()
        return index !in virtualPages
    }

    override fun extract(index: Int, output: OutputStream, checkCancelled: () -> Unit): Long {
        entries()
        val page = virtualPages[index] ?: return delegate.extract(index, output, checkCancelled)
        if (!page.file.isFile || page.file.length() != page.length) throw ArchiveReadException(ArchiveError.CORRUPT, "PDF 页面缓存大小核验失败。")
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        page.file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                this.checkCancelled()
                checkCancelled()
                val length = input.read(buffer)
                if (length < 0) break
                if (length.toLong() > page.length - count) throw ArchiveReadException(ArchiveError.CORRUPT, "PDF 页面缓存大小核验失败。")
                requireSpace(length)
                output.write(buffer, 0, length)
                digest.update(buffer, 0, length)
                count += length
            }
        }
        if (count != page.length || hex(digest.digest()) != page.sha256) throw ArchiveReadException(ArchiveError.CORRUPT, "PDF 页面缓存内容核验失败。")
        return count
    }

    override fun close() {
        try { delegate.close() } finally { resolved = null; virtualPages.clear() }
    }

    private data class CachedPage(val file: File, val length: Long, val sha256: String)
    private data class Cache(val sourceSha256: String, val pages: List<CachedPage>)
    private class PdfConversionException(message: String, cause: Throwable? = null) : IOException(message, cause)
    private var failedPdfReason = "PDF 页面未能转换。"

    private fun convert(entry: ArchiveEntry, pageLimit: Int): List<CachedPage>? {
        if (entry.byteSize < 0 || entry.byteSize > ZipArchiveReader.MAX_ENTRY_BYTES) limit("PDF 条目超过 2 GiB 限制。")
        if (root.exists() && !root.isDirectory || !root.exists() && !root.mkdirs()) throw ArchiveReadException(ArchiveError.IO, "无法创建 PDF 页面暂存目录。")
        val folder = child(root, "pdf-${entry.index}")
        if (folder.exists() && !folder.isDirectory || !folder.exists() && !folder.mkdir()) throw ArchiveReadException(ArchiveError.IO, "无法创建 PDF 页面暂存目录。")
        var hasCompleteManifest = false
        try {
            val cache = readManifest(entry, folder)
            hasCompleteManifest = cache != null
            val source = child(folder, "source.pdf")
            // The task's original volumes are immutable. Encrypted entries still authenticate on every fresh reader.
            val sourceSha = if (!entry.isEncrypted && cache != null && source.isFile && source.length() == entry.byteSize && sha256(source) == cache.sourceSha256) {
                cache.sourceSha256
            } else extractSource(entry, source)
            if (cache != null && cache.sourceSha256 == sourceSha && cache.pages.all { it.file.isFile && it.file.length() == it.length && sha256(it.file) == it.sha256 }) {
                if (cache.pages.size > pageLimit) limit("PDF 页面与压缩条目总数超过 20000 条限制。")
                return cache.pages
            }
            folder.listFiles()?.filter { it.name != "source.pdf" }?.forEach(::removeTree)
            val pages = render(entry, source, sourceSha, folder, pageLimit)
            writeManifest(entry, sourceSha, pages, folder)
            return pages
        } catch (cancelled: CancellationException) {
            runCatching { removeTree(folder) }
            throw cancelled
        } catch (error: PdfConversionException) {
            removeTree(folder)
            failedPdfReason = error.message ?: "PDF 页面未能转换。"
            return null
        } catch (error: ArchiveReadException) {
            if (!hasCompleteManifest || error.reason !in setOf(ArchiveError.PASSWORD, ArchiveError.MISSING_VOLUME)) removeTree(folder)
            throw error
        } catch (error: IOException) {
            removeTree(folder)
            throw ArchiveReadException(ArchiveError.IO, "读取或保存 PDF 页面失败。", error)
        }
    }

    private fun extractSource(entry: ArchiveEntry, source: File): String {
        requireSpace(entry.byteSize)
        val part = child(requireNotNull(source.parentFile), "source.pdf.partial")
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        try {
            FileOutputStream(part).use { file ->
                val output = object : OutputStream() {
                    override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        checkCancelled()
                        if (length.toLong() > entry.byteSize - count) limit("PDF 解压大小超过声明大小。")
                        requireSpace(length)
                        file.write(bytes, offset, length)
                        digest.update(bytes, offset, length)
                        count += length
                    }
                }
                val extracted = delegate.extract(entry.index, output, checkCancelled)
                if (extracted != count || count != entry.byteSize) throw ArchiveReadException(ArchiveError.CORRUPT, "PDF 解压大小核验失败。")
                file.fd.sync()
            }
            if (source.exists() && !source.delete() || !part.renameTo(source)) throw IOException("无法保存 PDF 暂存文件。")
            return hex(digest.digest())
        } finally { if (part.exists()) removeTree(part) }
    }

    private fun render(entry: ArchiveEntry, source: File, sourceSha: String, folder: File, pageLimit: Int): List<CachedPage> {
        val descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        descriptor.use {
            val renderer = try {
                PdfRenderer(descriptor)
            } catch (error: SecurityException) {
                throw PdfConversionException("PDF 已加密，无法转换页面。", error)
            } catch (error: IOException) {
                throw PdfConversionException("PDF 文件损坏或格式不受支持，页面未能转换。", error)
            } catch (error: IllegalArgumentException) {
                throw PdfConversionException("PDF 文件损坏或格式不受支持，页面未能转换。", error)
            }
            renderer.use {
                if (renderer.pageCount > pageLimit) limit("PDF 页面与压缩条目总数超过 20000 条限制。")
                if (renderer.pageCount == 0) throw PdfConversionException("PDF 没有可转换的页面。")
                return (0 until renderer.pageCount).map { index ->
                    checkCancelled()
                    onProgress("正在转换 PDF · ${entry.path.substringAfterLast('/')} · ${index + 1} / ${renderer.pageCount}")
                    val page = try { renderer.openPage(index) } catch (error: RuntimeException) {
                        if (error is CancellationException) throw error
                        throw PdfConversionException("PDF 第 ${index + 1} 页损坏，未导入该 PDF。", error)
                    }
                    page.use {
                        if (page.width <= 0 || page.height <= 0) throw PdfConversionException("PDF 页面尺寸无效。")
                        val scale = LONG_EDGE.toDouble() / maxOf(page.width, page.height)
                        val width = (page.width * scale).roundToInt().coerceIn(1, LONG_EDGE)
                        val height = (page.height * scale).roundToInt().coerceIn(1, LONG_EDGE)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            try { page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                            catch (error: RuntimeException) {
                                if (error is CancellationException) throw error
                                throw PdfConversionException("PDF 第 ${index + 1} 页无法转换，未导入该 PDF。", error)
                            }
                            checkCancelled()
                            val target = child(folder, pageName(index))
                            val part = child(folder, pageName(index) + ".partial")
                            FileOutputStream(part).use { file ->
                                val output = object : OutputStream() {
                                    override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
                                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                                        checkCancelled()
                                        requireSpace(length)
                                        file.write(bytes, offset, length)
                                    }
                                }
                                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("无法保存 PDF 页面 PNG。")
                                file.fd.sync()
                            }
                            PdfPagePng.stamp(part, sourceSha, index + 1, checkCancelled) { requireSpace(it) }
                            if (!part.renameTo(target)) throw IOException("无法保存 PDF 页面 PNG。")
                            CachedPage(target, target.length(), sha256(target))
                        } finally { bitmap.recycle() }
                    }
                }
            }
        }
    }

    private fun readManifest(entry: ArchiveEntry, folder: File): Cache? {
        val manifest = child(folder, "manifest.json")
        if (!manifest.isFile) return null
        return try {
            checkCancelled()
            val json = JSONObject(manifest.readText())
            if (json.getInt("rendererVersion") != RENDERER_VERSION || json.getInt("sourceIndex") != entry.index ||
                json.getString("sourcePath") != entry.path || json.getLong("sourceLength") != entry.byteSize) return null
            val sourceSha = json.getString("sourceSha256")
            if (!isHash(sourceSha)) return null
            val pages = json.getJSONArray("pages")
            if (pages.length() == 0 || pages.length() > ZipArchiveReader.MAX_ENTRIES) return null
            Cache(sourceSha, (0 until pages.length()).map { index ->
                val item = pages.getJSONObject(index)
                val name = item.getString("name")
                val length = item.getLong("length")
                val sha = item.getString("sha256")
                if (name != pageName(index) || length < PdfPagePng.MIN_BYTES || length > ZipArchiveReader.MAX_ENTRY_BYTES || !isHash(sha)) return null
                CachedPage(child(folder, name), length, sha)
            })
        } catch (error: ArchiveReadException) { throw error }
        catch (_: JSONException) { null } catch (_: IOException) { null }
    }

    private fun writeManifest(entry: ArchiveEntry, sourceSha: String, pages: List<CachedPage>, folder: File) {
        val json = JSONObject().put("rendererVersion", RENDERER_VERSION).put("sourceIndex", entry.index)
            .put("sourcePath", entry.path).put("sourceLength", entry.byteSize).put("sourceSha256", sourceSha)
            .put("pages", JSONArray(pages.map { JSONObject().put("name", it.file.name).put("length", it.length).put("sha256", it.sha256) }))
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        requireSpace(bytes.size)
        checkCancelled()
        val part = child(folder, "manifest.json.partial")
        FileOutputStream(part).use { it.write(bytes); it.fd.sync() }
        if (!part.renameTo(child(folder, "manifest.json"))) throw IOException("无法保存 PDF 页面缓存记录。")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }

    private fun child(parent: File, name: String): File {
        val file = File(parent, name).canonicalFile
        if (file.parentFile != parent.canonicalFile || !file.toPath().startsWith(root.toPath()) || file == root) {
            throw ArchiveReadException(ArchiveError.UNSAFE_PATH, "PDF 页面暂存路径不安全。")
        }
        return file
    }

    private fun removeTree(file: File) {
        val canonical = file.canonicalFile
        if (canonical == root || !canonical.toPath().startsWith(root.toPath())) throw ArchiveReadException(ArchiveError.UNSAFE_PATH, "PDF 页面暂存清理路径不安全。")
        if (canonical.isDirectory) canonical.listFiles()?.forEach(::removeTree)
        if (canonical.exists() && !canonical.delete()) throw IOException("无法清理 PDF 页面暂存文件。")
    }

    private fun requireSpace(bytes: Int) = requireSpace(bytes.toLong())
    private fun requireSpace(bytes: Long) {
        if (bytes < 0 || availableBytes() - SPACE_RESERVE < bytes) limit("可用空间不足，无法暂存 PDF 页面。")
    }

    private fun limit(message: String): Nothing = throw ArchiveReadException(ArchiveError.LIMIT, message)
    private fun pageName(index: Int) = "page-${(index + 1).toString().padStart(5, '0')}.png"
    private fun isHash(value: String) = Regex("[0-9a-f]{64}").matches(value)
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        private const val LONG_EDGE = 2560
        private const val SPACE_RESERVE = 16L * 1024 * 1024
        private const val RENDERER_VERSION = 1
    }
}
