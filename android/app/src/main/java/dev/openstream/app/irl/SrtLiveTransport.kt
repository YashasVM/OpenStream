package dev.openstream.app.irl

import dev.openstream.app.encoder.EncodedAccessUnit
import dev.openstream.app.stream.SrtPublisherException
import dev.openstream.app.stream.SrtPublisherStats
import dev.openstream.app.stream.SrtSendResult
import dev.openstream.app.stream.SrtStreamClient

/**
 * SRT caller publisher over the native libsrt/MPEG-TS path used by OBS mode.
 *
 * The native SRT session is process-global (one socket, one muxer): OBS mode and
 * IRL must never run at the same time, and the IRL session creates a fresh
 * transport per (re)connect attempt. Listener callbacks and native calls run
 * outside [lock] so a session holding its own lock can call stats()/send*()
 * without lock-order inversion; every callback is gated on the attempt
 * generation, so late results from a superseded attempt are dropped.
 */
class SrtLiveTransport internal constructor(
    private val backend: SrtLiveBackend,
    private val startConnect: (() -> Unit) -> Unit,
    private val nowNs: () -> Long,
) : LiveTransport {
    constructor() : this(
        NativeSrtLiveBackend(),
        { work -> Thread(work, "IRL-SRT-connect").apply { isDaemon = true }.start() },
        System::nanoTime,
    )

    private val lock = Any()
    private var generation = 0L
    private var listener: LiveTransportListener? = null
    private var connected = false
    private var failed = false
    private var sampler = SrtStatsSampler(nowNs())

    override fun connect(publishUrl: String, video: LiveVideoParams, audio: LiveAudioParams, listener: LiveTransportListener) {
        val attempt = synchronized(lock) {
            connected = false
            failed = false
            this.listener = listener
            sampler = SrtStatsSampler(nowNs())
            ++generation
        }
        startConnect {
            val result = runCatching {
                require(video.width > 0 && video.height > 0 && video.fps > 0) { "Invalid video parameters" }
                require(audio.sampleRate in AAC_SAMPLE_RATES && audio.channelCount in 1..2) {
                    "Unsupported SRT audio parameters"
                }
                backend.connect(publishUrl, video) { isCurrent(attempt) }
            }
            result.fold(
                onSuccess = {
                    val callback = synchronized(lock) {
                        if (!isCurrentLocked(attempt)) return@synchronized null
                        connected = true
                        sampler = SrtStatsSampler(nowNs())
                        this.listener
                    }
                    callback?.onConnected()
                },
                onFailure = { error ->
                    val (reason, failure) = srtFailure(error)
                    fail(attempt, reason, failure)
                },
            )
        }
    }

    override fun sendVideo(accessUnit: EncodedAccessUnit) {
        val attempt = sendableAttempt() ?: return
        handleSend(attempt, backend.sendVideo(accessUnit))
    }

    override fun sendAudio(accessUnit: EncodedAccessUnit) {
        val attempt = sendableAttempt() ?: return
        handleSend(attempt, backend.sendAudio(accessUnit))
    }

    override fun stats(): LiveTransportStats {
        val attempt = sendableAttempt() ?: return LiveTransportStats()
        val counters = backend.stats()
        return synchronized(lock) {
            if (isCurrentLocked(attempt) && connected && !failed) sampler.sample(counters, nowNs()) else LiveTransportStats()
        }
    }

    override fun disconnect() {
        val callback = synchronized(lock) {
            ++generation
            connected = false
            listener.also { listener = null }
        }
        // Native teardown also aborts a blocking connect on the connect thread.
        backend.disconnect()
        callback?.onDisconnected()
    }

    private fun sendableAttempt(): Long? = synchronized(lock) { if (connected && !failed) generation else null }

    private fun isCurrent(attempt: Long): Boolean = synchronized(lock) { isCurrentLocked(attempt) }

    private fun isCurrentLocked(attempt: Long): Boolean = generation == attempt && listener != null

    private fun handleSend(attempt: Long, result: SrtSendResult) {
        if (result.recoveryRequired) fail(attempt, "SRT send failed; reconnect required", LiveFailure.TRANSIENT)
    }

    /** Reports at most one failure per connect generation. */
    private fun fail(attempt: Long, reason: String, failure: LiveFailure) {
        val callback = synchronized(lock) {
            if (!isCurrentLocked(attempt) || failed) return
            failed = true
            connected = false
            listener
        }
        callback?.onFailed(reason, failure)
    }

    private companion object {
        val AAC_SAMPLE_RATES = setOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200, 96000)
    }
}

internal interface SrtLiveBackend {
    fun connect(url: String, video: LiveVideoParams, shouldConnect: () -> Boolean)
    fun sendVideo(unit: EncodedAccessUnit): SrtSendResult
    fun sendAudio(unit: EncodedAccessUnit): SrtSendResult
    fun stats(): SrtPublisherStats
    fun disconnect()
}

private class NativeSrtLiveBackend : SrtLiveBackend {
    private val client = SrtStreamClient()
    override fun connect(url: String, video: LiveVideoParams, shouldConnect: () -> Boolean) =
        client.connectPublisher(url, video.width, video.height, video.fps, shouldConnect)
    override fun sendVideo(unit: EncodedAccessUnit) = client.sendVideoAccessUnit(unit)
    override fun sendAudio(unit: EncodedAccessUnit) = client.sendAudioAccessUnit(unit)
    override fun stats() = client.publisherStats()
    override fun disconnect() = client.disconnect()
}

internal fun srtFailure(error: Throwable): Pair<String, LiveFailure> = when {
    error is SrtPublisherException && error.message.orEmpty().startsWith("AUTH:") ->
        error.message.orEmpty().removePrefix("AUTH: ") to LiveFailure.AUTH
    error is SrtPublisherException && error.message.orEmpty().startsWith("CONFIG:") ->
        error.message.orEmpty().removePrefix("CONFIG: ") to LiveFailure.CONFIG
    error is IllegalArgumentException || error is LinkageError ||
        error.message.orEmpty().contains("Native SRT library unavailable") ->
        "Invalid SRT configuration or native SRT unavailable" to LiveFailure.CONFIG
    else -> "SRT connection failed or timed out" to LiveFailure.TRANSIENT
}

internal class SrtStatsSampler(private var previousNs: Long) {
    private var previous = SrtPublisherStats()
    private var bitrate = 0L

    fun sample(current: SrtPublisherStats, nowNs: Long): LiveTransportStats {
        val elapsed = nowNs - previousNs
        if (elapsed > 0) {
            bitrate = ((current.sentBytes - previous.sentBytes).coerceAtLeast(0).toDouble() *
                8_000_000_000.0 / elapsed).toLong()
        }
        val growing = current.bufferedPackets > previous.bufferedPackets + 16 ||
            current.bufferedMs > previous.bufferedMs + 100 ||
            current.queuedAccessUnits > previous.queuedAccessUnits + 5
        val congested = growing || current.droppedPackets > previous.droppedPackets
        previous = current
        previousNs = nowNs
        // Native drops count TS packets, not video frames; do not misreport them as frames.
        return LiveTransportStats(sentBitrate = bitrate, queuedFrames = current.queuedAccessUnits, congested = congested)
    }
}
