package dev.openstream.app.irl

import dev.openstream.app.encoder.EncodedAccessUnit

/** RTMP(S) publisher backed by RootEncoder. Stub; owned by the RTMP workstream. */
class RtmpLiveTransport : LiveTransport {
    override fun connect(publishUrl: String, video: LiveVideoParams, audio: LiveAudioParams, listener: LiveTransportListener) = TODO()
    override fun sendVideo(accessUnit: EncodedAccessUnit) = TODO()
    override fun sendAudio(accessUnit: EncodedAccessUnit) = TODO()
    override fun stats(): LiveTransportStats = TODO()
    override fun disconnect() = TODO()
}
