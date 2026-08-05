package dev.openstream.app.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
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

fun CodecPreference.advertisedMimeType(): String = when (this) {
    CodecPreference.PreferHevc -> MediaFormat.MIMETYPE_VIDEO_HEVC
    CodecPreference.ForceAvc -> MediaFormat.MIMETYPE_VIDEO_AVC
}

data class EncodedAccessUnit(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val flags: Int,
)

class SoftwareEncoderOnlyException(message: String) : IllegalStateException(message)

class MediaCodecVideoEncoder(
    private val preference: CodecPreference,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val hevcBitrate: Int,
    private val avcBitrate: Int,
    private val keyframeIntervalSeconds: Int,
    private val allowSoftwareEncoder: Boolean,
    private val onEncodedAccessUnit: (EncodedAccessUnit) -> Unit,
) {
    private data class Selection(
        val codecInfo: MediaCodecInfo,
        val mimeType: String,
        val softwareOnly: Boolean,
    )

    private var selection = selectEncoder(preference, allowSoftwareEncoder)
    private var codec: MediaCodec? = null
    private val thread = HandlerThread("OpenStreamEncoder")
    private lateinit var handler: Handler
    private var surface: Surface? = null

    val codecName: String
        get() = selection.codecInfo.name

    val mimeType: String
        get() = selection.mimeType

    val configuredBitrate: Int
        get() = if (mimeType == MediaFormat.MIMETYPE_VIDEO_AVC) avcBitrate else hevcBitrate

    val isSoftwareEncoder: Boolean
        get() = selection.softwareOnly

    fun inputSurface(): Surface = checkNotNull(surface) { "Encoder input surface is not ready" }

    fun start() {
        if (codec != null) stop()
        selection = selectEncoder(preference, allowSoftwareEncoder)
        val encoder = MediaCodec.createByCodecName(selection.codecInfo.name)
        codec = encoder
        val format = MediaFormat.createVideoFormat(selection.mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, configuredBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeIntervalSeconds)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setInteger(MediaFormat.KEY_LATENCY, 0)
            }
        }
        Log.i(
            TAG,
            "Selected codec=${selection.codecInfo.name} mime=${selection.mimeType} " +
                "hardware=${!selection.softwareOnly} bitrate=$configuredBitrate",
        )
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = encoder.createInputSurface()

        if (!thread.isAlive) thread.start()
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
                    EncodedAccessUnit(bytes, info.presentationTimeUs, info.flags),
                )
                codec.releaseOutputBuffer(index, false)
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "MediaCodec encoder error", e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                codecConfigFrom(format)?.let { config ->
                    onEncodedAccessUnit(
                        EncodedAccessUnit(config, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG),
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

    private fun selectEncoder(preference: CodecPreference, allowSoftware: Boolean): Selection {
        val mimeOrder = when (preference) {
            CodecPreference.PreferHevc -> listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
            CodecPreference.ForceAvc -> listOf(MediaFormat.MIMETYPE_VIDEO_AVC)
        }
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }
        for (mime in mimeOrder) {
            val compatible = infos.filter { info ->
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                    runCatching {
                        info.getCapabilitiesForType(mime).colorFormats.contains(
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                        )
                    }.getOrDefault(false)
            }
            compatible.firstOrNull { !isSoftwareOnly(it) }?.let {
                return Selection(it, mime, softwareOnly = false)
            }
        }
        if (allowSoftware) {
            for (mime in mimeOrder) {
                infos.firstOrNull { info ->
                    info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
                }?.let { return Selection(it, mime, softwareOnly = true) }
            }
        }
        throw SoftwareEncoderOnlyException(
            "No hardware HEVC/AVC encoder is available. Enable software fallback explicitly to continue.",
        )
    }

    private fun isSoftwareOnly(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isSoftwareOnly
        val name = info.name.lowercase()
        return name.startsWith("omx.google.") || name.startsWith("c2.android.") ||
            name.contains("software") || name.contains("sw.")
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

    companion object {
        private const val TAG = "OpenStreamEncoder"
    }
}
