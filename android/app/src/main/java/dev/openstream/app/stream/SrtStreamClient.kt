package dev.openstream.app.stream

import dev.openstream.app.encoder.EncodedAccessUnit
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

data class StreamStats(
    val accessUnitsSent: Long = 0,
    val keyframesSent: Long = 0,
    val bytesSent: Long = 0,
    val sendFailures: Long = 0,
    val lastPresentationTimeUs: Long = 0,
) {
    val secondsSent: Double
        get() = lastPresentationTimeUs / 1_000_000.0
}

data class SrtSendResult(
    val sent: Boolean,
    val sessionGeneration: Long,
    val recoveryRequired: Boolean = false,
)

class SrtStreamClient {
    @Volatile private var connected = false
    private val sessionGeneration = AtomicLong()
    private val operationLock = Any()
    private val stateLock = Any()
    val stats: StreamStats
        get() = StreamStats(
            accessUnitsSent = accessUnitsSent.get(),
            keyframesSent = keyframesSent.get(),
            bytesSent = bytesSent.get(),
            sendFailures = sendFailures.get(),
            lastPresentationTimeUs = lastPresentationTimeUs.get(),
        )

    private val accessUnitsSent = AtomicLong()
    private val keyframesSent = AtomicLong()
    private val bytesSent = AtomicLong()
    private val sendFailures = AtomicLong()
    private val lastPresentationTimeUs = AtomicLong()

    fun connect(url: String, codecMime: String, width: Int, height: Int, fps: Int) {
        require(url.startsWith("srt://")) { "OpenStream V1 expects an SRT URL" }
        synchronized(operationLock) {
            establishSession("connection") { generation ->
                connectToResolvedAddress(url, codecMime, width, height, fps, generation)
            }
        }
    }

    fun listen(url: String, codecMime: String, width: Int, height: Int, fps: Int) {
        require(url.startsWith("srt://")) { "OpenStream expects an SRT URL" }
        synchronized(operationLock) {
            // Caller and listener share one native SRT transport. establishSession()
            // performs an explicit disconnect-before-relisten via
            // beginSession(generation), so a stale listener socket can never
            // survive into the new session. Callers must not start a second
            // listen without going through establishSession/disconnect.
            establishSession("listener") { generation ->
                SrtNativeBridge.listen(url, codecMime, width, height, fps, generation)
            }
        }
    }

    fun sendVideoAccessUnit(accessUnit: EncodedAccessUnit): SrtSendResult {
        // Snapshot session state under lock; do not hold stateLock across the
        // JNI sendVideo() call so a concurrent disconnect() never blocks behind
        // a slow native mux/send. Stats are committed under lock only if the
        // generation still owns the session (bounded, no queues held across JNI).
        val generation: Long
        val wasConnected: Boolean
        synchronized(stateLock) {
            generation = sessionGeneration.get()
            wasConnected = connected
        }
        if (!wasConnected) return SrtSendResult(false, generation)
        if (accessUnit.encoderFailure) {
            // MediaCodec asynchronous failures mean the current camera surface/codec
            // session is no longer usable. Mark only the generation that observed the
            // error as failed so MainActivity's existing reconnect path can rebuild it.
            markSendFailure(generation)
            return SrtSendResult(false, generation, recoveryRequired = true)
        }
        val sent = SrtNativeBridge.sendVideo(accessUnit.data, accessUnit.presentationTimeUs, accessUnit.flags)
        val isCodecConfig = (accessUnit.flags and BUFFER_FLAG_CODEC_CONFIG) != 0
        if (sent) {
            if (!isCodecConfig) {
                synchronized(stateLock) {
                    if (sessionGeneration.get() == generation) {
                        accessUnitsSent.incrementAndGet()
                        if ((accessUnit.flags and BUFFER_FLAG_KEY_FRAME) != 0) {
                            keyframesSent.incrementAndGet()
                        }
                        bytesSent.addAndGet(accessUnit.data.size.toLong())
                        lastPresentationTimeUs.updateAndGet { current ->
                            maxOf(current, accessUnit.presentationTimeUs)
                        }
                    }
                }
            }
        } else {
            sendFailures.incrementAndGet()
            markSendFailure(generation)
        }
        return SrtSendResult(sent, generation, recoveryRequired = !sent)
    }

