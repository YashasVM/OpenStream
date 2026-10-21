package dev.openstream.app.irl

import dev.openstream.app.encoder.EncodedAccessUnit
import dev.openstream.app.stream.SrtPublisherException
import dev.openstream.app.stream.SrtPublisherStats
import dev.openstream.app.stream.SrtSendResult
import dev.openstream.app.stream.SrtStreamClient
import org.junit.Assert.*
import org.junit.Test

class SrtLiveTransportTest {
    private val video = LiveVideoParams(1280, 720, 30, 3_500_000)
    private val audio = LiveAudioParams()
    private val url = "srt://relay.example:9000?mode=caller&irl=1"
    private val unit = EncodedAccessUnit(byteArrayOf(0, 0, 0, 1, 0x65), 12345, 1)

    private class Backend : SrtLiveBackend {
        val connections = mutableListOf<String>()
        val videos = mutableListOf<EncodedAccessUnit>()
        val audios = mutableListOf<EncodedAccessUnit>()
        var error: Throwable? = null
        var duringConnect: (() -> Unit)? = null
        var result = SrtSendResult(true, 1)
        var counters = SrtPublisherStats()
        var disconnects = 0
        override fun connect(url: String, video: LiveVideoParams, shouldConnect: () -> Boolean) {
            if (!shouldConnect()) return
            connections += url
            duringConnect?.invoke()
            error?.let { throw it }
        }
        override fun sendVideo(unit: EncodedAccessUnit): SrtSendResult { videos += unit; return result }
        override fun sendAudio(unit: EncodedAccessUnit): SrtSendResult { audios += unit; return result }
        override fun stats() = counters
        override fun disconnect() { disconnects++ }
    }

    private class Listener : LiveTransportListener {
        val events = mutableListOf<String>()
        val failures = mutableListOf<LiveFailure>()
        override fun onConnected() { events += "connected" }
        override fun onFailed(reason: String, failure: LiveFailure) { events += reason; failures += failure }
        override fun onDisconnected() { events += "disconnected" }
    }

    @Test fun connectIsScheduledAndMediaIncludingCodecConfigIsForwarded() {
        val backend = Backend()
        val jobs = ArrayDeque<() -> Unit>()
        val transport = SrtLiveTransport(backend, jobs::addLast) { 0 }
        val listener = Listener()
        transport.connect(url, video, audio, listener)
        transport.sendVideo(unit)
        assertTrue(listener.events.isEmpty())
        assertTrue(backend.connections.isEmpty())
        assertTrue(backend.videos.isEmpty())
        jobs.removeFirst().invoke()
        val config = unit.copy(flags = 2)
        transport.sendVideo(config)
        transport.sendVideo(unit)
        transport.sendAudio(config)
        transport.sendAudio(unit)
        assertEquals(listOf("connected"), listener.events)
        assertEquals(listOf(config, unit), backend.videos)
        assertEquals(listOf(config, unit), backend.audios)
        transport.disconnect()
        transport.disconnect()
        transport.sendVideo(unit)
        transport.sendAudio(unit)
        assertEquals(listOf("connected", "disconnected"), listener.events)
        assertEquals(2, backend.videos.size)
        assertEquals(2, backend.audios.size)
    }

    @Test fun disconnectCancelsQueuedConnectAndSuppressesLateCallbacks() {
        val backend = Backend()
        val jobs = ArrayDeque<() -> Unit>()
        val transport = SrtLiveTransport(backend, jobs::addLast) { 0 }
        val listener = Listener()
        transport.connect(url, video, audio, listener)
        transport.disconnect()
        jobs.removeFirst().invoke()
        assertTrue(backend.connections.isEmpty())
        assertEquals(listOf("disconnected"), listener.events)
        assertEquals(LiveTransportStats(), transport.stats())
    }

    @Test fun disconnectDuringConnectSuppressesSuccessAndFailure() {
        for (failure in listOf(null, SrtPublisherException("AUTH: SRT passphrase rejected"))) {
            val backend = Backend().apply { error = failure }
            val jobs = ArrayDeque<() -> Unit>()
            val transport = SrtLiveTransport(backend, jobs::addLast) { 0 }
            val listener = Listener()
            backend.duringConnect = transport::disconnect
            transport.connect(url, video, audio, listener)
            jobs.removeFirst().invoke()
            assertEquals(listOf("disconnected"), listener.events)
        }
    }

    @Test fun queuedOldAttemptCannotReplaceNewConnectionEvenWhenJobsRunBackwards() {
        val backend = Backend()
        val jobs = ArrayDeque<() -> Unit>()
        val transport = SrtLiveTransport(backend, jobs::addLast) { 0 }
        val old = Listener()
        val current = Listener()
        transport.connect(url, video, audio, old)
        transport.connect(url.replace("9000", "9001"), video, audio, current)
        jobs.removeLast().invoke()
        jobs.removeFirst().invoke()
        assertEquals(listOf(url.replace("9000", "9001")), backend.connections)
        assertTrue(old.events.isEmpty())
        assertEquals(listOf("connected"), current.events)
    }

    @Test fun recoveryFailureIsReportedOncePerGenerationAcrossAudioAndVideo() {
        val backend = Backend()
        val transport = SrtLiveTransport(backend, { it() }) { 0 }
        val listener = Listener()
        transport.connect(url, video, audio, listener)
        backend.result = SrtSendResult(false, 1, recoveryRequired = true)
        transport.sendAudio(unit)
        transport.sendVideo(unit)
        transport.sendAudio(unit)
        assertEquals(listOf(LiveFailure.TRANSIENT), listener.failures)
        transport.connect(url, video, audio, listener)
        backend.result = SrtSendResult(false, 2, recoveryRequired = true)
        transport.sendVideo(unit)
        assertEquals(listOf(LiveFailure.TRANSIENT, LiveFailure.TRANSIENT), listener.failures)
    }

