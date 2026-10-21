package dev.openstream.app.irl

import android.media.MediaCodec
import dev.openstream.app.camera.CameraLens
import dev.openstream.app.encoder.EncodedAccessUnit
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Serial executor for session state; every state change runs on it. */
interface SessionScheduler {
    fun execute(task: () -> Unit)

    /** Runs [task] after [delayMs]; the returned function cancels it. */
    fun schedule(delayMs: Long, task: () -> Unit): () -> Unit
}

class ExecutorSessionScheduler(private val executor: ScheduledExecutorService) : SessionScheduler {
    override fun execute(task: () -> Unit) {
        runCatching { executor.execute(task) }
    }

    override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
        val future = runCatching { executor.schedule(task, delayMs, TimeUnit.MILLISECONDS) }.getOrNull()
        return { future?.cancel(false) }
    }
}

/**
 * Capture + encoders feeding a session. Encoders keep running across transport
 * reconnects so a new connection only needs cached config and a fresh keyframe.
 */
interface IrlMediaPipeline {
    /**
     * Starts capture and encoding. Returns the audio parameters actually in use,
     * or null when audio is unavailable (e.g. microphone permission missing).
     * Throws when video cannot start.
     */
    fun start(
        video: LiveVideoParams,
        audio: LiveAudioParams,
        onVideo: (EncodedAccessUnit) -> Unit,
        onAudio: (EncodedAccessUnit) -> Unit,
    ): LiveAudioParams?

    fun stop()
    fun setVideoBitrate(bitrate: Int)
    fun requestKeyframe()
    fun setMuted(muted: Boolean)
}

/**
 * IRL publishing state machine: Idle -> Connecting -> Live <-> Reconnecting,
 * ending in Idle (user stop) or Error (auth/config/local failure).
 *
 * Each transport instance gets a generation; callbacks from an older generation
 * are ignored, so a late failure from a replaced connection cannot tear down the
 * current one. Media is forwarded on encoder threads directly to the current
 * live transport, rebased onto that connection's [LiveTimeline]; everything else
 * is serialized on [scheduler].
 */
