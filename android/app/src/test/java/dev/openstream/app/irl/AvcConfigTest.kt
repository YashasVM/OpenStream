package dev.openstream.app.irl

import org.junit.Assert.*
import org.junit.Test

class AvcConfigTest {
    private val start3 = byteArrayOf(0, 0, 1)
    private val start4 = byteArrayOf(0, 0, 0, 1)
    private val sps = byteArrayOf(0x67, 0x42, 0, 0x1f)
    private val pps = byteArrayOf(0x68, 0x0a, 0x20)

    @Test
    fun splitsSeparateCsdWithThreeOrFourByteStartCodes() {
        listOf(start3, start4).forEach { start ->
            val result = requireNotNull(AvcConfig.split(start + sps, start + pps))
            assertArrayEquals(start4 + sps, result.sps)
            assertArrayEquals(start4 + pps, result.pps)
        }
    }

    @Test
    fun splitsASingleConcatenatedCsdZeroWithMixedStartCodes() {
        val result = requireNotNull(AvcConfig.split(start4 + sps + start3 + pps))
        assertArrayEquals(start4 + sps, result.sps)
        assertArrayEquals(start4 + pps, result.pps)
    }

    @Test
    fun findsParameterSetsAmongOtherNalsInEitherOrder() {
        val result = requireNotNull(AvcConfig.split(start3 + byteArrayOf(9, 0x10) + start4 + pps + start3 + sps))
        assertArrayEquals(start4 + sps, result.sps)
        assertArrayEquals(start4 + pps, result.pps)
    }

    @Test
    fun acceptsRawNalsAndIgnoresEmptyBuffers() {
        val result = requireNotNull(AvcConfig.split(byteArrayOf(), sps, pps))
        assertArrayEquals(start4 + sps, result.sps)
        assertArrayEquals(start4 + pps, result.pps)
    }

    @Test
    fun missingPpsOrSpsAndEmptyConfigReturnNull() {
        assertNull(AvcConfig.split(start4 + sps))
        assertNull(AvcConfig.split(start3 + pps))
        assertNull(AvcConfig.split())
        assertNull(AvcConfig.split(byteArrayOf(), start4))
    }
}
