package dev.openstream.app.irl

import com.pedro.common.AudioCodec
import com.pedro.common.frame.MediaFrame
import com.pedro.rtmp.flv.FlvPacket
import com.pedro.rtmp.flv.FlvType
import com.pedro.rtmp.flv.audio.packet.AacPacket
import com.pedro.rtmp.flv.video.packet.H264Packet
import java.nio.ByteBuffer
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercise RootEncoder 2.8.1's real packetizers without MediaCodec.BufferInfo. */
class RtmpPacketFormatTest {
    @Test
    fun annexBConfigProducesAvcSequenceHeaderAndLengthPrefixedKeyframe() = runPacketizer {
        val config = AvcConfig.split(bytes(
            0, 0, 1, 0x67, 0x42, 0, 0x1e, 0x80,
            0, 0, 0, 1, 0x68, 0xce, 0x38, 0x80,
        ))!!
        val packetizer = H264Packet()
        packetizer.sendVideoInfo(ByteBuffer.wrap(config.sps), ByteBuffer.wrap(config.pps))
        val packets = mutableListOf<FlvPacket>()

        packetizer.createFlvPacket(frame(bytes(0, 0, 0, 1, 0x65, 0x12, 0x34), video = true)) {
            packets += it
        }

        assertEquals(2, packets.size)
        assertEquals(FlvType.VIDEO, packets[0].type)
        assertEquals(123L, packets[0].timeStamp)
        assertArrayEquals(bytes(
            0x17, 0, 0, 0, 0,
            1, 0x42, 0, 0x1e, 0xff, 0xe1, 0, 5, 0x67, 0x42, 0, 0x1e, 0x80,
            1, 0, 4, 0x68, 0xce, 0x38, 0x80,
        ), packets[0].buffer)
        assertArrayEquals(bytes(0x17, 1, 0, 0, 0, 0, 0, 0, 3, 0x65, 0x12, 0x34), packets[1].buffer)
    }

    @Test
    fun stereoAudioInfoCreatesAacConfigBeforeTheFirstRawPacket() = runPacketizer {
        val packetizer = AacPacket()
        packetizer.sendAudioInfo(48_000, true, AudioCodec.AAC)
        val packets = mutableListOf<FlvPacket>()

        repeat(2) {
            packetizer.createFlvPacket(frame(bytes(0x21, 0x12, 0x34))) { packets += it }
        }

        assertEquals(3, packets.size)
        assertEquals(FlvType.AUDIO, packets[0].type)
        assertEquals(123L, packets[0].timeStamp)
        assertArrayEquals(bytes(0xaf, 0, 0x11, 0x90), packets[0].buffer)
        assertArrayEquals(bytes(0xaf, 1, 0x21, 0x12, 0x34), packets[1].buffer)
        assertArrayEquals(packets[1].buffer, packets[2].buffer)
    }

    @Test
    fun monoAudioInfoUsesOneChannelInAacConfig() = runPacketizer {
        val packetizer = AacPacket()
        packetizer.sendAudioInfo(44_100, false, AudioCodec.AAC)
        val packets = mutableListOf<FlvPacket>()

        packetizer.createFlvPacket(frame(bytes(0x21, 0x12))) { packets += it }

        assertArrayEquals(bytes(0xae, 0, 0x12, 0x08), packets[0].buffer)
        assertArrayEquals(bytes(0xae, 1, 0x21, 0x12), packets[1].buffer)
    }

    private fun frame(data: ByteArray, video: Boolean = false) = MediaFrame(
        ByteBuffer.wrap(data),
        MediaFrame.Info(0, data.size, 123_456L, video),
        if (video) MediaFrame.Type.VIDEO else MediaFrame.Type.AUDIO,
    )

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun runPacketizer(block: suspend () -> Unit) {
        // These packetizers and our collectors complete synchronously; no dispatcher is needed.
        var completed: Result<Unit>? = null
        block.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) { completed = result }
        })
        checkNotNull(completed) { "Packetizer unexpectedly suspended" }.getOrThrow()
    }
}
