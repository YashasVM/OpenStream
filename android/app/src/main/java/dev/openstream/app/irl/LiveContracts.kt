package dev.openstream.app.irl

import android.view.Surface
import dev.openstream.app.camera.CameraLens
import dev.openstream.app.encoder.EncodedAccessUnit

/*
 * Shared contracts for IRL Live. Transports, the foreground service and the UI
 * are built independently against these types; change them only deliberately.
 */

data class LiveVideoParams(val width: Int, val height: Int, val fps: Int, val bitrate: Int, val keyframeIntervalSeconds: Int = 2)

data class LiveAudioParams(val sampleRate: Int = 48_000, val channelCount: Int = 2, val bitrate: Int = 128_000)

data class LiveTransportStats(
    val sentBitrate: Long = 0,
    val queuedFrames: Int = 0,
    val droppedVideoFrames: Long = 0,
    val congested: Boolean = false,
)

interface LiveTransportListener {
    /** Publishing accepted by the ingest server. */
    fun onConnected()

    /** Connect or send failed; [failure] decides whether the session retries. */
    fun onFailed(reason: String, failure: LiveFailure)

    /** Transport closed after a local disconnect(). */
    fun onDisconnected()
}

/**
 * Outbound publisher fed with already-encoded media. Implementations must be
 * thread-safe: video and audio arrive on separate encoder threads, and calls
 * after disconnect() are ignored. Codec-config units (BUFFER_FLAG_CODEC_CONFIG)
 * are delivered through sendVideo/sendAudio and must be consumed as config.
 */
interface LiveTransport {
    fun connect(publishUrl: String, video: LiveVideoParams, audio: LiveAudioParams, listener: LiveTransportListener)
    fun sendVideo(accessUnit: EncodedAccessUnit)
    fun sendAudio(accessUnit: EncodedAccessUnit)
    fun stats(): LiveTransportStats
    fun disconnect()
}

sealed class LiveStatus {
    data object Idle : LiveStatus()
    data object Connecting : LiveStatus()
    data class Live(val startedAtElapsedMs: Long) : LiveStatus()
    data class Reconnecting(val retryInSeconds: Long, val reason: String) : LiveStatus()
    data class Error(val message: String) : LiveStatus()
}

data class LiveHud(
    val status: LiveStatus = LiveStatus.Idle,
    val targetBitrate: Int = 0,
    val currentBitrate: Int = 0,
    val sentBitrate: Long = 0,
    val fps: Int = 0,
    val droppedFrames: Long = 0,
    val network: String = "",
    val thermalWarning: Boolean = false,
    val muted: Boolean = false,
    val lens: CameraLens? = null,
)

/** Local-binder API exposed by IrlStreamService to IrlActivity. */
interface IrlServiceApi {
    fun goLive(settings: LiveSettings)
    fun stopLive()
    fun attachPreview(surface: Surface?)
    fun availableLenses(): List<CameraLens>
    fun switchLens(lens: CameraLens)
    fun setMuted(muted: Boolean)
    fun addHudListener(listener: (LiveHud) -> Unit)
    fun removeHudListener(listener: (LiveHud) -> Unit)
    fun currentHud(): LiveHud
}
