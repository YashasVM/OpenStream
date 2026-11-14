package dev.openstream.app.encoder

import android.media.MediaCodecInfo.CodecProfileLevel
import org.junit.Assert.*
import org.junit.Test

class HlgEncoderTest {
    @Test fun main10RequiresTenBitProfileRatherThanHevcMain() {
        assertFalse(supportsMain10(listOf(CodecProfileLevel.HEVCProfileMain)))
        assertTrue(supportsMain10(listOf(CodecProfileLevel.HEVCProfileMain10)))
        assertEquals("video/hevc", CodecPreference.HevcMain10.advertisedMimeType())
        assertEquals("video/avc", CodecPreference.ForceAvc.advertisedMimeType())
    }

    @Test fun hdrOutputRejectsMissingOrDowngradedMetadata() {
        assertTrue(isHlgMain10Output(2, 6, 7, 2))
        assertFalse(isHlgMain10Output(1, 6, 7, 2))
        assertFalse(isHlgMain10Output(2, 1, 7, 2))
        assertFalse(isHlgMain10Output(2, 6, 3, 2))
        assertFalse(isHlgMain10Output(2, 6, 7, 1))
        assertFalse(isHlgMain10Output(null, 6, 7, 2))
        assertFalse(isHlgMain10Output(2, null, 7, 2))
    }
}
