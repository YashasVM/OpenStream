package dev.openstream.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Pcm16AudioLevelTest {
    @Test
    fun `silence reports the meter floor`() {
        val level = Pcm16AudioLevel.measure(ByteArray(16), 16)

        assertEquals(AudioLevel.MIN_DBFS, level.rmsDbfs, 0f)
        assertEquals(AudioLevel.MIN_DBFS, level.peakDbfs, 0f)
    }

    @Test
    fun `full scale pcm reports zero dbfs`() {
        val level = Pcm16AudioLevel.measure(byteArrayOf(0xff.toByte(), 0x7f), 2)

        assertTrue(level.peakDbfs > -0.01f)
        assertTrue(level.rmsDbfs > -0.01f)
    }

    @Test
    fun `partial sample bytes are ignored`() {
        val level = Pcm16AudioLevel.measure(byteArrayOf(0x00, 0x40, 0x7f), 3)

        assertEquals(-6.02f, level.peakDbfs, 0.05f)
    }
}
