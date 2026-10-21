package dev.openstream.app.irl

import dev.openstream.app.encoder.EncodedAccessUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class RtmpLiveTransportTest {
    @Test
    fun unusedTransportDropsMediaAndCallsAfterDisconnectAreIgnored() {
        val transport = RtmpLiveTransport()
        val unit = EncodedAccessUnit(byteArrayOf(0, 0, 0, 1, 0x65), 123L, 1)
        transport.sendVideo(unit)
        transport.sendAudio(unit)
        assertEquals(LiveTransportStats(), transport.stats())

        transport.disconnect()
        transport.disconnect()
        transport.connect(
            "rtmps://example.invalid/app/unused-key",
            LiveVideoParams(1280, 720, 30, 3_500_000),
            LiveAudioParams(),
            object : LiveTransportListener {
                override fun onConnected() = error("Disconnected transport connected")
                override fun onFailed(reason: String, failure: LiveFailure) = error("Disconnected transport failed")
                override fun onDisconnected() = error("Unused transport emitted a callback")
            },
        )
        transport.sendVideo(unit)
        transport.sendAudio(unit)
        assertEquals(LiveTransportStats(), transport.stats())
    }
}
