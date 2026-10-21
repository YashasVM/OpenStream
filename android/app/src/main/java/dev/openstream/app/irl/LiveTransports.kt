package dev.openstream.app.irl

/** Picks the publisher implementation for a destination. */
object LiveTransports {
    fun forSettings(settings: LiveSettings): LiveTransport =
        if (settings.platform == LivePlatform.CUSTOM_SRT) SrtLiveTransport() else RtmpLiveTransport()
}
