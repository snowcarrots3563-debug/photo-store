package dev.local.artistgallery.data.preview

import kotlin.math.exp
import kotlin.math.roundToInt

private val gaussianKernel = FloatArray(37) { index -> exp(-(index - 18).toDouble() * (index - 18) / 72.0).toFloat() }
    .also { weights -> val sum = weights.sum(); weights.indices.forEach { weights[it] /= sum } }

/** Separable sigma-6 Gaussian, with clamped edges and premultiplied color to avoid transparent halos. */
internal fun gaussianBlur(pixels: IntArray, width: Int, height: Int, checkActive: () -> Unit = {}): IntArray {
    val horizontal = FloatArray(pixels.size * 4)
    for (y in 0 until height) {
        checkActive()
        for (x in 0 until width) {
            val target = (y * width + x) * 4
            for (offset in -18..18) {
                val pixel = pixels[y * width + (x + offset).coerceIn(0, width - 1)]
                val weight = gaussianKernel[offset + 18]
                val alpha = (pixel ushr 24).toFloat()
                horizontal[target] += alpha * weight
                horizontal[target + 1] += (pixel ushr 16 and 255) * alpha / 255f * weight
                horizontal[target + 2] += (pixel ushr 8 and 255) * alpha / 255f * weight
                horizontal[target + 3] += (pixel and 255) * alpha / 255f * weight
            }
        }
    }
    val output = IntArray(pixels.size)
    for (y in 0 until height) {
        checkActive()
        for (x in 0 until width) {
            var alpha = 0f
            var red = 0f
            var green = 0f
            var blue = 0f
            for (offset in -18..18) {
                val source = ((y + offset).coerceIn(0, height - 1) * width + x) * 4
                val weight = gaussianKernel[offset + 18]
                alpha += horizontal[source] * weight
                red += horizontal[source + 1] * weight
                green += horizontal[source + 2] * weight
                blue += horizontal[source + 3] * weight
            }
            val a = alpha.roundToInt().coerceIn(0, 255)
            if (a > 0) {
                output[y * width + x] = (a shl 24) or
                    ((red * 255f / alpha).roundToInt().coerceIn(0, 255) shl 16) or
                    ((green * 255f / alpha).roundToInt().coerceIn(0, 255) shl 8) or
                    (blue * 255f / alpha).roundToInt().coerceIn(0, 255)
            }
        }
    }
    return output
}
