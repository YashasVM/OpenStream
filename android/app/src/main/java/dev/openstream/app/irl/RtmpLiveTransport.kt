package dev.openstream.app.irl

import android.media.MediaCodec
import com.pedro.common.ConnectChecker
import com.pedro.rtmp.rtmp.RtmpClient
import dev.openstream.app.encoder.EncodedAccessUnit
import java.nio.ByteBuffer

/** One publishing attempt. The service creates a new transport when retrying. */
class RtmpLiveTransport : LiveTransport {
    private val lock = Any()
    private var session: Session? = null
    private var disconnected = false

    override fun connect(publishUrl: String, video: LiveVideoParams, audio: LiveAudioParams, listener: LiveTransportListener) {
        synchronized(lock) {
            if (disconnected || session != null) return
            val current = Session(publishUrl, listener)
            session = current
            current.client = RtmpClient(object : ConnectChecker {
                override fun onConnectionStarted(url: String) = Unit
                override fun onAuthSuccess() = Unit

                override fun onConnectionSuccess() {
                    val connectedListener = synchronized(lock) {
                        current.listener.takeIf { current.gate.onConnected() }
                    }
                    connectedListener?.onConnected()
                }

                override fun onConnectionFailed(reason: String) = fail(current, FailureClassifier.classify(reason))

                override fun onAuthError() = fail(current, LiveFailure.AUTH)

                override fun onNewBitrate(bitrate: Long) {
                    synchronized(lock) { current.gate.onBitrate(bitrate) }
                }

                override fun onDisconnect() {
                    val disconnectedListener = synchronized(lock) {
                        if (!current.gate.onDisconnected()) return
                        session = null
                        current.listener
                    }
                    disconnectedListener.onDisconnected()
                }
            }).apply {
                tlsHostVerification = true
                setReTries(0)
                setLogs(false)
                setVideoResolution(video.width, video.height)
                setFps(video.fps)
                setAudioInfo(audio.sampleRate, audio.channelCount > 1)
                // Keeps AAC out of onMetaData so ingests do not wait for audio that never comes.
                setOnlyVideo(!audio.enabled)
                // RootEncoder otherwise ignores read errors on an established connection.
                shouldFailOnRead = true
            }
        }
    }

    override fun sendVideo(accessUnit: EncodedAccessUnit) = send(accessUnit, video = true)

    override fun sendAudio(accessUnit: EncodedAccessUnit) = send(accessUnit, video = false)

    private fun send(accessUnit: EncodedAccessUnit, video: Boolean) {
        var failedSession: Session? = null
        var failure = LiveFailure.TRANSIENT
        synchronized(lock) {
            val current = session ?: return
            try {
                val config = if (video) current.gate.videoConfig(accessUnit) else null
                if (config != null) {
                    // 2.8.1 publishes metadata without waiting for AVC info. Connect only after
                    // config; H264Packet.sendVideoInfo removes these Annex-B start codes.
                    current.client.setVideoInfo(ByteBuffer.wrap(config.sps), ByteBuffer.wrap(config.pps), null)
                    if (current.gate.startConnection()) {
                        val url = current.publishUrl
                        current.publishUrl = null
                        current.client.connect(url)
                    }
                } else if (current.gate.canSend(accessUnit)) {
                    // AacPacket derives AudioSpecificConfig from setAudioInfo, so AAC config
                    // units are consumed by the gate rather than sent as audio media.
                    val buffer = ByteBuffer.wrap(accessUnit.data)
                    val info = MediaCodec.BufferInfo().apply {
                        set(0, accessUnit.data.size, accessUnit.presentationTimeUs, accessUnit.flags)
                    }
                    if (video) current.client.sendVideo(buffer, info) else current.client.sendAudio(buffer, info)
                }
            } catch (error: Exception) {
                failedSession = current
                failure = FailureClassifier.classify(error.message.orEmpty())
            }
        }
        failedSession?.let { fail(it, failure) }
    }