    fun sendAudioAccessUnit(accessUnit: EncodedAccessUnit): SrtSendResult {
        // Same lock discipline as video: snapshot under lock, native call
        // unlocked, stats commit under lock. Never hold stateLock across JNI.
        val generation: Long
        val wasConnected: Boolean
        synchronized(stateLock) {
            generation = sessionGeneration.get()
            wasConnected = connected
        }
        if (!wasConnected) return SrtSendResult(false, generation)
        if (accessUnit.encoderFailure) {
            // A runtime AAC codec failure is terminal for this media session. Do not
            // send the sentinel into MPEG-TS; force the existing reconnect path to
            // rebuild both the transport and encoder resources instead.
            markSendFailure(generation)
            return SrtSendResult(false, generation, recoveryRequired = true)
        }
        val sent = SrtNativeBridge.sendAudio(accessUnit.data, accessUnit.presentationTimeUs, accessUnit.flags)
        if (!sent) {
            sendFailures.incrementAndGet()
            markSendFailure(generation)
        }
        return SrtSendResult(sent, generation, recoveryRequired = !sent)
    }

    fun isCurrentSessionGeneration(generation: Long): Boolean = synchronized(stateLock) {
        sessionGeneration.get() == generation
    }

    fun disconnect() {
        synchronized(stateLock) {
            val generation = sessionGeneration.incrementAndGet()
            connected = false
            // Native generation invalidation and socket teardown are ordered before
            // any stale connect/listen can publish a replacement socket.
            SrtNativeBridge.disconnect(generation)
        }
    }

    private fun connectToResolvedAddress(
        url: String,
        codecMime: String,
        width: Int,
        height: Int,
        fps: Int,
        generation: Long,
    ): Boolean {
        for (candidateUrl in resolvedConnectUrls(url)) {
            if (!isCurrentSessionGeneration(generation)) return false
            if (SrtNativeBridge.connect(candidateUrl, codecMime, width, height, fps, generation)) {
                return true
            }
        }
        return false
    }

    private fun resolvedConnectUrls(url: String): List<String> {
        val uri = runCatching { URI(url) }.getOrNull() ?: return listOf(url)
        val host = uri.host ?: return listOf(url)
        val port = uri.port
        if (port <= 0) return listOf(url)

        val querySuffix = uri.rawQuery?.let { "?$it" }.orEmpty()
        return runCatching {
            InetAddress.getAllByName(host)
                .mapNotNull { address ->
                    address.hostAddress?.let { numericHost ->
                        val authorityHost = if (numericHost.contains(':')) "[$numericHost]" else numericHost
                        "srt://$authorityHost:$port$querySuffix"
                    }
                }
                .distinct()
                .ifEmpty { listOf(url) }
        }.getOrElse { listOf(url) }
    }

    private inline fun establishSession(operationName: String, nativeOperation: (Long) -> Boolean) {
        val generation = synchronized(stateLock) {
            connected = false
            sessionGeneration.incrementAndGet().also { generation ->
                SrtNativeBridge.beginSession(generation)
            }
        }
        val didConnect = nativeOperation(generation)
        val cancelled = synchronized(stateLock) {
            if (generation != sessionGeneration.get()) {
                true
            } else {
                check(didConnect) { "Native SRT bridge failed to $operationName" }
                resetStats()
                connected = true
                false
            }
        }
        if (cancelled) {
            // Cancelled connect/listen must clean up only its own stale native
            // session (generation), never the current generation which may belong
            // to a newer disconnect() that already invalidated this attempt.
            // operationLock is still held, so no replacement connect/listen has
            // published yet, but disconnect(current) would still invalidate the
            // newer Kotlin generation that disconnect() already installed.
            if (didConnect) SrtNativeBridge.disconnect(generation)
            error("SRT $operationName was cancelled")
        }
    }

    private fun resetStats() {
        accessUnitsSent.set(0)
        keyframesSent.set(0)
        bytesSent.set(0)
        sendFailures.set(0)
        lastPresentationTimeUs.set(0)
    }

    private fun markSendFailure(generation: Long) {
        synchronized(stateLock) {
            if (sessionGeneration.get() == generation) {
                connected = false
            }
        }
    }

    companion object {
        private const val BUFFER_FLAG_KEY_FRAME = 1
        private const val BUFFER_FLAG_CODEC_CONFIG = 2
    }
}

private object SrtNativeBridge {
    init {
        System.loadLibrary("openstream_srt")
    }

    external fun beginSession(sessionGeneration: Long)
    external fun connect(
        url: String,
        codecMime: String,
        width: Int,
        height: Int,
        fps: Int,
        sessionGeneration: Long,
    ): Boolean
    external fun listen(
        url: String,
        codecMime: String,
        width: Int,
        height: Int,
        fps: Int,
        sessionGeneration: Long,
    ): Boolean
    external fun sendVideo(data: ByteArray, presentationTimeUs: Long, flags: Int): Boolean
    external fun sendAudio(data: ByteArray, presentationTimeUs: Long, flags: Int): Boolean
    external fun disconnect(sessionGeneration: Long)
}