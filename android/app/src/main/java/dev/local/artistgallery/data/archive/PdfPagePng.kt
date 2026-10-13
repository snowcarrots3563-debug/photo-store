package dev.local.artistgallery.data.archive

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.CRC32

/** Keeps page pixels unchanged while preserving identical/blank pages through byte-exact deduplication. */
internal object PdfPagePng {
    const val MIN_BYTES = 5120L
    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    fun stamp(
        file: File,
        pdfSha256: String,
        pageNumber: Int,
        checkCancelled: () -> Unit = {},
        beforeWrite: (Int) -> Unit = {},
    ): Long {
        require(Regex("[0-9a-f]{64}").matches(pdfSha256) && pageNumber > 0)
        RandomAccessFile(file, "rw").use { png ->
            val actualSignature = ByteArray(8)
            png.readFully(actualSignature)
            if (!actualSignature.contentEquals(signature)) throw IOException("PDF 页面不是有效的 PNG。")
            val buffer = ByteArray(128 * 1024)
            var first = true
            var hasData = false
            var endOffset = -1L
            while (png.filePointer < png.length()) {
                checkCancelled()
                val offset = png.filePointer
                if (png.length() - offset < 12) throw IOException("PNG 数据块不完整。")
                val length = png.readInt()
                val type = ByteArray(4).also(png::readFully)
                if (length < 0 || length.toLong() > png.length() - png.filePointer - 4 ||
                    type.any { it.toInt() !in 65..90 && it.toInt() !in 97..122 }) throw IOException("PNG 数据块无效。")
                val name = String(type, Charsets.US_ASCII)
                if (first && (name != "IHDR" || length != 13) || !first && name == "IHDR") throw IOException("PNG 页头无效。")
                first = false
                val crc = CRC32().also { it.update(type) }
                var remaining = length
                while (remaining > 0) {
                    checkCancelled()
                    val count = minOf(remaining, buffer.size)
                    png.readFully(buffer, 0, count)
                    crc.update(buffer, 0, count)
                    remaining -= count
                }
                if ((png.readInt().toLong() and 0xffffffffL) != crc.value) throw IOException("PNG 数据块校验失败。")
                if (name == "IDAT") hasData = true
                if (name == "IEND") {
                    if (length != 0 || !hasData || png.filePointer != png.length()) throw IOException("PNG 结束数据块无效。")
                    endOffset = offset
                    break
                }
            }
            if (endOffset < 0) throw IOException("PNG 缺少结束数据块。")
            val identity = "ArtistGalleryPDF\u0000sha256=$pdfSha256;page=$pageNumber".toByteArray(Charsets.US_ASCII)
            val padding = (MIN_BYTES - png.length() - 12 - identity.size).coerceAtLeast(0).toInt()
            val payload = identity + ByteArray(padding) { 32 }
            beforeWrite(12 + payload.size)
            checkCancelled()
            png.seek(endOffset)
            png.writeInt(payload.size)
            val type = "tEXt".toByteArray(Charsets.US_ASCII)
            png.write(type)
            png.write(payload)
            png.writeInt(CRC32().also { it.update(type); it.update(payload) }.value.toInt())
            png.writeInt(0)
            val endType = "IEND".toByteArray(Charsets.US_ASCII)
            png.write(endType)
            png.writeInt(CRC32().also { it.update(endType) }.value.toInt())
            png.setLength(png.filePointer)
            png.fd.sync()
            return png.length()
        }
    }
}
