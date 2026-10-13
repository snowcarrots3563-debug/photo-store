package dev.local.artistgallery.data.archive

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic PDFs only; never reads the user's selected archives or gallery. */
@RunWith(AndroidJUnit4::class)
class PdfArchiveReaderTest {
    private lateinit var folder: File
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun prepare() {
        folder = File(context.cacheDir, "pdf-reader-test-${UUID.randomUUID()}")
        assertTrue(folder.mkdir())
    }

    @After fun cleanUp() {
        val target = folder.canonicalFile
        assertEquals(context.cacheDir.canonicalFile, target.parentFile)
        assertTrue(target.name.startsWith("pdf-reader-test-"))
        target.deleteRecursively()
    }

    @Test fun ordinaryArchivePassesThroughWithoutCreatingCache() {
        val archive = FakeReader(listOf(source(0, "作品/a.png", ByteArray(6000) { 12 })))
        val cache = File(folder, "cache")
        PdfArchiveReader(archive, cache, { Long.MAX_VALUE }, {}).use { reader ->
            assertEquals(archive.entries(), reader.entries())
            val output = ByteArrayOutputStream()
            assertEquals(6000L, reader.extract(0, output) {})
            assertArrayEquals(ByteArray(6000) { 12 }, output.toByteArray())
            assertFalse(cache.exists())
        }
        assertTrue(archive.closed)
    }

    @Test fun everyPagePreservesOrderWhiteBackgroundGeometryAndIndependentIdentity() {
        val bytes = pdf(listOf(100 to 200, 200 to 100, 100 to 200, 100 to 200))
        val archive = FakeReader(listOf(source(4, "作品/book.PDF", bytes), source(8, "作品/other.png", ByteArray(6000))))
        PdfArchiveReader(archive, File(folder, "cache"), { Long.MAX_VALUE }, {}).use { reader ->
            val entries = reader.entries()
            val pages = entries.filter { it.path.endsWith("页.png") }
            assertEquals(listOf(9, 10, 11, 12), pages.map { it.index })
            assertEquals((1..4).map { "作品/book/第${it.toString().padStart(4, '0')}页.png" }, pages.map { it.path })
            assertTrue(pages.all { it.byteSize >= 5120 })
            assertEquals(8, entries.last().index)
            val images = pages.map { extract(reader, it.index) }
            assertFalse(images[0].contentEquals(images[2]))
            assertFalse(images[2].contentEquals(images[3]))
            images.forEachIndexed { index, image ->
                val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size)
                try {
                    assertNotNull(bitmap)
                    assertEquals(if (index == 1) 2560 else 1280, bitmap.width)
                    assertEquals(if (index == 1) 1280 else 2560, bitmap.height)
                    assertEquals(Color.WHITE, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
                } finally { bitmap?.recycle() }
            }
        }
    }

    @Test fun duplicatePdfPathsAndExistingDirectoriesKeepSeparateGroups() {
        val bytes = pdf(listOf(100 to 100))
        val archive = FakeReader(listOf(
            source(0, "book.pdf", bytes), source(1, "book/a.png", ByteArray(6000)), source(2, "book.pdf", bytes),
        ))
        PdfArchiveReader(archive, File(folder, "cache"), { Long.MAX_VALUE }, {}).use { reader ->
            val entries = reader.entries()
            assertEquals(listOf("book (2)/第0001页.png", "book/a.png", "book (3)/第0001页.png"), entries.map { it.path })
            assertEquals(listOf("book (2)", "book", "book (3)"), ArchivePreview.build(entries, "包.zip", emptyList()).map { it.relativeDirectory })
            assertEquals(3, entries.map { it.index }.toSet().size)
        }
    }

    @Test fun cachedPagesReturnExactBytesWithoutExtractingOrdinaryPdfAgain() {
        val bytes = pdf(listOf(100 to 200, 200 to 100), colored = true)
        val cache = File(folder, "cache")
        val firstArchive = FakeReader(listOf(source(3, "book.pdf", bytes)))
        val first = PdfArchiveReader(firstArchive, cache, { Long.MAX_VALUE }, {}).use { reader ->
            reader.entries().map { extract(reader, it.index) }
        }
        assertEquals(1, firstArchive.extracted.size)
        assertTrue(File(cache, "pdf-3/manifest.json").isFile)
        val secondArchive = FakeReader(listOf(source(3, "book.pdf", bytes)))
        PdfArchiveReader(secondArchive, cache, { Long.MAX_VALUE }, {}).use { reader ->
            // extract initializes the virtual mapping even when the coordinator opened a new reader.
            assertArrayEquals(first[0], extract(reader, 4))
            assertArrayEquals(first[1], extract(reader, 5))
            assertTrue(secondArchive.extracted.isEmpty())
        }
    }

