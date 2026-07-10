package dev.openstream.app.monitoring

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ZebraAnalyzerTest {
    @Test
    fun `flags only pixels at or above exposure threshold`() {
        val pixels = intArrayOf(0xff000000.toInt(), 0xfff0f0f0.toInt(), 0xffffffff.toInt())

        val mask = ZebraAnalyzer.analyze(pixels, thresholdPercent = 95)

        assertArrayEquals(booleanArrayOf(false, false, true), mask)
    }

    @Test
    fun `uses perceptual luminance rather than a single color channel`() {
        val mask = ZebraAnalyzer.analyze(
            intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt()),
            thresholdPercent = 60,
        )

        assertFalse(mask[0])
        assertTrue(mask[1])
        assertFalse(mask[2])
    }

    @Test
    fun `rejects invalid thresholds`() {
        assertThrows(IllegalArgumentException::class.java) {
            ZebraAnalyzer.analyze(intArrayOf(0), thresholdPercent = 0)
        }
    }
}
