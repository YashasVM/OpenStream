package dev.openstream.app.stream

import dev.openstream.app.encoder.CodecPreference

data class StreamConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val keyframeIntervalSeconds: Int,
    val latencyMs: Int,
    val codecPreference: CodecPreference,
) {
    val bitrateMbps: Int
        get() = bitrate / 1_000_000

    companion object {
        val Default1080p30 = StreamConfig(
            width = 1920,
            height = 1080,
            fps = 30,
            bitrate = 12_000_000,
            keyframeIntervalSeconds = 1,
            latencyMs = 120,
            codecPreference = CodecPreference.PreferHevc,
        )

        val Fallback720p30 = StreamConfig(
            width = 1280,
            height = 720,
            fps = 30,
            bitrate = 8_000_000,
            keyframeIntervalSeconds = 1,
            latencyMs = 120,
            codecPreference = CodecPreference.PreferHevc,
        )
    }
}