    @Test fun corruptedCachedPageIsRebuiltRatherThanImported() {
        val cache = File(folder, "cache")
        val bytes = pdf(listOf(100 to 100), colored = true)
        val original = PdfArchiveReader(FakeReader(listOf(source(0, "book.pdf", bytes))), cache, { Long.MAX_VALUE }, {}).use { reader ->
            extract(reader, reader.entries().single().index)
        }
        val page = File(cache, "pdf-0/page-00001.png")
        val corrupted = original.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        page.writeBytes(corrupted)
        PdfArchiveReader(FakeReader(listOf(source(0, "book.pdf", bytes))), cache, { Long.MAX_VALUE }, {}).use { reader ->
            assertArrayEquals(original, extract(reader, reader.entries().single().index))
        }
    }

    @Test fun encryptedArchivePdfMustAuthenticateBeforeReusingCache() {
        val cache = File(folder, "cache")
        val value = source(0, "book.pdf", pdf(listOf(100 to 100)), encrypted = true)
        PdfArchiveReader(FakeReader(listOf(value)), cache, { Long.MAX_VALUE }, {}).use { reader ->
            reader.setPassword("correct".toCharArray())
            reader.entries()
        }
        val pageFile = File(cache, "pdf-0/page-00001.png")
        val cachedBytes = pageFile.readBytes()
        val nextArchive = FakeReader(listOf(value))
        var renderedAgain = false
        PdfArchiveReader(nextArchive, cache, { Long.MAX_VALUE }, {}, onProgress = {
            if (it.startsWith("正在转换 PDF")) renderedAgain = true
        }).use { reader ->
            try { reader.entries(); fail("encrypted cache bypassed authentication") }
            catch (error: ArchiveReadException) { assertEquals(ArchiveError.PASSWORD, error.reason) }
            assertArrayEquals(cachedBytes, pageFile.readBytes())
            assertTrue(File(cache, "pdf-0/manifest.json").isFile)
            reader.setPassword("wrong".toCharArray())
            try { reader.entries(); fail("wrong password accepted") }
            catch (error: ArchiveReadException) { assertEquals(ArchiveError.PASSWORD, error.reason) }
            assertArrayEquals(cachedBytes, pageFile.readBytes())
            reader.setPassword("correct".toCharArray())
            assertEquals(1, reader.entries().size)
            assertEquals(listOf(0, 0, 0), nextArchive.extracted)
            assertFalse(renderedAgain)
        }
        val missingVolume = object : ArchiveReader by FakeReader(listOf(value)) {
            override fun extract(index: Int, output: OutputStream, checkCancelled: () -> Unit): Long {
                throw ArchiveReadException(ArchiveError.MISSING_VOLUME, "test missing volume")
            }
        }
        PdfArchiveReader(missingVolume, cache, { Long.MAX_VALUE }, {}).use { reader ->
            try { reader.entries(); fail("missing volume ignored") }
            catch (error: ArchiveReadException) { assertEquals(ArchiveError.MISSING_VOLUME, error.reason) }
            assertArrayEquals(cachedBytes, pageFile.readBytes())
        }
    }

    @Test fun previouslySkippedPdfKeepsItsOriginalEntryForCommittedBatchRecovery() {
        val skipped = source(0, "作品/book.pdf", pdf(listOf(100 to 100)))
        val pending = source(1, "作品/a.png", ByteArray(6000))
        val cache = File(folder, "cache")
        val archive = FakeReader(listOf(skipped, pending))
        PdfArchiveReader(archive, cache, { Long.MAX_VALUE }, {}, skippedPdfIndices = setOf(0)).use { reader ->
            val entries = reader.entries()
            assertEquals(listOf(skipped.entry.index, pending.entry.index), entries.map { it.index })
            assertEquals(skipped.entry.path, entries[0].path)
            assertEquals(skipped.entry.byteSize, entries[0].byteSize)
            assertTrue(entries[0].skipReason!!.contains("此任务已跳过"))
            assertFalse(cache.exists())
            assertArrayEquals(pending.bytes, extract(reader, pending.entry.index))
            assertEquals(listOf(1), archive.extracted)
        }
    }

