package dev.local.artistgallery.data.preview

import kotlin.math.exp
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

class GaussianBlurTest {
    @Test fun impulseHasTheNormalizedGaussianProfileRatherThanPixelBlocks() {
        val pixels = IntArray(61) { 0xFF000000.toInt() }
        pixels[30] = 0xFFFFFFFF.toInt()
        val blurred = gaussianBlur(pixels, 61, 1)
        val sum = (-18..18).sumOf { exp(-it.toDouble() * it / 72.0) }
        for (distance in 0..18) {
            val expected = (255 * exp(-distance.toDouble() * distance / 72.0) / sum).roundToInt()
            assertEquals(expected, blurred[30 + distance] and 255)
            assertEquals(blurred[30 - distance], blurred[30 + distance])
        }
        assertEquals(0, blurred[10] and 255)
        assertEquals(0xFFFFFFFF.toInt(), pixels[30])
        assertEquals(0xFF000000.toInt(), pixels[29])
    }

    @Test fun uniformTranslucentColorIsPreservedIncludingTheEdges() {
        val color = 0x8079B32C.toInt()
        val pixels = IntArray(23 * 17) { color }
        assertArrayEquals(pixels, gaussianBlur(pixels, 23, 17))
    }

    @Test fun invisibleBlueDoesNotBleedIntoVisibleRed() {
        val pixels = IntArray(61) { if (it < 30) 0x00_00_00_FF else 0xFF_FF_00_00.toInt() }
        val blurred = gaussianBlur(pixels, 61, 1)
        assertTrue((blurred[29] ushr 24) in 1..254)
        for (pixel in blurred) {
            if (pixel ushr 24 > 0) {
                assertEquals(255, pixel ushr 16 and 255)
                assertEquals(0, pixel and 0xFFFF)
            }
        }
    }

    @Test fun verticalConvolutionMatchesHorizontalConvolution() {
        val pixels = IntArray(61) { if (it == 30) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        assertArrayEquals(gaussianBlur(pixels, 61, 1), gaussianBlur(pixels, 1, 61))
    }

    @Test fun rowChecksAllowCancelledWorkToStopBeforeCompleting() {
        var rows = 0
        val cancelled = IllegalStateException("stop")
        try {
            gaussianBlur(IntArray(25) { -1 }, 5, 5) {
                rows++
                if (rows == 3) throw cancelled
            }
            fail("Expected work to stop")
        } catch (error: IllegalStateException) {
            assertSame(cancelled, error)
        }
        assertEquals(3, rows)
    }
}
