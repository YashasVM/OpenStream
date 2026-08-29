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
    val encoderFailure: Boolean = false,
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
    @Volatile private var streamGeneration = 0L
    private val lifecycleLock = Any()
    private val deliveryLock = Any()
    private val callbackLock = Any()

    val codecName: String
        get() = mimeType

    fun inputSurface(): Surface = checkNotNull(surface) { "Encoder input surface is not ready" }

    fun start() = synchronized(lifecycleLock) {
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
        val generation = synchronized(deliveryLock) {
            val nextGeneration = streamGeneration + 1
            streamGeneration = nextGeneration
            nextGeneration
        }
        try {
            encoder.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    val accessUnit = synchronized(callbackLock) {
                        if (streamGeneration != generation) {
                            runCatching { codec.releaseOutputBuffer(index, false) }
                            return@synchronized null
                        }
                        val buffer: ByteBuffer = codec.getOutputBuffer(index) ?: run {
                            runCatching { codec.releaseOutputBuffer(index, false) }
                            return@synchronized null
                        }
                        if (info.size <= 0) {
                            runCatching { codec.releaseOutputBuffer(index, false) }
                            return@synchronized null
                        }
                        val bytes = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.get(bytes)
                        runCatching { codec.releaseOutputBuffer(index, false) }
                        EncodedAccessUnit(
                            data = bytes,
                            presentationTimeUs = info.presentationTimeUs,
                            flags = info.flags,
                        )
                    } ?: return
                    deliverIfCurrent(generation, accessUnit)
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    if (streamGeneration != generation) return
                    Log.e("OpenStreamEncoder", "MediaCodec encoder error", e)
                    // Route a fatal asynchronous codec failure through the same generation-bound
                    // delivery path as media. SrtStreamClient recognizes this sentinel and marks
                    // the active session failed without attempting to mux an invalid access unit.
                    deliverIfCurrent(
                        generation,
                        EncodedAccessUnit(
                            data = ByteArray(0),
                            presentationTimeUs = 0,
                            flags = 0,
                            encoderFailure = true,
                        ),
                    )
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    if (streamGeneration != generation) return
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                        format.containsKey(MediaFormat.KEY_LATENCY)
                    ) {
                        Log.i(
                            "OpenStreamEncoder",
                            "Encoder accepted latency=${format.getInteger(MediaFormat.KEY_LATENCY)} frame(s)",
                        )
                    }
                    codecConfigFrom(format)?.let { config ->
                        deliverIfCurrent(
                            generation,
                            EncodedAccessUnit(
                                data = config,
                                presentationTimeUs = 0,
                                flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG,
                            ),
                        )
                    }
                }
            }, handler)
            encoder.start()
        } catch (error: Throwable) {
            synchronized(deliveryLock) {
                if (streamGeneration == generation) {
                    streamGeneration += 1
                }
            }
            codec = null
            surface = null
            synchronized(callbackLock) {
                runCatching { encoder.stop() }
                runCatching { encoder.release() }
            }
            throw error
        }
    }

    fun stop() = synchronized(lifecycleLock) {
        synchronized(deliveryLock) {
            streamGeneration += 1
        }
        val encoder = codec ?: return@synchronized
        codec = null
        surface = null
        synchronized(callbackLock) {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
    }

    private fun deliverIfCurrent(generation: Long, accessUnit: EncodedAccessUnit): Boolean {
        synchronized(deliveryLock) {
            if (streamGeneration != generation) return false
            onEncodedAccessUnit(accessUnit)
            return true
        }
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
