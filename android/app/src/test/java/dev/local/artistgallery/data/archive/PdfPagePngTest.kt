package dev.local.artistgallery.data.archive

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfPagePngTest {
    @get:Rule val temporary = TemporaryFolder()
    private val hash = "ab".repeat(32)

    @Test fun metadataPreservesImageChunksAndProducesValidCrcBeforeIend() {
        val original = fixture()
        val file = png(original)
        PdfPagePng.stamp(file, hash, 1)
        assertEquals(PdfPagePng.MIN_BYTES, file.length())
        val before = chunks(original)
        val after = chunks(file.readBytes())
        assertEquals(listOf("IHDR", "IDAT", "tEXt", "IEND"), after.map { it.first })
        assertArrayEquals(before[0].second, after[0].second)
        assertArrayEquals(before[1].second, after[1].second)
        assertArrayEquals(before.last().second, after.last().second)
        assertTrue(String(after[2].second, Charsets.US_ASCII).startsWith("ArtistGalleryPDF\u0000sha256=$hash;page=1"))
    }

    @Test fun samePageIsStableButIdenticalImageOnDifferentPagesIsDistinct() {
        val first = png(fixture())
        val repeat = png(fixture())
        val second = png(fixture())
        PdfPagePng.stamp(first, hash, 1)
        PdfPagePng.stamp(repeat, hash, 1)
        PdfPagePng.stamp(second, hash, 2)
        assertArrayEquals(first.readBytes(), repeat.readBytes())
        assertFalse(first.readBytes().contentEquals(second.readBytes()))
        val otherPdf = png(fixture())
        PdfPagePng.stamp(otherPdf, "cd".repeat(32), 1)
        assertFalse(first.readBytes().contentEquals(otherPdf.readBytes()))
    }

    @Test fun largerPngGetsIdentityWithoutUnnecessaryPadding() {
        val original = fixture(6000)
        val file = png(original)
        PdfPagePng.stamp(file, hash, 12)
        val identity = "ArtistGalleryPDF\u0000sha256=$hash;page=12".toByteArray(Charsets.US_ASCII)
        assertEquals(original.size.toLong() + identity.size + 12, file.length())
        val after = chunks(file.readBytes())
        assertArrayEquals(chunks(original).first { it.first == "tEXt" }.second, after.first { it.first == "tEXt" }.second)
        assertArrayEquals(identity, after.last { it.first == "tEXt" }.second)
    }

    @Test fun malformedPngSignatureCrcAndTerminationAreRejectedBeforeWriting() {
        val valid = fixture()
        val corrupted = valid.copyOf().also { it[29] = (it[29].toInt() xor 1).toByte() }
        listOf(byteArrayOf(0, 1), corrupted, valid.copyOf(valid.size - 1), valid + byteArrayOf(0), valid.copyOf(valid.size - 12)).forEach { bytes ->
            val file = png(bytes)
            try { PdfPagePng.stamp(file, hash, 1); fail("malformed PNG accepted") }
            catch (_: IOException) { assertArrayEquals(bytes, file.readBytes()) }
        }
    }

    @Test fun cancelledOrInsufficientSpaceDoesNotChangeUnstampedImage() {
        val original = fixture()
        val file = png(original)
        try {
            PdfPagePng.stamp(file, hash, 1, beforeWrite = { throw IOException("no space") })
            fail("space check was ignored")
        } catch (_: IOException) { assertArrayEquals(original, file.readBytes()) }
        try {
            PdfPagePng.stamp(file, hash, 1, checkCancelled = { throw java.util.concurrent.CancellationException() })
            fail("cancellation was ignored")
        } catch (_: java.util.concurrent.CancellationException) { assertArrayEquals(original, file.readBytes()) }
    }

    private fun png(bytes: ByteArray): File = temporary.newFile().also { it.writeBytes(bytes) }

    private fun fixture(extraText: Int = 0): ByteArray {
        val bytes = ByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        output.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        val header = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { it.writeInt(1); it.writeInt(1); it.write(byteArrayOf(8, 6, 0, 0, 0)) }
        }.toByteArray()
        chunk(output, "IHDR", header)
        val image = ByteArrayOutputStream().also { buffer ->
            DeflaterOutputStream(buffer).use { it.write(byteArrayOf(0, -1, -1, -1, -1)) }
        }.toByteArray()
        chunk(output, "IDAT", image)
        if (extraText > 0) chunk(output, "tEXt", "Fixture\u0000${"x".repeat(extraText)}".toByteArray(Charsets.US_ASCII))
        chunk(output, "IEND", byteArrayOf())
        return bytes.toByteArray()
    }

    private fun chunk(output: DataOutputStream, name: String, payload: ByteArray) {
        val type = name.toByteArray(Charsets.US_ASCII)
        output.writeInt(payload.size)
        output.write(type)
        output.write(payload)
        output.writeInt(CRC32().also { it.update(type); it.update(payload) }.value.toInt())
    }

    private fun chunks(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val chunks = mutableListOf<Pair<String, ByteArray>>()
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            assertArrayEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), ByteArray(8).also(input::readFully))
            while (input.available() > 0) {
                val length = input.readInt()
                val type = ByteArray(4).also(input::readFully)
                val payload = ByteArray(length).also(input::readFully)
                assertEquals(CRC32().also { it.update(type); it.update(payload) }.value, input.readInt().toLong() and 0xffffffffL)
                chunks.add(String(type, Charsets.US_ASCII) to payload)
                if (chunks.last().first == "IEND") assertEquals(0, input.available())
            }
        }
        return chunks
    }
}
