package dev.openstream.app.stream

import dev.openstream.app.encoder.EncodedAccessUnit

class SrtStreamClient {
    private var connected = false

    fun connect(url: String, codecMime: String, width: Int, height: Int, fps: Int) {
        require(url.startsWith("srt://")) { "OpenStream V1 expects an SRT URL" }
        check(SrtNativeBridge.connect(url, codecMime, width, height, fps)) { "Native SRT bridge failed to connect" }
        connected = true
    }

    fun sendVideoAccessUnit(accessUnit: EncodedAccessUnit) {
        if (!connected) return
        SrtNativeBridge.sendVideo(accessUnit.data, accessUnit.presentationTimeUs, accessUnit.flags)
    }

    fun disconnect() {
        if (connected) {
            SrtNativeBridge.disconnect()
        }
        connected = false
    }
}

private object SrtNativeBridge {
    init {
        System.loadLibrary("openstream_srt")
    }

    external fun connect(url: String, codecMime: String, width: Int, height: Int, fps: Int): Boolean
    external fun sendVideo(data: ByteArray, presentationTimeUs: Long, flags: Int): Boolean
    external fun disconnect()
}