class IrlSession(
    private val transportFactory: (LiveSettings) -> LiveTransport,
    private val media: IrlMediaPipeline,
    private val scheduler: SessionScheduler,
    private val clock: () -> Long,
    private val mediaClock: () -> MediaClockSample,
    private val onHud: (LiveHud) -> Unit,
    random: () -> Double = { kotlin.random.Random.nextDouble() },
) {
    private val reconnect = ReconnectPolicy(clock, random)

    // Session-thread state.
    private var settings: LiveSettings? = null
    private var publishUrl: String? = null
    private var videoParams: LiveVideoParams? = null
    private var audioParams: LiveAudioParams = LiveAudioParams()
    private var transport: LiveTransport? = null
    private var generation = 0L
    private var bitrate: AdaptiveBitratePolicy? = null
    private var appliedBitrate = 0
    private var lastDropped = 0L
    private var liveSinceMs: Long? = null
    private var cancelRetry: (() -> Unit)? = null
    private var cancelConnectTimeout: (() -> Unit)? = null
    private var cancelTick: (() -> Unit)? = null
    private var lastFrameCount = 0L
    private var lastTickMs = 0L
    private var networkKnown = false
    private var networkId: Long? = null
    private var lastNetworkId: Long? = null
    private var hud = LiveHud()

    // Shared with encoder threads. Transport and timeline swap together.
    private class LiveLink(val transport: LiveTransport, val timeline: LiveTimeline)
    @Volatile private var link: LiveLink? = null
    private val liveTransport: LiveTransport? get() = link?.transport
    @Volatile private var videoConfig: EncodedAccessUnit? = null
    @Volatile private var audioConfig: EncodedAccessUnit? = null
    @Volatile private var mediaGeneration = 0L
    private val videoFrames = AtomicLong()

    private val active: Boolean
        get() = settings != null

    fun start(settings: LiveSettings) = scheduler.execute { startOnSession(settings) }

    fun stop() = scheduler.execute { stopOnSession(LiveStatus.Idle) }

    fun setMuted(muted: Boolean) = scheduler.execute {
        media.setMuted(muted)
        publish(hud.copy(muted = muted))
    }

    fun setLens(lens: CameraLens?) = scheduler.execute { publish(hud.copy(lens = lens)) }

    fun setThermalWarning(warning: Boolean) = scheduler.execute {
        if (hud.thermalWarning != warning) publish(hud.copy(thermalWarning = warning))
    }

    /**
     * Default network changed. [id] identifies the network (null: none). A new
     * network resets backoff and reconnects at once: the old socket is usually
     * bound to an interface that is going away.
     */
    fun onNetworkChanged(id: Long?, label: String) = scheduler.execute {
        val previous = networkId
        val wasKnown = networkKnown
        networkKnown = true
        networkId = id
        if (hud.network != label) publish(hud.copy(network = label))
        if (id == null || !wasKnown || !active) {
            if (id != null) lastNetworkId = id
            return@execute
        }
        val switched = id != lastNetworkId
        lastNetworkId = id
        when (hud.status) {
            is LiveStatus.Live -> if (switched) reconnectNow("Network changed")
            is LiveStatus.Connecting, is LiveStatus.Reconnecting ->
                if (switched || previous == null) reconnectNow("Network changed")
            else -> Unit
        }
    }

    private fun startOnSession(next: LiveSettings) {
        if (active) stopOnSession(LiveStatus.Idle)
        val url = try {
            next.publishUrl()
        } catch (error: IllegalArgumentException) {
            publish(hud.copy(status = LiveStatus.Error(error.message ?: "Invalid stream settings.")))
            return
        }
        val quality = next.quality
        val video = LiveVideoParams(quality.width, quality.height, quality.fps, quality.bitrate(next.platform), KEYFRAME_SECONDS)
        val requestedAudio = LiveAudioParams(sampleRate = 48_000, channelCount = 2, bitrate = 128_000)
        val mediaGen = ++mediaGeneration
        videoConfig = null
        audioConfig = null
        videoFrames.set(0)
        val audio = try {
            media.start(
                video,
                requestedAudio,
                onVideo = { onVideoUnit(mediaGen, it) },
                onAudio = { onAudioUnit(mediaGen, it) },
            )
        } catch (error: Exception) {
            mediaGeneration++
            runCatching { media.stop() }
            publish(hud.copy(status = LiveStatus.Error("Could not start camera or encoder: ${error.message ?: error.javaClass.simpleName}")))
            return
        }
        settings = next
        publishUrl = url
        videoParams = video
        audioParams = audio ?: requestedAudio
        bitrate = AdaptiveBitratePolicy(video.bitrate, clock)
        appliedBitrate = video.bitrate
        liveSinceMs = null
        reconnect.reset()
        lastNetworkId = networkId
        lastFrameCount = 0
        lastTickMs = clock()
        publish(hud.copy(targetBitrate = video.bitrate, currentBitrate = video.bitrate, sentBitrate = 0, fps = 0, droppedFrames = 0))
        connect()
        scheduleTick()
    }

    private fun stopOnSession(status: LiveStatus) {
        cancelRetry?.invoke(); cancelRetry = null
        cancelConnectTimeout?.invoke(); cancelConnectTimeout = null
        cancelTick?.invoke(); cancelTick = null
        dropTransport()
        if (active) {
            mediaGeneration++
            runCatching { media.stop() }
        }
        settings = null
        publishUrl = null
        videoParams = null
        bitrate = null
        liveSinceMs = null
        videoConfig = null
        audioConfig = null
        publish(hud.copy(status = status, sentBitrate = 0, fps = 0))
    }

    private fun connect() {
        val url = publishUrl ?: return
        val video = videoParams?.copy(bitrate = appliedBitrate) ?: return
        val current = settings ?: return
        cancelRetry?.invoke(); cancelRetry = null
        dropTransport()
        val gen = ++generation
        val next = try {
            transportFactory(current)
        } catch (error: Exception) {
            fail(LiveFailure.CONFIG)
            return
        }
        transport = next
        lastDropped = 0
        val status = hud.status
        publish(hud.copy(status = if (status is LiveStatus.Reconnecting) status.copy(retryInSeconds = 0) else LiveStatus.Connecting))
        cancelConnectTimeout = scheduler.schedule(CONNECT_TIMEOUT_MS) {
            if (gen == generation && liveTransport == null) onTransportFailed(gen, LiveFailure.TRANSIENT)
        }
        val listener = object : LiveTransportListener {
            override fun onConnected() = scheduler.execute { onTransportConnected(gen) }
            override fun onFailed(reason: String, failure: LiveFailure) =
                scheduler.execute { onTransportFailed(gen, failure) }
            override fun onDisconnected() = Unit
        }
        try {
            next.connect(url, video, audioParams, listener)
        } catch (error: Exception) {
            onTransportFailed(gen, FailureClassifier.classify(error.message.orEmpty()))
            return
        }
        // RTMP needs codec info before its publish handshake completes.
        replayConfig(next)
    }

    private fun onTransportConnected(gen: Long) {
        if (gen != generation || !active) return
        val connected = transport ?: return
        cancelConnectTimeout?.invoke(); cancelConnectTimeout = null
        reconnect.reset()
        replayConfig(connected)
        // Every connection starts its own timeline at the next keyframe.
        link = LiveLink(connected, LiveTimeline(mediaClock))
        media.requestKeyframe()
        val started = liveSinceMs ?: clock().also { liveSinceMs = it }
        publish(hud.copy(status = LiveStatus.Live(started)))
    }

    private fun onTransportFailed(gen: Long, failure: LiveFailure) {
        if (gen != generation || !active) return
        val delay = reconnect.next(failure) ?: return fail(failure)
        dropTransport()
        val label = if (hud.status is LiveStatus.Live) "Connection lost" else "Connection failed"
        publish(hud.copy(status = LiveStatus.Reconnecting(reconnect.remainingSeconds(), if (networkKnown && networkId == null) "No network" else label)))
        cancelRetry = scheduler.schedule(delay) {
            cancelRetry = null
            if (active && hud.status is LiveStatus.Reconnecting) connect()
        }
    }

    private fun fail(failure: LiveFailure) {
        // Server reasons can echo credentials; show only a classified message.
        val message = when (failure) {
            LiveFailure.AUTH -> "Stream key rejected. Check your key and try again."
            LiveFailure.CONFIG -> "Server settings rejected. Check the server URL."
            LiveFailure.TRANSIENT -> "Connection failed."
        }
        stopOnSession(LiveStatus.Error(message))
    }

    private fun reconnectNow(reason: String) {
        reconnect.reset()
        dropTransport()
        publish(hud.copy(status = LiveStatus.Reconnecting(0, reason)))
        connect()
    }

    private fun dropTransport() {
        cancelConnectTimeout?.invoke(); cancelConnectTimeout = null
        link = null
        val old = transport ?: return
        transport = null
        generation++
        runCatching { old.disconnect() }
    }

    private fun replayConfig(target: LiveTransport) {
        videoConfig?.let { runCatching { target.sendVideo(it) } }
        audioConfig?.let { runCatching { target.sendAudio(it) } }
    }

    private fun onVideoUnit(mediaGen: Long, unit: EncodedAccessUnit) {
        if (mediaGen != mediaGeneration) return
        if (unit.encoderFailure) {
            scheduler.execute { if (mediaGen == mediaGeneration && active) stopOnSession(LiveStatus.Error("Video encoder failed.")) }
            return
        }
        if (unit.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            videoConfig = unit
            liveTransport?.sendVideo(unit)
            return
        }
        videoFrames.incrementAndGet()
        val target = link ?: return
        val keyframe = unit.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val pts = target.timeline.video(unit.presentationTimeUs, keyframe) ?: return
        target.transport.sendVideo(unit.copy(presentationTimeUs = pts))
    }

    private fun onAudioUnit(mediaGen: Long, unit: EncodedAccessUnit) {
        if (mediaGen != mediaGeneration) return
        if (unit.encoderFailure) {
            scheduler.execute { if (mediaGen == mediaGeneration && active) stopOnSession(LiveStatus.Error("Microphone or audio encoder failed.")) }
            return
        }
        if (unit.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            audioConfig = unit
            liveTransport?.sendAudio(unit)
            return
        }
        val target = link ?: return
        val pts = target.timeline.audio(unit.presentationTimeUs) ?: return
        target.transport.sendAudio(unit.copy(presentationTimeUs = pts))
    }

    private fun scheduleTick() {
        cancelTick = scheduler.schedule(TICK_MS) {
            cancelTick = null
            if (!active) return@schedule
            tick()
            scheduleTick()
        }
    }

    private fun tick() {
        val now = clock()
        val frames = videoFrames.get()
        val elapsed = (now - lastTickMs).coerceAtLeast(1)
        val fps = ((frames - lastFrameCount) * 1000 / elapsed).toInt()
        lastFrameCount = frames
        lastTickMs = now
        var next = hud.copy(fps = fps)
        val live = liveTransport
        if (live != null) {
            val stats = runCatching { live.stats() }.getOrNull()
            if (stats != null) {
                val newDrops = (stats.droppedVideoFrames - lastDropped).coerceAtLeast(0)
                lastDropped = stats.droppedVideoFrames
                val policy = bitrate
                if (policy != null) {
                    val target = policy.sample(stats.congested, stats.queuedFrames, newDrops)
                    if (target != appliedBitrate) {
                        appliedBitrate = target
                        media.setVideoBitrate(target)
                    }
                }
                next = next.copy(
                    sentBitrate = stats.sentBitrate,
                    droppedFrames = next.droppedFrames + newDrops,
                    currentBitrate = appliedBitrate,
                )
            }
        } else {
            next = next.copy(sentBitrate = 0)
        }
        val status = next.status
        if (status is LiveStatus.Reconnecting && cancelRetry != null) {
            next = next.copy(status = status.copy(retryInSeconds = reconnect.remainingSeconds()))
        }
        publish(next)
    }

    private fun publish(next: LiveHud) {
        hud = next
        onHud(next)
    }

    companion object {
        const val KEYFRAME_SECONDS = 2
        const val TICK_MS = 1000L
        const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
