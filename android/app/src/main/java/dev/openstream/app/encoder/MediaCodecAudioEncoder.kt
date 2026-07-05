package dev.openstream.app.encoder

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Captures microphone audio via AudioRecord and encodes it to AAC using MediaCodec.
 * Encoded access units are delivered via [onEncodedAccessUnit] for muxing into MPEG-TS.
 */
class MediaCodecAudioEncoder(
    private val sampleRate: Int = 48_000,
    private val channelCount: Int = 1,
    private val bitrate: Int = 192_000,
    private val onEncodedAccessUnit: (EncodedAccessUnit) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var running = false

    fun start() {
        // If already running, stop first to allow clean restart
        if (codec != null) {
            stop()
        }

        val mime = MediaFormat.MIMETYPE_AUDIO_AAC
        val format = MediaFormat.createAudioFormat(mime, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSizeBytes())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
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
        check(minBufferSize > 0) { "AudioRecord does not support $sampleRate Hz / $channelCount ch PCM16" }
        val bufferSize = max(minBufferSize * 4, bytesForDurationMs(250))

        val recorder = createRecorder(channelConfig, bufferSize)
        audioRecord = recorder
        recorder.startRecording()

        running = true
        captureThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val pcmBuffer = ByteArray(bytesForDurationMs(20))
            var capturedSamples = 0L
            val startPresentationTimeUs = System.nanoTime() / 1000
            while (running) {
                val bytesRead = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    recorder.read(pcmBuffer, 0, pcmBuffer.size, AudioRecord.READ_BLOCKING)
                } else {
                    @Suppress("DEPRECATION")
                    recorder.read(pcmBuffer, 0, pcmBuffer.size)
                }
                if (bytesRead > 0) {
                    val samplesRead = bytesRead / bytesPerSampleFrame()
                    val inputIndex = encoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            val presentationTimeUs =
                                startPresentationTimeUs + capturedSamples * 1_000_000L / sampleRate
                            inputBuffer.clear()
                            inputBuffer.put(pcmBuffer, 0, bytesRead)
                            encoder.queueInputBuffer(inputIndex, 0, bytesRead, presentationTimeUs, 0)
                        }
                    }
                    capturedSamples += samplesRead
                    drainEncoder(encoder)
                } else if (bytesRead < 0) {
                    Log.w(TAG, "AudioRecord read failed: $bytesRead")
                } else {
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

    private fun createRecorder(channelConfig: Int, bufferSize: Int): AudioRecord {
        val sources = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                add(MediaRecorder.AudioSource.UNPROCESSED)
            }
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.MIC)
        }
        var lastError: Throwable? = null
        for (source in sources) {
            val recorder = runCatching {
                AudioRecord(
                    source,
                    sampleRate,
                    channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
            }.onFailure { lastError = it }.getOrNull() ?: continue
            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "Using audio source $source at $sampleRate Hz, $channelCount ch, $bitrate bps")
                return recorder
            }
            recorder.release()
        }
        throw IllegalStateException("Could not initialize AudioRecord", lastError)
    }

    private fun bytesPerSampleFrame(): Int = channelCount * BYTES_PER_PCM16_SAMPLE

    private fun bytesForDurationMs(durationMs: Int): Int {
        val bytes = sampleRate * bytesPerSampleFrame() * durationMs / 1_000
        val aligned = bytes - (bytes % bytesPerSampleFrame())
        return max(aligned, bytesPerSampleFrame())
    }

    private fun maxInputSizeBytes(): Int = max(bytesForDurationMs(40), 16_384)

    companion object {
        private const val TAG = "OpenStreamAudioEncoder"
        private const val BYTES_PER_PCM16_SAMPLE = 2
    }
}