    override fun stats(): LiveTransportStats = synchronized(lock) {
        val current = session ?: return LiveTransportStats()
        if (!current.gate.isConnected) return LiveTransportStats()
        LiveTransportStats(
            sentBitrate = current.gate.sentBitrate,
            // cacheSize is the capacity; getItemsInCache is the current queue depth.
            queuedFrames = current.client.getItemsInCache(),
            droppedVideoFrames = current.client.droppedVideoFrames,
            congested = current.client.hasCongestion(),
        )
    }

    override fun disconnect() {
        val client = synchronized(lock) {
            if (disconnected) return
            disconnected = true
            val current = session ?: return
            current.gate.disconnect()
            current.publishUrl = null
            current.client
        }
        // RootEncoder closes asynchronously and calls onDisconnect when local teardown finishes.
        client.disconnect()
    }

    private fun fail(current: Session, failure: LiveFailure) {
        val listener = synchronized(lock) {
            if (!current.gate.onFailed()) return
            current.publishUrl = null
            current.listener
        }
        // Server descriptions and exception messages can echo the publish URL or stream key.
        listener.onFailed(rtmpFailureMessage(failure), failure)
    }

    private class Session(var publishUrl: String?, val listener: LiveTransportListener) {
        val gate = RtmpPublishGate()
        lateinit var client: RtmpClient
    }
}

/** Android-free decisions; the transport holds its lock for every access to this state. */
internal class RtmpPublishGate {
    private enum class Phase { WAITING_FOR_CONFIG, CONNECTING, CONNECTED, FAILED, DISCONNECTING, CLOSED }

    private var phase = Phase.WAITING_FOR_CONFIG
    private var hasVideoConfig = false
    val isConnected: Boolean get() = phase == Phase.CONNECTED
    var sentBitrate = 0L
        private set

    fun videoConfig(unit: EncodedAccessUnit): AvcConfig? {
        if (!acceptsMedia() || unit.encoderFailure || unit.flags and CODEC_CONFIG == 0) return null
        val config = AvcConfig.split(unit.data) ?: return null
        hasVideoConfig = true
        return config
    }

    fun startConnection(): Boolean {
        if (phase != Phase.WAITING_FOR_CONFIG || !hasVideoConfig) return false
        phase = Phase.CONNECTING
        return true
    }

    fun onConnected(): Boolean {
        if (phase != Phase.CONNECTING) return false
        phase = Phase.CONNECTED
        return true
    }

    fun canSend(unit: EncodedAccessUnit): Boolean = isConnected && hasVideoConfig &&
        !unit.encoderFailure && unit.flags and CODEC_CONFIG == 0 && unit.data.isNotEmpty()

    fun onBitrate(bitrate: Long) {
        if (isConnected) sentBitrate = bitrate.coerceAtLeast(0)
    }

    fun onFailed(): Boolean {
        if (!acceptsMedia()) return false
        phase = Phase.FAILED
        sentBitrate = 0
        return true
    }

    fun disconnect() {
        if (phase == Phase.CLOSED || phase == Phase.DISCONNECTING) return
        phase = Phase.DISCONNECTING
        sentBitrate = 0
    }

    fun onDisconnected(): Boolean {
        if (phase != Phase.DISCONNECTING) return false
        phase = Phase.CLOSED
        return true
    }

    private fun acceptsMedia(): Boolean = phase == Phase.WAITING_FOR_CONFIG ||
        phase == Phase.CONNECTING || phase == Phase.CONNECTED

    private companion object {
        // MediaCodec.BUFFER_FLAG_CODEC_CONFIG; keep Android classes out of the decision helper.
        const val CODEC_CONFIG = 2
    }
}

internal fun rtmpFailureMessage(failure: LiveFailure): String = when (failure) {
    LiveFailure.AUTH -> "RTMP authentication failed. Check the stream key and publishing permissions."
    LiveFailure.CONFIG -> "RTMP server or TLS configuration failed."
    LiveFailure.TRANSIENT -> "RTMP connection or send failed."
}
