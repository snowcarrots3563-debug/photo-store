package dev.local.artistgallery.data.archive

import java.io.Closeable
import java.io.IOException
import java.io.OutputStream

data class ArchiveEntry(val index: Int, val path: String, val byteSize: Long, val isDirectory: Boolean, val isEncrypted: Boolean, val skipReason: String? = null)

enum class ArchiveError { PASSWORD, MISSING_VOLUME, UNSAFE_PATH, LIMIT, CORRUPT, IO }

class ArchiveReadException(val reason: ArchiveError, message: String, cause: Throwable? = null) : IOException(message, cause)

interface ArchiveReader : Closeable {
    fun entries(): List<ArchiveEntry>
    fun setPassword(password: CharArray)
    fun extract(index: Int, output: OutputStream, checkCancelled: () -> Unit): Long
}

object ArchivePaths {
    fun normalize(path: String): String {
        val normalized = path.replace('\\', '/')
        val parts = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        if (normalized.startsWith('/') || '\u0000' in path || parts.isEmpty() ||
            parts.any { it == ".." } || Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
            throw ArchiveReadException(ArchiveError.UNSAFE_PATH, "压缩包含不安全的路径，未读取该条目。")
        }
        return parts.joinToString("/")
    }
}
