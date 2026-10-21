package dev.openstream.app.irl

import android.media.MediaCodec
import dev.openstream.app.encoder.EncodedAccessUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IrlSessionTest {
    private class ManualScheduler(private val clock: FakeClock) : SessionScheduler {
        private class Task(val at: Long, val seq: Long, val run: () -> Unit) { var cancelled = false }
        private val tasks = mutableListOf<Task>()
        private var seq = 0L

        override fun execute(task: () -> Unit) { tasks += Task(clock.now, seq++, task) }

        override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
            val entry = Task(clock.now + delayMs, seq++, task)
            tasks += entry
            return { entry.cancelled = true }
        }

        /** Runs everything due up to now + [ms], advancing the clock task by task. */
        fun advance(ms: Long = 0) {
            val end = clock.now + ms
            while (true) {
                val next = tasks.filter { it.at <= end }.minWithOrNull(compareBy({ it.at }, { it.seq })) ?: break
                tasks.remove(next)
                clock.now = maxOf(clock.now, next.at)
                if (!next.cancelled) next.run()
            }
            clock.now = end
        }
    }

    private class FakeClock { var now = 1_000L }

    private class FakeTransport : LiveTransport {
        var listener: LiveTransportListener? = null
        var url: String? = null
        var videoParams: LiveVideoParams? = null
        var audioParams: LiveAudioParams? = null
        val video = mutableListOf<EncodedAccessUnit>()
        val audio = mutableListOf<EncodedAccessUnit>()
        var stats = LiveTransportStats()
        var disconnected = false

        override fun connect(publishUrl: String, video: LiveVideoParams, audio: LiveAudioParams, listener: LiveTransportListener) {
            url = publishUrl
            videoParams = video
            audioParams = audio
            this.listener = listener
        }
        override fun sendVideo(accessUnit: EncodedAccessUnit) { if (!disconnected) video += accessUnit }
        override fun sendAudio(accessUnit: EncodedAccessUnit) { if (!disconnected) audio += accessUnit }
        override fun stats() = stats
        override fun disconnect() { disconnected = true }
    }

    private class FakeMedia : IrlMediaPipeline {
        var onVideo: ((EncodedAccessUnit) -> Unit)? = null
        var onAudio: ((EncodedAccessUnit) -> Unit)? = null
        var started = 0
        var stopped = 0
        var keyframeRequests = 0
        val bitrates = mutableListOf<Int>()
        var mutedState = false
        var microphone = true
        var startError: Exception? = null

        override fun start(video: LiveVideoParams, audio: LiveAudioParams, onVideo: (EncodedAccessUnit) -> Unit, onAudio: (EncodedAccessUnit) -> Unit): LiveAudioParams? {
            started++
            startError?.let { throw it }
            this.onVideo = onVideo
            this.onAudio = onAudio
            return audio.takeIf { microphone }
        }
        override fun stop() { stopped++ }
        override fun setVideoBitrate(bitrate: Int) { bitrates += bitrate }
        override fun requestKeyframe() { keyframeRequests++ }
        override fun setMuted(muted: Boolean) { mutedState = muted }
    }

    private val clock = FakeClock()
    private var bootAheadUs = 0L
    private val scheduler = ManualScheduler(clock)
    private val media = FakeMedia()
    private val transports = mutableListOf<FakeTransport>()
    private val huds = mutableListOf<LiveHud>()
    private val session = IrlSession(
        transportFactory = { FakeTransport().also { transports += it } },
        media = media,
        scheduler = scheduler,
        clock = { clock.now },
        mediaClock = { MediaClockSample(clock.now * 1000, clock.now * 1000 + bootAheadUs) },
        onHud = { huds += it },
        random = { 0.5 },
    )
    private val settings = LiveSettings(LivePlatform.TWITCH, LivePlatform.TWITCH.server, "live_secret", LiveQuality.P720)

    private val status get() = huds.last().status
    private val videoConfig = unit(byteArrayOf(0, 0, 0, 1, 0x67, 0, 0, 0, 1, 0x68), MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
    private val audioConfig = unit(byteArrayOf(0x11, 0x90.toByte()), MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
    private val keyframe = unit(byteArrayOf(1), MediaCodec.BUFFER_FLAG_KEY_FRAME, 10)
    private val delta = unit(byteArrayOf(2), 0, 20)

    private fun unit(data: ByteArray, flags: Int, pts: Long = 0) = EncodedAccessUnit(data, pts, flags)

    private fun goLive(): FakeTransport {
        session.start(settings)
        scheduler.advance()
        media.onVideo!!(videoConfig)
        media.onAudio!!(audioConfig)
        val transport = transports.last()
        transport.listener!!.onConnected()
        scheduler.advance()
        return transport
    }

    @Test
    fun startConnectsWithQualityParamsAndGoesLive() {
        session.start(settings)
        scheduler.advance()
        assertEquals(LiveStatus.Connecting, status)
        val transport = transports.single()
        assertEquals(LiveVideoParams(1280, 720, 30, 3_500_000, 2), transport.videoParams)
        assertTrue(transport.url!!.endsWith("/app/live_secret"))

        transport.listener!!.onConnected()
        scheduler.advance()
        assertTrue(status is LiveStatus.Live)
        assertEquals(1, media.keyframeRequests)
    }

    @Test
    fun mediaBeforeLiveIsDroppedAndDeltaFramesWaitForKeyframe() {
        session.start(settings)
        scheduler.advance()
        media.onVideo!!(videoConfig)
        media.onVideo!!(delta)
        val transport = transports.single()
        // Not live yet: config is cached, media is dropped.
        assertTrue(transport.video.none { it === delta })

        transport.listener!!.onConnected()
        scheduler.advance()
        media.onVideo!!(delta)
        media.onVideo!!(keyframe)
        media.onVideo!!(delta)
        val frames = transport.video.filter { it.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 }
        // Rebased onto the connection timeline: the keyframe is the origin.
        assertEquals(listOf(keyframe.copy(presentationTimeUs = 0), delta.copy(presentationTimeUs = 10)), frames)
    }

    @Test
    fun everyConnectionRestartsTheTimelineWithAudioAlignedToVideo() {
        val first = goLive()
        // Camera PTS use BOOTTIME, an hour ahead of MONOTONIC after deep sleep; audio uses MONOTONIC.
        val boot = 3_600_000_000L
        bootAheadUs = boot
        val now = clock.now * 1000
        media.onAudio!!(unit(byteArrayOf(9), 0, now - 30_000)) // before any keyframe: dropped
        media.onVideo!!(unit(byteArrayOf(1), MediaCodec.BUFFER_FLAG_KEY_FRAME, now + boot - 50_000))
        media.onAudio!!(unit(byteArrayOf(9), 0, now - 20_000))
        media.onVideo!!(unit(byteArrayOf(2), 0, now + boot - 16_667))
        assertEquals(listOf(0L, 33_333L), first.video.media().map { it.presentationTimeUs })
        assertEquals(listOf(30_000L), first.audio.media().map { it.presentationTimeUs })

        first.listener!!.onFailed("reset", LiveFailure.TRANSIENT)
        scheduler.advance(1_000)
        val second = transports.last()
        second.listener!!.onConnected()
        scheduler.advance()
        val later = clock.now * 1000
        media.onVideo!!(unit(byteArrayOf(2), 0, later + boot - 10_000)) // delta before keyframe: dropped
        media.onVideo!!(unit(byteArrayOf(1), MediaCodec.BUFFER_FLAG_KEY_FRAME, later + boot))
        media.onAudio!!(unit(byteArrayOf(9), 0, later + 5_000))
        assertEquals(listOf(0L), second.video.media().map { it.presentationTimeUs })
        assertEquals(listOf(5_000L), second.audio.media().map { it.presentationTimeUs })
    }

    private fun List<EncodedAccessUnit>.media() = filter { it.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 }

    @Test
    fun configIsReplayedToNewTransportAfterReconnect() {
        val first = goLive()
        first.listener!!.onFailed("Connection reset", LiveFailure.TRANSIENT)
        scheduler.advance()
        val reconnecting = status as LiveStatus.Reconnecting
        assertEquals(1, reconnecting.retryInSeconds)
        assertTrue(first.disconnected)

        scheduler.advance(1_000)
        val second = transports.last()
        assertNotSame(first, second)
        // Delivered immediately after connect for handshakes that need codec info.
        assertTrue(second.video.contains(videoConfig))
        assertTrue(second.audio.contains(audioConfig))
        second.video.clear(); second.audio.clear()

        second.listener!!.onConnected()
        scheduler.advance()
        assertTrue(status is LiveStatus.Live)
        assertEquals(listOf(videoConfig), second.video)
        assertEquals(listOf(audioConfig), second.audio)
        assertEquals(2, media.keyframeRequests)
        assertEquals(1, media.started) // encoders survive reconnects
    }

    @Test
    fun authErrorStopsWithoutRetry() {
        val transport = goLive()
        transport.listener!!.onFailed("NetStream.Publish.BadName live_secret", LiveFailure.AUTH)
        scheduler.advance(60_000)
        val error = status as LiveStatus.Error
        assertFalse(error.message.contains("live_secret"))
        assertEquals(1, transports.size)
        assertEquals(1, media.stopped)
    }

    @Test
    fun configErrorStopsWithoutRetry() {
        session.start(settings)
        scheduler.advance()
        transports.single().listener!!.onFailed("certificate", LiveFailure.CONFIG)
        scheduler.advance(60_000)
        assertTrue(status is LiveStatus.Error)
        assertEquals(1, transports.size)
    }

    @Test
    fun backoffGrowsAcrossFailedAttemptsAndResetsWhenLive() {
        val first = goLive()
        first.listener!!.onFailed("reset", LiveFailure.TRANSIENT)
        scheduler.advance(1_000)
        transports.last().listener!!.onFailed("refused", LiveFailure.TRANSIENT)
        scheduler.advance()
        assertEquals(2, (status as LiveStatus.Reconnecting).retryInSeconds)
        scheduler.advance(1_000)
        assertEquals(2, transports.size) // still waiting
        scheduler.advance(1_000)
        assertEquals(3, transports.size)
        transports.last().listener!!.onConnected()
        scheduler.advance()
        transports.last().listener!!.onFailed("reset", LiveFailure.TRANSIENT)
        scheduler.advance()
        assertEquals(1, (status as LiveStatus.Reconnecting).retryInSeconds)
    }

    @Test
    fun countdownTicksDownWhileReconnecting() {
        val first = goLive()
        repeat(4) { attempt ->
            transports.last().listener!!.onFailed("refused", LiveFailure.TRANSIENT)
            scheduler.advance()
            if (attempt < 3) scheduler.advance((1_000L shl attempt))
        }
        assertEquals(8, (status as LiveStatus.Reconnecting).retryInSeconds)
        scheduler.advance(3_000)
        assertTrue((status as LiveStatus.Reconnecting).retryInSeconds in 5..6)
        assertTrue(first.disconnected)
    }

    @Test
    fun networkChangeReconnectsImmediatelyAndResetsBackoff() {
        session.onNetworkChanged(100, "Wi-Fi")
        val first = goLive()
        first.listener!!.onFailed("reset", LiveFailure.TRANSIENT)
        scheduler.advance()
        assertEquals(1, transports.size)

        session.onNetworkChanged(200, "Cellular")
        scheduler.advance()
        assertEquals(2, transports.size)
        assertEquals("Cellular", huds.last().network)
        transports.last().listener!!.onConnected()
        scheduler.advance()
        assertTrue(status is LiveStatus.Live)
    }

    @Test
    fun networkSwitchWhileLiveMovesToNewConnection() {
        session.onNetworkChanged(100, "Wi-Fi")
        val first = goLive()
        session.onNetworkChanged(100, "Wi-Fi") // capability refresh, same network
        scheduler.advance()
        assertEquals(1, transports.size)

        session.onNetworkChanged(200, "Cellular")
        scheduler.advance()
        assertTrue(first.disconnected)
        assertEquals(2, transports.size)
        assertTrue(transports.last().video.contains(videoConfig))
    }

    @Test
    fun networkReturningAfterLossRetriesNow() {
        session.onNetworkChanged(100, "Wi-Fi")
        val first = goLive()
        session.onNetworkChanged(null, "None")
        first.listener!!.onFailed("reset", LiveFailure.TRANSIENT)
        scheduler.advance()
        assertEquals("No network", (status as LiveStatus.Reconnecting).reason)
        session.onNetworkChanged(100, "Wi-Fi")
        scheduler.advance()
        assertEquals(2, transports.size)
    }

    @Test
    fun staleCallbacksFromOldTransportAreIgnored() {
        val first = goLive()
        first.listener!!.onFailed("reset", LiveFailure.TRANSIENT)
        scheduler.advance(1_000)
        val second = transports.last()
        second.listener!!.onConnected()
        scheduler.advance()
        assertTrue(status is LiveStatus.Live)

        first.listener!!.onFailed("late", LiveFailure.AUTH)
        first.listener!!.onFailed("late", LiveFailure.TRANSIENT)
        first.listener!!.onConnected()
        scheduler.advance(30_000)
        assertTrue(status is LiveStatus.Live)
        assertFalse(second.disconnected)
        assertEquals(2, transports.size)

        media.onVideo!!(keyframe)
        assertTrue(second.video.any { it.data.contentEquals(keyframe.data) })
        assertFalse(first.video.any { it.data.contentEquals(keyframe.data) })
    }

    @Test
    fun connectTimeoutCountsAsTransientFailure() {
        session.start(settings)
        scheduler.advance(IrlSession.CONNECT_TIMEOUT_MS)
        assertTrue(status is LiveStatus.Reconnecting)
        assertTrue(transports.single().disconnected)
    }

    @Test
    fun stopReturnsToIdleAndIgnoresLateCallbacks() {
        val transport = goLive()
        session.stop()
        scheduler.advance()
        assertEquals(LiveStatus.Idle, status)
        assertTrue(transport.disconnected)
        assertEquals(1, media.stopped)
        transport.listener!!.onFailed("late", LiveFailure.TRANSIENT)
        scheduler.advance(60_000)
        assertEquals(LiveStatus.Idle, status)
        assertEquals(1, transports.size)
    }

    @Test
    fun invalidSettingsReportErrorWithoutStartingMedia() {
        session.start(settings.copy(key = ""))
        scheduler.advance()
        assertEquals(LiveStatus.Error("Enter your stream key."), status)
        assertEquals(0, media.started)
    }

    @Test
    fun adaptiveBitrateLowersEncoderOnCongestion() {
        val transport = goLive()
        transport.stats = LiveTransportStats(sentBitrate = 1_000_000, queuedFrames = 2, congested = true)
        scheduler.advance(1_000)
        assertEquals(listOf(2_450_000), media.bitrates)
        assertEquals(2_450_000, huds.last().currentBitrate)
        assertEquals(3_500_000, huds.last().targetBitrate)
        assertEquals(1_000_000, huds.last().sentBitrate)
    }

    @Test
    fun hudReportsFpsAndDroppedFrames() {
        val transport = goLive()
        media.onVideo!!(keyframe)
        repeat(29) { media.onVideo!!(delta) }
        transport.stats = LiveTransportStats(droppedVideoFrames = 3)
        scheduler.advance(1_000)
        assertEquals(30, huds.last().fps)
        assertEquals(3, huds.last().droppedFrames)
    }

    @Test
    fun muteIsForwardedToMediaAndHud() {
        goLive()
        session.setMuted(true)
        scheduler.advance()
        assertTrue(media.mutedState)
        assertTrue(huds.last().muted)
    }

    @Test
    fun missingMicrophoneStreamsVideoOnly() {
        media.microphone = false
        val transport = goLive()
        assertTrue(status is LiveStatus.Live)
        assertFalse(transport.audioParams!!.enabled)
        media.onVideo!!(keyframe)
        assertEquals(1, transport.video.media().size)
    }

    @Test
    fun startFailureShowsFixedTextWithoutExceptionDetails() {
        media.startError = IllegalStateException("rtmps://ingest.example/app/live_secret")
        session.start(settings)
        scheduler.advance()
        assertEquals(LiveStatus.Error(IrlSession.START_FAILED), status)
        assertTrue(transports.isEmpty())
    }

    @Test
    fun everyAttemptUsesAFreshTransport() {
        session.onNetworkChanged(100, "Wi-Fi")
        goLive()
        transports.last().listener!!.onFailed("reset", LiveFailure.TRANSIENT)
        scheduler.advance(1_000)
        transports.last().listener!!.onConnected()
        scheduler.advance()
        session.onNetworkChanged(200, "Cellular")
        scheduler.advance()
        scheduler.advance(IrlSession.CONNECT_TIMEOUT_MS)
        scheduler.advance(2_000)
        assertTrue(transports.size >= 4)
        assertEquals(transports.size, transports.toSet().size)
        assertTrue(transports.dropLast(1).all { it.disconnected })
    }

    @Test
    fun encoderFailureEndsInError() {
        goLive()
        media.onVideo!!(EncodedAccessUnit(ByteArray(0), 0, 0, encoderFailure = true))
        scheduler.advance()
        assertTrue(status is LiveStatus.Error)
        assertEquals(1, media.stopped)
    }
}
