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

data class EncodedAccessUnit(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val flags: Int,
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
    private var mimeType = chooseMimeType(preference)
    private var codec: MediaCodec? = null
    private val thread = HandlerThread("OpenStreamEncoder")
    private lateinit var handler: Handler
    private var surface: Surface? = null

    val codecName: String
        get() = mimeType

    fun inputSurface(): Surface = checkNotNull(surface) { "Encoder input surface is not ready" }

    fun start() {
        check(codec == null) { "Encoder is already running" }
        mimeType = chooseMimeType(preference)
        val encoder = MediaCodec.createEncoderByType(mimeType)
        codec = encoder
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeIntervalSeconds)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                setInteger(MediaFormat.KEY_LATENCY, 0)
            }
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = encoder.createInputSurface()

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
        encoder.start()
    }

    fun stop() {
        val encoder = codec ?: return
        codec = null
        surface = null
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
    }

    private fun chooseMimeType(preference: CodecPreference): String {
        if (preference == CodecPreference.ForceAvc) {
            return MediaFormat.MIMETYPE_VIDEO_AVC
        }
        val hasHevc = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) }
        }
        return if (hasHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
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
