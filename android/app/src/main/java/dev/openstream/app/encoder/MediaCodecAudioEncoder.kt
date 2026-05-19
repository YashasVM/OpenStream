package dev.openstream.app.encoder

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer

/**
 * Captures microphone audio via AudioRecord and encodes it to AAC using MediaCodec.
 * Encoded access units are delivered via [onEncodedAccessUnit] for muxing into MPEG-TS.
 */
class MediaCodecAudioEncoder(
    private val sampleRate: Int = 44100,
    private val channelCount: Int = 1,
    private val bitrate: Int = 128_000,
    private val onEncodedAccessUnit: (EncodedAccessUnit) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var running = false

    fun start() {
        check(codec == null) { "Audio encoder is already running" }

        val mime = MediaFormat.MIMETYPE_AUDIO_AAC
        val format = MediaFormat.createAudioFormat(mime, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        val encoder = MediaCodec.createEncoderByType(mime)
        codec = encoder
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val channelConfig = if (channelCount == 2) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufferSize * 2, 8192)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        audioRecord = recorder
        recorder.startRecording()

        running = true
        captureThread = Thread({
            val pcmBuffer = ByteArray(4096)
            while (running) {
                val bytesRead = recorder.read(pcmBuffer, 0, pcmBuffer.size)
                if (bytesRead > 0) {
                    // Feed PCM to encoder input
                    val inputIndex = encoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            inputBuffer.clear()
                            inputBuffer.put(pcmBuffer, 0, bytesRead)
                            encoder.queueInputBuffer(inputIndex, 0, bytesRead, System.nanoTime() / 1000, 0)
                        }
                    }
                    // Drain encoded output
                    drainEncoder(encoder)
                }
            }
        }, "OpenStreamAudioCapture").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        captureThread?.join(500)
        captureThread = null
        val recorder = audioRecord
        audioRecord = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }

        val encoder = codec
        codec = null
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
    }

    private fun drainEncoder(encoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(info, 0)
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Deliver codec-specific data (AudioSpecificConfig)
                val newFormat = encoder.outputFormat
                codecConfigFrom(newFormat)?.let { config ->
                    onEncodedAccessUnit(
                        EncodedAccessUnit(
                            data = config,
                            presentationTimeUs = 0,
                            flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG,
                        )
                    )
                }
            } else if (outputIndex >= 0) {
                val buffer: ByteBuffer? = encoder.getOutputBuffer(outputIndex)
                if (buffer != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    buffer.get(bytes)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        onEncodedAccessUnit(
                            EncodedAccessUnit(
                                data = bytes,
                                presentationTimeUs = 0,
                                flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG,
                            )
                        )
                    } else {
                        onEncodedAccessUnit(
                            EncodedAccessUnit(
                                data = bytes,
                                presentationTimeUs = info.presentationTimeUs,
                                flags = info.flags,
                            )
                        )
                    }
                }
                encoder.releaseOutputBuffer(outputIndex, false)
            } else {
                break
            }
        }
    }

    private fun codecConfigFrom(format: MediaFormat): ByteArray? {
        val csd0 = format.getByteBuffer("csd-0") ?: return null
        val dup = csd0.duplicate()
        dup.position(0)
        val bytes = ByteArray(dup.remaining())
        dup.get(bytes)
        return bytes.takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val TAG = "OpenStreamAudioEncoder"
    }
}