    @Test fun skippedMalformedDuplicatePdfKeepsOtherPdfPagePathsOnReopen() {
        val sources = listOf(source(0, "book.pdf", ByteArray(5120)), source(1, "book.pdf", pdf(listOf(100 to 100))))
        val cache = File(folder, "cache")
        val before = PdfArchiveReader(FakeReader(sources), cache, { Long.MAX_VALUE }, {}).use { it.entries() }
        assertNotNull(before[0].skipReason)
        assertEquals("book (2)/第0001页.png", before[1].path)
        PdfArchiveReader(FakeReader(sources), cache, { Long.MAX_VALUE }, {}, skippedPdfIndices = setOf(0)).use { reader ->
            assertEquals(before.map { Triple(it.index, it.path, it.byteSize) }, reader.entries().map { Triple(it.index, it.path, it.byteSize) })
        }
    }

    @Test fun malformedPdfIsSkippedWhileValidSiblingRemainsImportable() {
        val archive = FakeReader(listOf(source(0, "bad.pdf", ByteArray(5120)), source(1, "valid.png", ByteArray(6000))))
        val cache = File(folder, "cache")
        PdfArchiveReader(archive, cache, { Long.MAX_VALUE }, {}).use { reader ->
            val entries = reader.entries()
            assertTrue(entries[0].skipReason!!.contains("PDF"))
            assertEquals("bad.pdf", entries[0].path)
            val preview = ArchivePreview.build(entries, "包.zip", emptyList()).single()
            assertTrue(preview.canImport)
            assertNull(preview.items[0].mediaType)
            assertNotNull(preview.items[1].mediaType)
            assertArrayEquals(ByteArray(6000), extract(reader, 1))
            assertFalse(File(cache, "pdf-0").exists())
        }
    }

    @Test fun cancellationRemovesPartialPageCacheAndPropagates() {
        var cancelled = false
        val cache = File(folder, "cache")
        val bytes = pdf(listOf(100 to 100, 100 to 100, 100 to 100))
        PdfArchiveReader(FakeReader(listOf(source(0, "book.pdf", bytes))), cache, { Long.MAX_VALUE }, {
            if (cancelled) throw CancellationException("test cancellation")
        }, onProgress = { progress -> if (progress.endsWith("2 / 3")) cancelled = true }).use { reader ->
            try { reader.entries(); fail("cancelled conversion finished") }
            catch (_: CancellationException) { assertFalse(File(cache, "pdf-0").exists()) }
        }
    }

    @Test fun combinedEntryCountAndSpaceLimitsAreFatal() {
        val bytes = pdf(listOf(100 to 100))
        val many = mutableListOf(source(0, "book.pdf", bytes))
        (1 until ZipArchiveReader.MAX_ENTRIES).forEach { many.add(source(it, "files/$it.png", byteArrayOf())) }
        listOf(FakeReader(many) to Long.MAX_VALUE, FakeReader(listOf(source(0, "book.pdf", bytes))) to 16L * 1024 * 1024).forEachIndexed { index, (archive, available) ->
            PdfArchiveReader(archive, File(folder, "cache-$index"), { available }, {}).use { reader ->
                try { reader.entries(); fail("PDF limit ignored") }
                catch (error: ArchiveReadException) { assertEquals(ArchiveError.LIMIT, error.reason) }
            }
        }
    }

    private data class Source(val entry: ArchiveEntry, val bytes: ByteArray)
    private fun source(index: Int, path: String, bytes: ByteArray, encrypted: Boolean = false) =
        Source(ArchiveEntry(index, path, bytes.size.toLong(), false, encrypted), bytes)

    private class FakeReader(private val sources: List<Source>) : ArchiveReader {
        val extracted = mutableListOf<Int>()
        var closed = false
        private var password: String? = null
        override fun entries() = sources.map { it.entry }
        override fun setPassword(password: CharArray) { this.password = String(password) }
        override fun extract(index: Int, output: OutputStream, checkCancelled: () -> Unit): Long {
            val source = sources.first { it.entry.index == index }
            extracted.add(index)
            if (source.entry.isEncrypted && password != "correct") throw ArchiveReadException(ArchiveError.PASSWORD, "test password")
            checkCancelled()
            output.write(source.bytes)
            return source.bytes.size.toLong()
        }
        override fun close() { closed = true }
    }

    private fun extract(reader: ArchiveReader, index: Int): ByteArray = ByteArrayOutputStream().also { reader.extract(index, it) {} }.toByteArray()

    private fun pdf(sizes: List<Pair<Int, Int>>, colored: Boolean = false): ByteArray = ByteArrayOutputStream().also { output ->
        val document = PdfDocument()
        try {
            sizes.forEachIndexed { index, (width, height) ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                if (colored) page.canvas.drawRect(0f, 0f, width / 2f, height / 2f, Paint().also { it.color = Color.RED })
                document.finishPage(page)
            }
            document.writeTo(output)
        } finally { document.close() }
    }.toByteArray()
}
