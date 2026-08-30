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
        const val MIN_BITRATE_MBPS = 8
        const val MAX_BITRATE_MBPS = 50

        // The sustainable default is 1080p30 AVC. 60 fps and HEVC remain
        // useful device-specific choices, but they should not be forced on a
        // phone before its thermal and hardware limits are known.
        val Default1080p30 = StreamConfig(
            width = 1920,
            height = 1080,
            fps = 30,
            bitrate = 12_000_000,
            keyframeIntervalSeconds = 1,
            latencyMs = 120,
            codecPreference = CodecPreference.ForceAvc,
            audioSampleRate = 48_000,
            audioChannelCount = 1,
            audioBitrate = 128_000,
        )

        val Fallback720p30 = StreamConfig(
            width = 1280,
            height = 720,
            fps = 30,
            bitrate = 8_000_000,
            keyframeIntervalSeconds = 1,
            latencyMs = 120,
            codecPreference = CodecPreference.ForceAvc,
            audioSampleRate = 48_000,
            audioChannelCount = 1,
            audioBitrate = 128_000,
        )
    }
}
