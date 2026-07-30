package dev.openstream.app.monitoring

/** Pure luminance analysis used by the preview zebra overlay. */
object ZebraAnalyzer {
    fun analyze(
        pixels: IntArray,
        thresholdPercent: Int = 95,
    ): BooleanArray {
        require(thresholdPercent in 1..100) { "thresholdPercent must be between 1 and 100" }
        val threshold = thresholdPercent * 255 / 100
        return BooleanArray(pixels.size) { index ->
            val color = pixels[index]
            val red = color shr 16 and 0xff
            val green = color shr 8 and 0xff
            val blue = color and 0xff
            // Integer Rec. 709 luma. The coefficients sum to 256.
            val luma = (54 * red + 183 * green + 19 * blue) shr 8
            luma >= threshold
        }
    }
}