    @Test fun sendResultFromASupersededAttemptIsNotReportedToTheNextAttempt() {
        val old = Listener()
        val current = Listener()
        lateinit var transport: SrtLiveTransport
        // A reconnect lands while the old attempt's native send is in flight.
        val backend = object : SrtLiveBackend by Backend() {
            override fun sendVideo(unit: EncodedAccessUnit): SrtSendResult {
                transport.connect(url, video, audio, current)
                return SrtSendResult(false, 1, recoveryRequired = true)
            }
        }
        transport = SrtLiveTransport(backend, { it() }) { 0 }
        transport.connect(url, video, audio, old)
        transport.sendVideo(unit)
        assertEquals(listOf("connected"), old.events)
        assertEquals(listOf("connected"), current.events)
    }

    @Test fun listenerCallbacksMayReenterTheTransport() {
        val backend = Backend()
        val transport = SrtLiveTransport(backend, { it() }) { 0 }
        val listener = object : LiveTransportListener {
            val stats = mutableListOf<LiveTransportStats>()
            override fun onConnected() { stats += transport.stats(); transport.sendVideo(unit) }
            override fun onFailed(reason: String, failure: LiveFailure) { transport.disconnect() }
            override fun onDisconnected() { stats += transport.stats() }
        }
        transport.connect(url, video, audio, listener)
        assertEquals(listOf(unit), backend.videos)
        backend.result = SrtSendResult(false, 1, recoveryRequired = true)
        transport.sendAudio(unit)
        assertEquals(1, backend.disconnects)
        assertEquals(2, listener.stats.size)
    }

    @Test fun publishFailuresAreClassifiedWithoutExposingArbitraryExceptionText() {
        val cases = listOf(
            SrtPublisherException("AUTH: SRT passphrase rejected (reject reason 10)") to LiveFailure.AUTH,
            SrtPublisherException("AUTH: SRT publishing rejected; check stream ID and credentials") to LiveFailure.AUTH,
            SrtPublisherException("CONFIG: Could not set SRT encryption key length") to LiveFailure.CONFIG,
            SrtPublisherException("TRANSIENT: SRT connection failed or timed out") to LiveFailure.TRANSIENT,
            IllegalArgumentException("srt://host?passphrase=private-secret") to LiveFailure.CONFIG,
            IllegalStateException("Native SRT library unavailable: missing") to LiveFailure.CONFIG,
            UnsatisfiedLinkError("missing JNI") to LiveFailure.CONFIG,
            java.net.SocketTimeoutException("private-secret") to LiveFailure.TRANSIENT,
            IllegalStateException("server echoed streamid=private-secret") to LiveFailure.TRANSIENT,
        )
        for ((error, expected) in cases) {
            val backend = Backend().apply { this.error = error }
            val transport = SrtLiveTransport(backend, { it() }) { 0 }
            val listener = Listener()
            transport.connect(url, video, audio, listener)
            assertEquals(listOf(expected), listener.failures)
            assertFalse(listener.events.single().contains("private-secret"))
            transport.sendVideo(unit)
            assertTrue(backend.videos.isEmpty())
        }
    }

    @Test fun invalidMediaParametersFailBeforeNativeConnect() {
        val backend = Backend()
        val transport = SrtLiveTransport(backend, { it() }) { 0 }
        val listener = Listener()
        transport.connect(url, video.copy(fps = 0), audio, listener)
        assertEquals(listOf(LiveFailure.CONFIG), listener.failures)
        assertTrue(backend.connections.isEmpty())
    }

    @Test fun statsMeasureSocketBitrateAndBufferGrowthThenResetOnReconnect() {
        var timeNs = 0L
        val backend = Backend()
        val transport = SrtLiveTransport(backend, { it() }) { timeNs }
        transport.connect(url, video, audio, Listener())
        backend.counters = SrtPublisherStats(sentBytes = 125_000, queuedAccessUnits = 2)
        timeNs += 1_000_000_000
        assertEquals(LiveTransportStats(sentBitrate = 1_000_000, queuedFrames = 2), transport.stats())
        backend.counters = SrtPublisherStats(sentBytes = 250_000, bufferedPackets = 40, bufferedMs = 150, queuedAccessUnits = 8)
        timeNs += 1_000_000_000
        val growing = transport.stats()
        assertTrue(growing.congested)
        assertEquals(8, growing.queuedFrames)
        timeNs += 1_000_000_000
        assertFalse(transport.stats().congested)
        backend.counters = backend.counters.copy(droppedPackets = 2)
        timeNs += 1_000_000_000
        val dropped = transport.stats()
        assertTrue(dropped.congested)
        assertEquals(0L, dropped.droppedVideoFrames)
        transport.connect(url, video, audio, Listener())
        backend.counters = SrtPublisherStats(sentBytes = 12_500)
        timeNs += 1_000_000_000
        assertEquals(100_000L, transport.stats().sentBitrate)
    }

    @Test fun aCancelledNativePublisherDoesNotInitializeTheNativeLibrary() {
        SrtStreamClient().connectPublisher(url, 1280, 720, 30) { false }
    }
}
