package dev.openstream.app.stream

import dev.openstream.app.encoder.CodecPreference
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamConfigTest {
    @Test
    fun defaultProfileIsBoundedForSustainablePhoneStreaming() {
        val config = StreamConfig.Default1080p30

        assertEquals(30, config.fps)
        assertEquals(12, config.bitrateMbps)
        assertEquals(CodecPreference.ForceAvc, config.codecPreference)
        assertEquals(1, config.audioChannelCount)
        assertEquals(128_000, config.audioBitrate)
    }

    @Test
    fun bitrateBoundsMatchPairingAndManualTargetValidation() {
        assertEquals(8, StreamConfig.MIN_BITRATE_MBPS)
        assertEquals(50, StreamConfig.MAX_BITRATE_MBPS)
    }

}
