package dev.openstream.app.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

enum class CodecPreference {
    PreferHevc,
    ForceAvc,
}

fun CodecPreference.advertisedMimeType(): String {
    return when (this) {
        CodecPreference.PreferHevc -> MediaFormat.MIMETYPE_VIDEO_HEVC
        CodecPreference.ForceAvc -> MediaFormat.MIMETYPE_VIDEO_AVC
    }
}

data class EncodedAccessUnit(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val flags: Int,
)

private data class EncoderSelection(
    val mimeType: String,
    val codecName: String,
)

class MediaCodecVideoEncoder(
    private val preference: CodecPreference,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val keyframeIntervalSeconds: Int,
    private val onEncodedAccessUnit: (EncodedAccessUnit) -> Unit,
) {
    private var selection: EncoderSelection? = null
    private var mimeType = preference.advertisedMimeType()
    private var codec: MediaCodec? = null
    private val thread = HandlerThread("OpenStreamEncoder")
    private lateinit var handler: Handler
    private var surface: Surface? = null

    val codecName: String
        get() = mimeType

    fun inputSurface(): Surface = checkNotNull(surface) { "Encoder input surface is not ready" }

    fun start() {
        // Re-evaluate the codec at every start because bitrate can change per OBS reservation
        // and vendor codec availability may change after a codec failure/restart.
        if (codec != null) {
            stop()
        }
        val resolvedSelection = chooseEncoder(preference, width, height, fps, bitrate)
        selection = resolvedSelection
        mimeType = resolvedSelection.mimeType
        Log.i(
            "OpenStreamEncoder",
            "Using hardware encoder ${resolvedSelection.codecName} for $mimeType " +
                "${width}x${height}@${fps} (${bitrate / 1_000_000} Mbps)",
        )
        val encoder = MediaCodec.createByCodecName(resolvedSelection.codecName)
        codec = encoder
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeIntervalSeconds)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setFloat(MediaFormat.KEY_OPERATING_RATE, fps.toFloat())
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                setInteger(MediaFormat.KEY_LATENCY, 0)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
        }
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = encoder.createInputSurface()
        } catch (error: Throwable) {
            codec = null
            surface = null
            runCatching { encoder.release() }
            throw error
        }

        if (!thread.isAlive) {
            thread.start()
        }
        handler = Handler(thread.looper)
        encoder.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                val buffer: ByteBuffer = codec.getOutputBuffer(index) ?: run {
                    codec.releaseOutputBuffer(index, false)
                    return
                }
                if (info.size <= 0) {
                    codec.releaseOutputBuffer(index, false)
                    return
                }
                val bytes = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                buffer.get(bytes)
                onEncodedAccessUnit(
                    EncodedAccessUnit(
                        data = bytes,
                        presentationTimeUs = info.presentationTimeUs,
                        flags = info.flags,
                    )
                )
                codec.releaseOutputBuffer(index, false)
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e("OpenStreamEncoder", "MediaCodec encoder error", e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                    format.containsKey(MediaFormat.KEY_LATENCY)
                ) {
                    Log.i(
                        "OpenStreamEncoder",
                        "Encoder accepted latency=${format.getInteger(MediaFormat.KEY_LATENCY)} frame(s)",
                    )
                }
                codecConfigFrom(format)?.let { config ->
                    onEncodedAccessUnit(
                        EncodedAccessUnit(
                            data = config,
                            presentationTimeUs = 0,
                            flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG,
                        ),
                    )
                }
            }
        }, handler)
        try {
            encoder.start()
        } catch (error: Throwable) {
            codec = null
            surface = null
            runCatching { encoder.release() }
            throw error
        }
    }

    fun stop() {
        val encoder = codec ?: return
        codec = null
        surface = null
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
    }

    private fun chooseEncoder(
        preference: CodecPreference,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
    ): EncoderSelection {
        val mimeTypes = when (preference) {
            CodecPreference.ForceAvc -> listOf(MediaFormat.MIMETYPE_VIDEO_AVC)
            CodecPreference.PreferHevc -> listOf(
                MediaFormat.MIMETYPE_VIDEO_HEVC,
                MediaFormat.MIMETYPE_VIDEO_AVC,
            )
        }
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        for (mime in mimeTypes) {
            val candidates = infos.mapNotNull { candidate ->
                if (!candidate.isEncoder ||
                    !candidate.isHardwareAccelerated ||
                    candidate.isSoftwareOnly ||
                    candidate.supportedTypes.none { it.equals(mime, true) }
                ) {
                    return@mapNotNull null
                }

                val capabilities = runCatching {
                    candidate.getCapabilitiesForType(mime)
                }.getOrNull() ?: return@mapNotNull null
                if (!capabilities.colorFormats.contains(
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                    )
                ) {
                    return@mapNotNull null
                }
                if (!capabilities.encoderCapabilities.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                    )
                ) {
                    Log.d("OpenStreamEncoder", "Skipping ${candidate.name}: CBR is unsupported")
                    return@mapNotNull null
                }

                val videoCapabilities = capabilities.videoCapabilities
                val supportsTarget = runCatching {
                    videoCapabilities.areSizeAndRateSupported(width, height, fps.toDouble()) &&
                        videoCapabilities.bitrateRange.contains(bitrate)
                }.getOrDefault(false)
                if (!supportsTarget) {
                    Log.d(
                        "OpenStreamEncoder",
                        "Skipping ${candidate.name}: cannot sustain ${width}x${height}@${fps} " +
                            "at ${bitrate / 1_000_000} Mbps",
                    )
                    return@mapNotNull null
                }

                EncoderSelection(mime, candidate.name)
            }

            val selected = candidates.firstOrNull() ?: continue
            if (preference == CodecPreference.PreferHevc &&
                mime == MediaFormat.MIMETYPE_VIDEO_AVC
            ) {
                Log.w(
                    "OpenStreamEncoder",
                    "Hardware HEVC cannot satisfy the requested stream profile; " +
                        "explicitly falling back to hardware AVC",
                )
            }
            return selected
        }

        Log.e(
            "OpenStreamEncoder",
            "No hardware surface encoder can satisfy ${width}x${height}@${fps} " +
                "at ${bitrate / 1_000_000} Mbps",
        )
        throw IllegalStateException(
            "OpenStream needs a hardware AVC/HEVC encoder that supports the selected stream profile",
        )
    }

    private fun codecConfigFrom(format: MediaFormat): ByteArray? {
        val output = ByteArrayOutputStream()
        for (index in 0..2) {
            val key = "csd-$index"
            if (!format.containsKey(key)) continue
            val buffer = format.getByteBuffer(key) ?: continue
            val duplicate = buffer.duplicate()
            duplicate.position(0)
            val bytes = ByteArray(duplicate.remaining())
            duplicate.get(bytes)
            output.write(bytes)
        }
        return output.toByteArray().takeIf { it.isNotEmpty() }
    }
}
