package dev.openstream.app.stream

import android.media.MediaFormat
import dev.openstream.app.encoder.CodecPreference

enum class StreamProfile(
    val preferenceValue: String,
    val displayName: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val hevcBitrate: Int,
    val avcBitrate: Int,
) {
    Balanced("balanced", "Balanced — 1080p30", 1920, 1080, 30, 12_000_000, 16_000_000),
    Smooth("smooth", "Smooth — 1080p60", 1920, 1080, 60, 20_000_000, 28_000_000),
    Cool("cool", "Cool — 720p30", 1280, 720, 30, 6_000_000, 8_000_000);

    fun toConfig(codecPreference: CodecPreference = CodecPreference.PreferHevc): StreamConfig = StreamConfig(
        width = width,
        height = height,
        fps = fps,
        hevcBitrate = hevcBitrate,
        avcBitrate = avcBitrate,
        keyframeIntervalSeconds = 1,
        latencyMs = 120,
        codecPreference = codecPreference,
        audioSampleRate = 48_000,
        audioChannelCount = 1,
        audioBitrate = 192_000,
    )

    companion object {
        fun fromPreference(value: String?): StreamProfile = entries.firstOrNull {
            it.preferenceValue == value
        } ?: Balanced
    }
}

data class StreamConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val hevcBitrate: Int,
    val avcBitrate: Int,
    val keyframeIntervalSeconds: Int,
    val latencyMs: Int,
    val codecPreference: CodecPreference,
    val audioSampleRate: Int,
    val audioChannelCount: Int,
    val audioBitrate: Int,
) {
    val bitrate: Int
        get() = hevcBitrate

    val bitrateMbps: Int
        get() = bitrate / 1_000_000

    fun bitrateForMime(mimeType: String): Int = when (mimeType) {
        MediaFormat.MIMETYPE_VIDEO_AVC -> avcBitrate
        else -> hevcBitrate
    }

    companion object {
        val Default = StreamProfile.Balanced.toConfig()
        val Default1080p60 = StreamProfile.Smooth.toConfig()
        val Fallback720p60 = StreamProfile.Cool.toConfig()
    }
}
