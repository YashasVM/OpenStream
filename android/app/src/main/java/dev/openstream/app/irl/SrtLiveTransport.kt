package dev.openstream.app.irl

import dev.openstream.app.encoder.EncodedAccessUnit

/** SRT caller publisher over the native libsrt/MPEG-TS path. Stub; owned by the SRT workstream. */
class SrtLiveTransport : LiveTransport {
    override fun connect(publishUrl: String, video: LiveVideoParams, audio: LiveAudioParams, listener: LiveTransportListener) = TODO()
    override fun sendVideo(accessUnit: EncodedAccessUnit) = TODO()
    override fun sendAudio(accessUnit: EncodedAccessUnit) = TODO()
    override fun stats(): LiveTransportStats = TODO()
    override fun disconnect() = TODO()
}
