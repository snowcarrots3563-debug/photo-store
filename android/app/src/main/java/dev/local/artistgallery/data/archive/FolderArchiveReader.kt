package dev.local.artistgallery.data.archive

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import dev.local.artistgallery.data.importing.MediaImportPolicy
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

/** A confirmed task reads this private snapshot, never the selected provider tree again. */
class FolderArchiveReader(sessionDirectory: File) : ArchiveReader {
    private val root = sessionDirectory.canonicalFile
    private val records by lazy { readIndex(root) }

    override fun entries(): List<ArchiveEntry> = records.map { it.entry }
    override fun setPassword(password: CharArray) {}
    override fun close() {}

    override fun extract(index: Int, output: OutputStream, checkCancelled: () -> Unit): Long {
        checkCancelled()
        val record = records.getOrNull(index) ?: corrupt("文件夹条目不存在。")
        if (record.entry.isDirectory) return 0
        if (record.filename.isEmpty()) throw ArchiveReadException(ArchiveError.IO, "该文件已跳过，没有暂存副本。")
        val source = child(root, record.filename)
        if (!source.isFile || source.length() != record.entry.byteSize) corrupt("文件夹暂存副本大小核验失败。")
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        source.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                checkCancelled()
                val length = input.read(buffer)
                if (length < 0) break
                if (length.toLong() > record.entry.byteSize - count) corrupt("文件夹暂存副本大小核验失败。")
                output.write(buffer, 0, length)
                digest.update(buffer, 0, length)
                count += length
            }
        }
        if (count != record.entry.byteSize || hex(digest.digest()) != record.sha256) corrupt("文件夹暂存副本内容核验失败。")
        return count
    }

    private data class Record(val entry: ArchiveEntry, val filename: String = "", val sha256: String = "")
    private data class Document(val id: String, val name: String, val directory: Boolean, val size: Long, val flags: Int)
    private data class Directory(val id: String, val path: String, val parent: Directory? = null)

    companion object {
        private const val INDEX_NAME = "folder.index"
        private const val INDEX_VERSION = "artist-gallery-folder-v1"
        private const val BUFFER_SIZE = 128 * 1024
        private const val SPACE_RESERVE = 16L * 1024 * 1024
        private val MEDIA_EXTENSIONS = setOf("gif", "png", "jpg", "jpeg", "webp", "bmp", "heic", "heif", "avif", "mp4", "m4v", "mov", "webm", "mkv", "3gp")
        private val PROJECTION = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_FLAGS)

        fun snapshot(context: Context, treeUri: Uri, sessionDirectory: File, availableBytes: () -> Long,
                     checkCancelled: () -> Unit, onProgress: (String) -> Unit): String {
            val root = sessionDirectory.canonicalFile
            if (!root.isDirectory || child(root, INDEX_NAME).exists()) throw IOException("文件夹暂存目录不可用。")
            val resolver = context.contentResolver
            val treeId = DocumentsContract.getTreeDocumentId(treeUri)
            fun query(uri: Uri): List<Document> {
                checkCancelled()
                val cursor = resolver.query(uri, PROJECTION, null, null, null)
                    ?: throw IOException("目录提供者没有返回文件清单，请重试。")
                return cursor.use {
                    if (it.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) throw IOException("目录仍在加载，请完成加载后重新选择。")
                    val result = mutableListOf<Document>()
                    while (it.moveToNext()) {
                        checkCancelled()
                        if (result.size >= ZipArchiveReader.MAX_ENTRIES) limit("文件夹条目数量超过 20000 条限制。")
                        result.add(document(it))
                    }
                    result
                }
            }
            val selected = query(DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)).singleOrNull()
                ?: throw IOException("无法读取所选文件夹名称。")
            if (!selected.directory) throw IOException("请选择文件夹。")
            val records = mutableListOf<Record>()
            val pending = ArrayDeque<Directory>()
            val queriedDirectories = mutableMapOf<String, List<Document>>()
            pending.add(Directory(treeId, ""))
            while (pending.isNotEmpty()) {
                checkCancelled()
                val directory = pending.removeFirst()
                var ancestor = directory.parent
                while (ancestor != null) {
                    if (ancestor.id == directory.id) corrupt("文件夹包含循环目录，无法确定分类。")
                    ancestor = ancestor.parent
                }
                onProgress("正在读取目录 · ${directory.path.ifEmpty { selected.name }}")
                val children = queriedDirectories.getOrPut(directory.id) {
                    query(DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directory.id))
                        .sortedWith(compareBy<Document> { it.name }.thenBy { it.id })
                }
                val directoryNames = mutableSetOf<String>()
                val reserved = children.map { it.name }.toMutableSet()
                for (document in children) {
                    checkCancelled()
                    if (records.size >= ZipArchiveReader.MAX_ENTRIES) limit("文件夹条目数量超过 20000 条限制。")
                    var name = document.name
                    if (document.directory && !directoryNames.add(name)) {
                        var suffix = 2
                        while (name in reserved) name = "${document.name} (${suffix++})"
                        reserved.add(name)
                    }
                    val path = if (directory.path.isEmpty()) name else "${directory.path}/$name"
                    val index = records.size
                    if (document.directory) {
                        records.add(Record(ArchiveEntry(index, path, 0, true, false)))
                        pending.add(Directory(document.id, path, directory))
                        continue
                    }
                    val extension = name.substringAfterLast('.', "").lowercase()
                    val reason = when {
                        extension == "txt" -> "TXT 暂不导入。"
                        extension != "pdf" && extension !in MEDIA_EXTENSIONS -> "不支持的媒体类型。"
                        document.flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0 -> "虚拟文件没有可读取的原件，已跳过。"
                        document.flags and DocumentsContract.Document.FLAG_PARTIAL != 0 -> "文件尚未下载完整，已跳过。"
                        extension != "pdf" -> MediaImportPolicy.skipReason(document.size)
                        else -> null
                    }
                    if (reason != null) {
                        records.add(Record(ArchiveEntry(index, path, document.size, false, false, reason)))
                        continue
                    }
                    if (document.size > ZipArchiveReader.MAX_ENTRY_BYTES) limit("媒体条目超过 2 GiB 限制。")
                    val filename = "data-$index"
                    val target = child(root, filename)
                    val part = child(root, "$filename.partial")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    try {
                        resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, document.id))?.use { input ->
                            FileOutputStream(part).use { output ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                while (true) {
                                    checkCancelled()
                                    val length = input.read(buffer)
                                    if (length < 0) break
                                    if (length.toLong() > ZipArchiveReader.MAX_ENTRY_BYTES - count) limit("媒体条目超过 2 GiB 限制。")
                                    if (document.size >= 0 && length.toLong() > document.size - count) throw IOException("来源文件大小已变化，请重新选择文件夹。")
                                    requireSpace(availableBytes, length.toLong())
                                    output.write(buffer, 0, length)
                                    digest.update(buffer, 0, length)
                                    count += length
                                    onProgress("$path · ${count / (1024 * 1024)} MB")
                                }
                                output.fd.sync()
                            }
                        } ?: throw IOException("来源文件不可用：$path")
                        if (document.size >= 0 && count != document.size) throw IOException("来源文件大小已变化，请重新选择文件夹。")
                        if (!part.renameTo(target)) throw IOException("无法保存文件夹暂存副本。")
                        records.add(Record(ArchiveEntry(index, path, count, false, false), filename, hex(digest.digest())))
                    } finally { part.delete() }
                }
            }
            checkCancelled()
            val part = child(root, "$INDEX_NAME.partial")
            try {
                FileOutputStream(part).use { file ->
                    val output = DataOutputStream(BufferedOutputStream(object : OutputStream() {
                        override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            checkCancelled(); requireSpace(availableBytes, length.toLong()); file.write(bytes, offset, length)
                        }
                    }, BUFFER_SIZE))
                    output.writeUTF(INDEX_VERSION); output.writeInt(records.size)
                    records.forEach { record ->
                        output.writeInt(record.entry.index); output.writeUTF(record.entry.path); output.writeLong(record.entry.byteSize)
                        output.writeBoolean(record.entry.isDirectory); output.writeUTF(record.entry.skipReason.orEmpty())
                        output.writeUTF(record.filename); output.writeUTF(record.sha256)
                    }
                    output.flush(); file.fd.sync()
                }
                if (!part.renameTo(child(root, INDEX_NAME))) throw IOException("无法保存文件夹导入索引。")
            } finally { part.delete() }
            return selected.name
        }

        private fun document(cursor: Cursor): Document {
            val id = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                ?: throw IOException("目录提供者没有返回文件标识。")
            val name = ArchiveBundle.safeName(cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                ?: throw IOException("目录提供者没有返回文件名称。"))
            val size = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val bytes = if (cursor.isNull(size)) -1 else cursor.getLong(size)
            if (id.isEmpty() || bytes < -1) throw IOException("目录提供者返回了无效的文件信息。")
            return Document(id, name, cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)) == DocumentsContract.Document.MIME_TYPE_DIR,
                bytes, cursor.getInt(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS)))
        }

        private fun readIndex(root: File): List<Record> = try {
            DataInputStream(child(root, INDEX_NAME).inputStream().buffered()).use { input ->
                if (input.readUTF() != INDEX_VERSION) corrupt("文件夹导入索引版本无效。")
                val count = input.readInt()
                if (count !in 0..ZipArchiveReader.MAX_ENTRIES) corrupt("文件夹导入索引数量无效。")
                val records = (0 until count).map { position ->
                    val index = input.readInt(); val path = input.readUTF(); val size = input.readLong()
                    val directory = input.readBoolean(); val reason = input.readUTF().ifEmpty { null }
                    val filename = input.readUTF(); val sha256 = input.readUTF()
                    if (index != position || path != ArchivePaths.normalize(path) || size < -1) corrupt("文件夹导入索引无效。")
                    if (directory || reason != null) {
                        if (filename.isNotEmpty() || sha256.isNotEmpty() || directory && size != 0L) corrupt("文件夹导入索引无效。")
                    } else if (filename != "data-$index" || size !in 0..ZipArchiveReader.MAX_ENTRY_BYTES || !Regex("[0-9a-f]{64}").matches(sha256)) {
                        corrupt("文件夹导入索引无效。")
                    }
                    Record(ArchiveEntry(index, path, size, directory, false, reason), filename, sha256)
                }
                if (input.read() != -1) corrupt("文件夹导入索引包含多余数据。")
                records
            }
        } catch (error: ArchiveReadException) { throw error }
        catch (error: IOException) { throw ArchiveReadException(ArchiveError.IO, "文件夹导入索引不可用，请重新选择文件夹。", error) }

        private fun child(root: File, name: String): File {
            val file = File(root, name).canonicalFile
            if (file.parentFile != root || file == root) throw ArchiveReadException(ArchiveError.UNSAFE_PATH, "文件夹暂存路径不安全。")
            return file
        }
        private fun requireSpace(availableBytes: () -> Long, bytes: Long) {
            if (availableBytes() - SPACE_RESERVE < bytes) limit("可用空间不足，无法暂存文件夹。")
        }
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        private fun corrupt(message: String): Nothing = throw ArchiveReadException(ArchiveError.CORRUPT, message)
        private fun limit(message: String): Nothing = throw ArchiveReadException(ArchiveError.LIMIT, message)
    }
}
