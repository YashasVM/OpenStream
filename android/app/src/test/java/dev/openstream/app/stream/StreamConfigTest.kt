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
        assertEquals(8, StreamConfig.Default1080p30.copy(bitrate = 8_000_000).bitrateMbps)
    }

    @Test
    fun savedQualityChangesAdvertisedVideoFormatAndBitrate() {
        val high = StreamConfig.forQuality(VideoQuality.fromPreference("High"))
        val balanced = StreamConfig.forQuality(VideoQuality.fromPreference("Balanced"))
        val saver = StreamConfig.forQuality(VideoQuality.fromPreference("DataSaver"))

        assertEquals(1920, high.width)
        assertEquals(1080, high.height)
        assertEquals(60, high.fps)
        assertEquals(50, high.bitrateMbps)
        assertEquals(12, balanced.bitrateMbps)
        assertEquals(1280, saver.width)
        assertEquals(720, saver.height)
        assertEquals(8, saver.bitrateMbps)
        assertEquals(VideoQuality.High, VideoQuality.fromPreference("unknown"))
    }
}
