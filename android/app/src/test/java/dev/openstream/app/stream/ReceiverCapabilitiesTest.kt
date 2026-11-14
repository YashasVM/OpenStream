package dev.openstream.app.stream

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReceiverCapabilitiesTest {
    private fun receiver() = JSONObject("""{"version":1,"codecs":["video/avc","video/hevc"],"maxWidth":3840,"maxHeight":2160,"maxFps":30,"dynamicRanges":["sdr","hlg10"]}""")
    @Test fun legacyCannotAuthorizeHighModes() {
        assertTrue(ReceiverCapabilities.accepts(null, StreamConfig.Default1080p30))
        for (quality in listOf(VideoQuality.Usb4k, VideoQuality.UsbHdr, VideoQuality.Usb4kHdr)) {
            val config = StreamConfig.forQuality(quality)
            assertEquals(30, config.fps)
            assertTrue(config.requiresUsb)
            assertFalse(ReceiverCapabilities.accepts(null, config))
            assertTrue(ReceiverCapabilities.accepts(receiver(), config))
        }
    }
    @Test fun rejectsMissingHdrAndInsufficientDimensions() {
        val hdr = StreamConfig.forQuality(VideoQuality.Usb4kHdr)
        assertFalse(ReceiverCapabilities.accepts(receiver().put("dynamicRanges", org.json.JSONArray("[\"sdr\"]")), hdr))
        assertFalse(ReceiverCapabilities.accepts(receiver().put("maxWidth", 1920), hdr))
        assertFalse(ReceiverCapabilities.accepts(receiver().put("maxFps", "30"), hdr))
        assertFalse(ReceiverCapabilities.accepts(receiver().put("version", 2), hdr))
        assertEquals("hlg10", ReceiverCapabilities.session(hdr).getString("dynamicRange"))
        assertEquals("video/hevc", ReceiverCapabilities.session(hdr).getString("codec"))
    }
}
