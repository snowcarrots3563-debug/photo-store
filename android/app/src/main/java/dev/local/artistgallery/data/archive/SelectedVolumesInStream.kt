package dev.local.artistgallery.data.archive

import java.io.File
import java.io.RandomAccessFile
import net.sf.sevenzipjbinding.IInStream
import net.sf.sevenzipjbinding.SevenZipException

/** Random access over selected numeric volumes, without creating another full-size copy. */
internal class SelectedVolumesInStream(files: List<File>, private val checkCancelled: () -> Unit) : IInStream {
    private val volumes = files.sortedBy { it.name.substringAfterLast('.').toInt() }
    private val starts = mutableListOf(0L)
    private var position = 0L
    private var currentIndex = -1
    private var current: RandomAccessFile? = null
    private val length: Long

    init {
        volumes.forEach { starts.add(starts.last() + it.length()) }
        length = starts.last()
    }

    override fun seek(offset: Long, origin: Int): Long {
        checkCancelled()
        val next = when (origin) { 0 -> offset; 1 -> position + offset; 2 -> length + offset; else -> throw SevenZipException("Invalid seek origin") }
        if (next < 0) throw SevenZipException("Invalid seek offset")
        position = next
        return position
    }

    override fun read(data: ByteArray): Int {
        checkCancelled()
        if (data.isEmpty() || position >= length) return 0
        val index = volumes.indices.first { position < starts[it + 1] }
        if (currentIndex != index) {
            current?.close()
            current = RandomAccessFile(volumes[index], "r")
            currentIndex = index
        }
        val source = requireNotNull(current)
        source.seek(position - starts[index])
        val count = source.read(data, 0, minOf(data.size.toLong(), starts[index + 1] - position).toInt())
        if (count < 0) throw ArchiveReadException(ArchiveError.MISSING_VOLUME, "分卷被截断，请重新选择完整文件。")
        position += count
        return count
    }

    override fun close() { current?.close(); current = null; currentIndex = -1 }
}
