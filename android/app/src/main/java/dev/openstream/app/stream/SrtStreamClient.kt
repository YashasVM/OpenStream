package dev.openstream.app.stream

import dev.openstream.app.encoder.EncodedAccessUnit
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

    fun connect(
        url: String,
        codecMime: String,
        width: Int,
        height: Int,
        fps: Int,
        passphrase: String? = null,
    ) {
        require(url.startsWith("srt://")) { "OpenStream V1 expects an SRT URL" }
        synchronized(operationLock) {
            establishSession("connection") {
                SrtNativeBridge.connect(url, codecMime, width, height, fps, passphrase)
            }
        }
    }

    fun listen(
        url: String,
        codecMime: String,
        width: Int,
        height: Int,
        fps: Int,
        passphrase: String? = null,
    ) {
        require(url.startsWith("srt://")) { "OpenStream V2 expects an SRT URL" }
        synchronized(operationLock) {
            establishSession("listener") {
                SrtNativeBridge.listen(url, codecMime, width, height, fps, passphrase)
            }
        }
    }

    fun sendVideoAccessUnit(accessUnit: EncodedAccessUnit): Boolean {
        if (!connected) return false
        val sent = SrtNativeBridge.sendVideo(accessUnit.data, accessUnit.presentationTimeUs, accessUnit.flags)
        val isCodecConfig = (accessUnit.flags and BUFFER_FLAG_CODEC_CONFIG) != 0
        if (sent) {
            if (!isCodecConfig) {
                accessUnitsSent.incrementAndGet()
                if ((accessUnit.flags and BUFFER_FLAG_KEY_FRAME) != 0) {
                    keyframesSent.incrementAndGet()
                }
                bytesSent.addAndGet(accessUnit.data.size.toLong())
                lastPresentationTimeUs.updateAndGet { current ->
                    maxOf(current, accessUnit.presentationTimeUs)
                }
            }
        } else {
            sendFailures.incrementAndGet()
        }
        return sent
    }

    fun sendAudioAccessUnit(accessUnit: EncodedAccessUnit): Boolean {
        if (!connected) return false
        return SrtNativeBridge.sendAudio(accessUnit.data, accessUnit.presentationTimeUs, accessUnit.flags)
    }

    fun disconnect() {
        synchronized(stateLock) {
            sessionGeneration.incrementAndGet()
            connected = false
            // listen() blocks in native accept before connected becomes true. This
            // must not take operationLock so lifecycle stop can cancel that accept.
            SrtNativeBridge.disconnect()
        }
    }

    private inline fun establishSession(operationName: String, nativeOperation: () -> Boolean) {
        val generation = synchronized(stateLock) {
            sessionGeneration.incrementAndGet()
        }
        val didConnect = nativeOperation()
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
            // operationLock is still held, so this cleanup cannot tear down a
            // subsequently started connect/listen operation.
            if (didConnect) SrtNativeBridge.disconnect()
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

    companion object {
        private const val BUFFER_FLAG_KEY_FRAME = 1
        private const val BUFFER_FLAG_CODEC_CONFIG = 2
    }
}

private object SrtNativeBridge {
    init {
        System.loadLibrary("openstream_srt")
    }

    external fun connect(url: String, codecMime: String, width: Int, height: Int, fps: Int, passphrase: String?): Boolean
    external fun listen(url: String, codecMime: String, width: Int, height: Int, fps: Int, passphrase: String?): Boolean
    external fun sendVideo(data: ByteArray, presentationTimeUs: Long, flags: Int): Boolean
    external fun sendAudio(data: ByteArray, presentationTimeUs: Long, flags: Int): Boolean
    external fun disconnect()
}
