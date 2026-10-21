package dev.openstream.app.irl

import dev.openstream.app.encoder.EncodedAccessUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtmpPublishGateTest {
    private val config = unit(byteArrayOf(0, 0, 1, 0x67, 0x64, 0, 0x1f, 0, 0, 0, 1, 0x68, 1), flags = 2)
    private val video = unit(byteArrayOf(0, 0, 0, 1, 0x65, 1), flags = 1)
    private val audio = unit(byteArrayOf(0x21, 1))

    @Test
    fun connectionStartsOnceAfterValidConfigAndMediaWaitsForPublishAcceptance() {
        val gate = RtmpPublishGate()

        assertFalse(gate.startConnection())
        assertFalse(gate.onConnected())
        assertFalse(gate.canSend(video))
        assertFalse(gate.canSend(audio))

        val parsed = gate.videoConfig(config)!!
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 0x64, 0, 0x1f), parsed.sps)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x68, 1), parsed.pps)
        assertTrue(gate.startConnection())
        assertFalse(gate.startConnection())
        assertFalse(gate.canSend(video))
        assertFalse(gate.canSend(audio))

        assertTrue(gate.onConnected())
        assertFalse(gate.onConnected())
        assertTrue(gate.canSend(video))
        assertTrue(gate.canSend(audio))
    }

    @Test
    fun malformedConfigAndUnflaggedConfigCannotStartConnection() {
        val gate = RtmpPublishGate()

        assertNull(gate.videoConfig(config.copy(flags = 0)))
        assertNull(gate.videoConfig(unit(byteArrayOf(0, 0, 1, 0x67, 1), flags = 2)))
        assertNull(gate.videoConfig(unit(byteArrayOf(0x68, 1), flags = 2)))
        assertNull(gate.videoConfig(unit(byteArrayOf(), flags = 2)))
        assertFalse(gate.startConnection())
        assertFalse(gate.onConnected())
        assertNotNull(gate.videoConfig(config))
        assertTrue(gate.startConnection())
    }

    @Test
    fun repeatedConfigCanUpdatePacketizerWithoutOpeningAnotherConnection() {
        val gate = connectedGate()
        val changedConfig = config.copy(data = config.data + byteArrayOf(3))

        assertNotNull(gate.videoConfig(changedConfig))
        assertFalse(gate.startConnection())
        assertFalse(gate.canSend(changedConfig))
        assertTrue(gate.canSend(video))
    }

    @Test
    fun encoderFailuresAreDroppedEvenWhenTheyContainValidConfigOrMedia() {
        val waiting = RtmpPublishGate()
        assertNull(waiting.videoConfig(config.copy(encoderFailure = true)))
        assertFalse(waiting.startConnection())

        val connected = connectedGate()
        assertNull(connected.videoConfig(config.copy(encoderFailure = true)))
        assertFalse(connected.canSend(video.copy(encoderFailure = true)))
        assertFalse(connected.canSend(audio.copy(encoderFailure = true)))
        assertTrue(connected.canSend(video))
    }

    @Test
    fun codecConfigAndEmptyBuffersAreNeverSentAsMedia() {
        val gate = connectedGate()

        assertFalse(gate.canSend(config))
        assertFalse(gate.canSend(config.copy(flags = 3)))
        assertFalse(gate.canSend(unit(byteArrayOf(0x11, 0x90.toByte()), flags = 2)))
        assertFalse(gate.canSend(unit(byteArrayOf())))
        assertTrue(gate.canSend(video.copy(flags = 9)))
    }

    @Test
    fun localDisconnectSuppressesLateCallbacksAndNotifiesClosureOnce() {
        val gate = connectedGate()
        gate.onBitrate(3_500_000)

        gate.disconnect()
        gate.disconnect()

        assertFalse(gate.isConnected)
        assertEquals(0L, gate.sentBitrate)
        assertFalse(gate.canSend(video))
        assertFalse(gate.canSend(audio))
        assertNull(gate.videoConfig(config))
        assertFalse(gate.startConnection())
        assertFalse(gate.onConnected())
        assertFalse(gate.onFailed())
        gate.onBitrate(123)
        assertEquals(0L, gate.sentBitrate)
        assertTrue(gate.onDisconnected())
        assertFalse(gate.onDisconnected())
        gate.disconnect()
        assertFalse(gate.onDisconnected())
        assertNull(gate.videoConfig(config))
    }

    @Test
    fun disconnectBeforeConfigStillAllowsOneLocalClosureNotification() {
        val gate = RtmpPublishGate()

        gate.disconnect()

        assertNull(gate.videoConfig(config))
        assertFalse(gate.startConnection())
        assertFalse(gate.onConnected())
        assertFalse(gate.onFailed())
        assertTrue(gate.onDisconnected())
        assertFalse(gate.onDisconnected())
    }

    @Test
    fun failureNotifiesOnceAndRequiresANewTransportForRetry() {
        val gate = connectedGate()
        gate.onBitrate(3_500_000)

        assertTrue(gate.onFailed())
        assertFalse(gate.onFailed())
        assertFalse(gate.isConnected)
        assertEquals(0L, gate.sentBitrate)
        assertNull(gate.videoConfig(config))
        assertFalse(gate.startConnection())
        assertFalse(gate.onConnected())
        assertFalse(gate.canSend(video))
        assertFalse(gate.canSend(audio))
        assertFalse(gate.onDisconnected())

        gate.disconnect()
        assertTrue(gate.onDisconnected())
    }

    @Test
    fun failureWhileConnectingSuppressesAQueuedSuccess() {
        val gate = RtmpPublishGate()
        gate.videoConfig(config)
        gate.startConnection()

        assertTrue(gate.onFailed())
        assertFalse(gate.onConnected())
        assertFalse(gate.canSend(video))
    }

    @Test
    fun bitrateOnlyTracksAcceptedPublishingAndResetsOnClosure() {
        val gate = RtmpPublishGate()
        gate.onBitrate(100)
        assertEquals(0L, gate.sentBitrate)
        gate.videoConfig(config)
        gate.startConnection()
        gate.onBitrate(200)
        assertEquals(0L, gate.sentBitrate)

        gate.onConnected()
        gate.onBitrate(3_500_000)
        assertEquals(3_500_000L, gate.sentBitrate)
        gate.onBitrate(-1)
        assertEquals(0L, gate.sentBitrate)
        gate.onBitrate(123)
        gate.disconnect()
        gate.onBitrate(456)
        assertEquals(0L, gate.sentBitrate)
    }

    @Test
    fun failureMessagesNeverForwardUntrustedServerDescriptions() {
        val failures = mapOf(
            "Publish rejected: rtmps://host/app/secret-key" to LiveFailure.AUTH,
            "SSLHandshakeException: rtmps://host/app/secret-key" to LiveFailure.CONFIG,
            "Broken pipe: rtmps://host/app/secret-key" to LiveFailure.TRANSIENT,
        )
        failures.forEach { (reason, expected) ->
            val failure = FailureClassifier.classify(reason)
            assertEquals(expected, failure)
            val message = rtmpFailureMessage(failure)
            assertFalse(message.contains("secret-key"))
            assertFalse(message.contains("rtmps://"))
        }
    }

    private fun connectedGate() = RtmpPublishGate().apply {
        videoConfig(config)
        startConnection()
        onConnected()
    }

    private fun unit(data: ByteArray, flags: Int = 0) = EncodedAccessUnit(data, 123_456L, flags)
}
