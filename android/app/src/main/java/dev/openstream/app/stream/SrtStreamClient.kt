package dev.openstream.app.stream

import dev.openstream.app.encoder.EncodedAccessUnit

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
    private var connected = false
    var stats = StreamStats()
        private set

    fun connect(url: String, codecMime: String, width: Int, height: Int, fps: Int) {
        require(url.startsWith("srt://")) { "OpenStream V1 expects an SRT URL" }
        check(SrtNativeBridge.connect(url, codecMime, width, height, fps)) { "Native SRT bridge failed to connect" }
        stats = StreamStats()
        connected = true
    }

    fun listen(url: String, codecMime: String, width: Int, height: Int, fps: Int) {
        require(url.startsWith("srt://")) { "OpenStream V2 expects an SRT URL" }
        check(SrtNativeBridge.listen(url, codecMime, width, height, fps)) { "Native SRT bridge failed to listen" }
        stats = StreamStats()
        connected = true
    }

    fun sendVideoAccessUnit(accessUnit: EncodedAccessUnit): Boolean {
        if (!connected) return false
        val sent = SrtNativeBridge.sendVideo(accessUnit.data, accessUnit.presentationTimeUs, accessUnit.flags)
        val isCodecConfig = (accessUnit.flags and BUFFER_FLAG_CODEC_CONFIG) != 0
        stats = if (sent) {
            stats.copy(
                accessUnitsSent = stats.accessUnitsSent + if (isCodecConfig) 0 else 1,
                keyframesSent = stats.keyframesSent + if ((accessUnit.flags and BUFFER_FLAG_KEY_FRAME) != 0) 1 else 0,
                bytesSent = stats.bytesSent + if (isCodecConfig) 0 else accessUnit.data.size,
                lastPresentationTimeUs = maxOf(stats.lastPresentationTimeUs, accessUnit.presentationTimeUs),
            )
        } else {
            stats.copy(sendFailures = stats.sendFailures + 1)
        }
        return sent
    }

    fun sendAudioAccessUnit(accessUnit: EncodedAccessUnit): Boolean {
        if (!connected) return false
        return SrtNativeBridge.sendAudio(accessUnit.data, accessUnit.presentationTimeUs, accessUnit.flags)
    }

    fun disconnect() {
        if (connected) {
            SrtNativeBridge.disconnect()
        }
        connected = false
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

    external fun connect(url: String, codecMime: String, width: Int, height: Int, fps: Int): Boolean
    external fun listen(url: String, codecMime: String, width: Int, height: Int, fps: Int): Boolean
    external fun sendVideo(data: ByteArray, presentationTimeUs: Long, flags: Int): Boolean
    external fun sendAudio(data: ByteArray, presentationTimeUs: Long, flags: Int): Boolean
    external fun disconnect()
}
