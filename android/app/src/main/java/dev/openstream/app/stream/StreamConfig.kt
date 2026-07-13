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
    val audioSampleRate: Int,
    val audioChannelCount: Int,
    val audioBitrate: Int,
) {
    val bitrateMbps: Int
        get() = bitrate / 1_000_000

    companion object {
        val Default1080p60 = StreamConfig(
            width = 1920,
            height = 1080,
            fps = 60,
            // HEVC at 1080p60 remains visually strong at this rate while leaving
            // enough headroom for retransmits on typical Wi-Fi links.
            bitrate = 16_000_000,
            keyframeIntervalSeconds = 1,
            latencyMs = 120,
            codecPreference = CodecPreference.PreferHevc,
            audioSampleRate = 48_000,
            audioChannelCount = 1,
            audioBitrate = 128_000,
        )

        val Fallback720p60 = StreamConfig(
            width = 1280,
            height = 720,
            fps = 60,
            bitrate = 8_000_000,
            keyframeIntervalSeconds = 1,
            latencyMs = 120,
            codecPreference = CodecPreference.PreferHevc,
            audioSampleRate = 48_000,
            audioChannelCount = 1,
            audioBitrate = 128_000,
        )
    }
}
