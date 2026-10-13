package dev.local.artistgallery.data.preview

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ImageSharingTest {
    @Test fun detectsJpegPngAndBothGifVersionsFromBytes() {
        assertEquals(ShareImageFormat.JPEG, detectShareImageFormat(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()) + ByteArray(13)))
        assertEquals(ShareImageFormat.PNG, detectShareImageFormat(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) + ByteArray(8)))
        listOf("GIF87a", "GIF89a").forEach { assertEquals(ShareImageFormat.GIF, detectShareImageFormat(it.toByteArray() + ByteArray(10))) }
    }

    @Test fun truncatedAndUnsupportedHeadersAreRejected() {
        listOf(byteArrayOf(), byteArrayOf(0xff.toByte(), 0xd8.toByte()), "GIF89".toByteArray(),
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47), "%PDF-1.7".toByteArray(), "RIFFxxxxWEBP".toByteArray(),
            "not an image.jpg".toByteArray()).forEach { header ->
            try { detectShareImageFormat(header); fail("Unsupported bytes must fail") }
            catch (error: IOException) { assertTrue(error.message.orEmpty().contains("JPEG")) }
        }
    }

    @Test fun missingExtensionsAreAddedUsingDetectedFormat() {
        assertEquals("作品.jpg", imageShareDisplayName("作品", ShareImageFormat.JPEG))
        assertEquals("作品.png", imageShareDisplayName("作品", ShareImageFormat.PNG))
        assertEquals("作品.gif", imageShareDisplayName("作品", ShareImageFormat.GIF))
    }

    @Test fun misleadingExtensionsAreCorrectedWithoutLosingTheBasename() {
        assertEquals("第1页.jpg", imageShareDisplayName("第1页.png", ShareImageFormat.JPEG))
        assertEquals("作品.png", imageShareDisplayName("作品.bin", ShareImageFormat.PNG))
        assertEquals("作品.gif", imageShareDisplayName("作品.jpeg", ShareImageFormat.GIF))
    }

    @Test fun validExtensionCaseAndUnicodeNameAreRetained() {
        assertEquals("はるたろー.JPEG", imageShareDisplayName("はるたろー.JPEG", ShareImageFormat.JPEG))
        assertEquals("作品.JPG", imageShareDisplayName("作品.JPG", ShareImageFormat.JPEG))
        assertEquals("作品.PNG", imageShareDisplayName("作品.PNG", ShareImageFormat.PNG))
        assertEquals("作品.GIF", imageShareDisplayName("作品.GIF", ShareImageFormat.GIF))
    }

    @Test fun directoryAndUnsafeCharactersCannotBecomeTheSharedFilename() {
        assertEquals("第1页.png", imageShareDisplayName("文件夹/第1页.png", ShareImageFormat.PNG))
        assertEquals("第1页.gif", imageShareDisplayName("文件夹\\第1页.gif", ShareImageFormat.GIF))
        assertEquals("作品___.jpg", imageShareDisplayName("作品\u0000:*", ShareImageFormat.JPEG))
    }

    @Test fun blankAndDotNamesReceiveAFilenameAndTrailingDotsAreRemoved() {
        listOf("", " ", ".", "..").forEach { assertEquals("图片.png", imageShareDisplayName(it, ShareImageFormat.PNG)) }
        assertEquals("作品.gif", imageShareDisplayName("作品... ", ShareImageFormat.GIF))
        assertEquals(".封面.jpg", imageShareDisplayName(".封面", ShareImageFormat.JPEG))
    }
}
