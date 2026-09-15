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

    @Test
    fun bitrateMbpsIsDerivedFromBitrateBps() {
        assertEquals(12, StreamConfig.Default1080p30.bitrateMbps)
        assertEquals(8, StreamConfig.Fallback720p30.bitrateMbps)
    }

    @Test
    fun bitrateCoercionClampsPairingValuesIntoBounds() {
        fun coerceBitrate(bitrateMbps: Int?): Int {
            return (bitrateMbps ?: StreamConfig.Default1080p30.bitrateMbps)
                .coerceIn(StreamConfig.MIN_BITRATE_MBPS, StreamConfig.MAX_BITRATE_MBPS)
        }
        assertEquals(12, coerceBitrate(null))
        assertEquals(12, coerceBitrate(12))
        assertEquals(StreamConfig.MIN_BITRATE_MBPS, coerceBitrate(1))
        assertEquals(StreamConfig.MAX_BITRATE_MBPS, coerceBitrate(100))
    }

}
