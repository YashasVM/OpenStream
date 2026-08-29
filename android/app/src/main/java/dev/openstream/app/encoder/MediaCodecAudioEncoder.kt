package dev.openstream.app.encoder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
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
    context: Context,
    private val sampleRate: Int = 48_000,
    private val channelCount: Int = 1,
    private val bitrate: Int = 128_000,
    private val onEncodedAccessUnit: (EncodedAccessUnit) -> Unit,
) {
    private val context = context.applicationContext
    private var codec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var captureGeneration = 0L
    private val lifecycleLock = Any()
    private val deliveryLock = Any()

    fun start() = synchronized(lifecycleLock) {
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission is not granted"
        }
        // If already running, stop first to allow clean restart
        if (codec != null) {
            stop()
        }

        try {
            val mime = MediaFormat.MIMETYPE_AUDIO_AAC
            val format = MediaFormat.createAudioFormat(mime, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSizeBytes())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
            }

            val encoder = createAudioCodec(mime, format)
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
            // Target at most 80 ms of capture buffering where the device minimum allows it.
            // READ_BLOCKING applies backpressure once this capacity is reached instead of allowing audio to trail video.
            val targetBufferSize = bytesForDurationMs(MAX_CAPTURE_BUFFER_MS)
            if (minBufferSize > targetBufferSize) {
                Log.w(TAG, "AudioRecord minimum buffer exceeds the ${MAX_CAPTURE_BUFFER_MS} ms latency target: $minBufferSize bytes")
            }
            val bufferSize = max(minBufferSize, targetBufferSize)

            val recorder = createRecorder(channelConfig, bufferSize)
            audioRecord = recorder
            recorder.startRecording()

            val generation = synchronized(deliveryLock) {
                val nextGeneration = captureGeneration + 1
                captureGeneration = nextGeneration
                nextGeneration
            }
            captureThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                val pcmBuffer = ByteArray(bytesForDurationMs(20))
                var capturedSamples = 0L
                val startPresentationTimeUs = System.nanoTime() / 1000
                while (captureGeneration == generation) {
                    val bytesRead = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        recorder.read(pcmBuffer, 0, pcmBuffer.size, AudioRecord.READ_BLOCKING)
                    } else {
                        @Suppress("DEPRECATION")
                        recorder.read(pcmBuffer, 0, pcmBuffer.size)
                    }
                    if (captureGeneration != generation) break
                    if (bytesRead > 0) {
                        val samplesRead = bytesRead / bytesPerSampleFrame()
                        val inputIndex = encoder.dequeueInputBuffer(10_000)
                        if (captureGeneration != generation) break
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
                        drainEncoder(encoder, generation)
                    } else if (bytesRead < 0) {
                        if (captureGeneration == generation) {
                            Log.w(TAG, "AudioRecord read failed: $bytesRead")
                        }
                    } else {
                        drainEncoder(encoder, generation)
                    }
                }
            }, "OpenStreamAudioCapture").apply {
                isDaemon = true
                start()
            }
        } catch (error: Throwable) {
            // start() is transactional: a recorder/codec/thread failure must not leave
            // partially started audio resources behind for the next reconnect.
            stop()
            throw error
        }
    }

    fun stop() = synchronized(lifecycleLock) {
        synchronized(deliveryLock) {
            captureGeneration += 1
        }
        val recorder = audioRecord
        audioRecord = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        captureThread?.join(500)
        captureThread = null

        val encoder = codec
        codec = null
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
    }

    private fun drainEncoder(encoder: MediaCodec, generation: Long) {
        val info = MediaCodec.BufferInfo()
        while (captureGeneration == generation) {
            val outputIndex = encoder.dequeueOutputBuffer(info, 0)
            if (captureGeneration != generation) break
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = encoder.outputFormat
                codecConfigFrom(newFormat)?.let { config ->
                    val accessUnit = EncodedAccessUnit(
                        data = config,
                        presentationTimeUs = 0,
                        flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG,
                    )
                    if (!deliverIfCurrent(generation, accessUnit)) return
                }
            } else if (outputIndex >= 0) {
                val buffer: ByteBuffer? = encoder.getOutputBuffer(outputIndex)
                if (buffer != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    buffer.get(bytes)
                    if (captureGeneration != generation) {
                        runCatching { encoder.releaseOutputBuffer(outputIndex, false) }
                        return
                    }
                    val accessUnit = if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        EncodedAccessUnit(
                            data = bytes,
                            presentationTimeUs = 0,
                            flags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG,
                        )
                    } else {
                        EncodedAccessUnit(
                            data = bytes,
                            presentationTimeUs = info.presentationTimeUs,
                            flags = info.flags,
                        )
                    }
                    if (!deliverIfCurrent(generation, accessUnit)) {
                        runCatching { encoder.releaseOutputBuffer(outputIndex, false) }
                        return
                    }
                }
                encoder.releaseOutputBuffer(outputIndex, false)
            } else {
                break
            }
        }
    }

    private fun deliverIfCurrent(generation: Long, accessUnit: EncodedAccessUnit): Boolean {
        synchronized(deliveryLock) {
            if (captureGeneration != generation) return false
            onEncodedAccessUnit(accessUnit)
            return true
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

    private fun createAudioCodec(mime: String, format: MediaFormat): MediaCodec {
        val candidates = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .asSequence()
            .filter { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
            .mapNotNull { info ->
                val capabilities = runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
                    ?: return@mapNotNull null
                if (!runCatching { capabilities.isFormatSupported(format) }.getOrDefault(false)) {
                    Log.i(TAG, "Skipping incompatible AAC encoder ${info.name}")
                    return@mapNotNull null
                }
                info
            }
            .sortedWith(
                compareByDescending<MediaCodecInfo> { it.isHardwareAccelerated && !it.isSoftwareOnly }
                    .thenBy { it.name }
            )
            .toList()

        val selected = candidates.firstOrNull()
            ?: throw IllegalStateException(
                "No AAC encoder supports $sampleRate Hz / $channelCount ch / $bitrate bps"
            )
        if (selected.isHardwareAccelerated && !selected.isSoftwareOnly) {
            Log.i(TAG, "Using hardware audio encoder ${selected.name} for $mime")
        } else {
            Log.w(TAG, "Using compatible software audio encoder ${selected.name} for $mime")
        }
        return MediaCodec.createByCodecName(selected.name)
    }

    @SuppressLint("MissingPermission")
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
        private const val MAX_CAPTURE_BUFFER_MS = 80
    }
}
