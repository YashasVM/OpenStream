package dev.openstream.irl

import android.content.Context
import android.media.MediaCodec
import dev.openstream.irl.camera.Camera2Controller
import dev.openstream.irl.encoder.CodecPreference
import dev.openstream.irl.encoder.EncodedAccessUnit
import dev.openstream.irl.encoder.MediaCodecAudioEncoder
import dev.openstream.irl.encoder.MediaCodecVideoEncoder
import java.io.ByteArrayOutputStream
import java.util.PriorityQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal class Capture(private val context: Context, private val camera: Camera2Controller,
                       private val queue: DiskQueue, private val status: (String) -> Unit,
                       private val failed: (String) -> Unit) {
    private val lifecycle = Executors.newSingleThreadExecutor()
    private val muxWorker = Executors.newSingleThreadScheduledExecutor()
    private val waitingBytes = AtomicLong()
    @Volatile private var accepting = false
    private var videoPtsOffset: Long? = null
    private var video: MediaCodecVideoEncoder? = null
    private var audio: MediaCodecAudioEncoder? = null
    private var segmenter: Segmenter? = null
    init {
        muxWorker.scheduleWithFixedDelay({
            val current = segmenter
            if (accepting && current != null && System.nanoTime() / 1000 - current.lastVideoWallUs > 6_000_000) {
                fail("Camera stopped producing video. Capture stopped; queue retained.")
            }
        }, 1, 1, TimeUnit.SECONDS)
    }
    fun start(bitrate: Int, ready: () -> Unit) {
        lifecycle.execute {
            try {
                videoPtsOffset = null
                muxWorker.submit { segmenter = Segmenter(queue) }.get()
                accepting = true
                video = MediaCodecVideoEncoder(CodecPreference.ForceAvc, 1280, 720, 30, bitrate, 1) { deliver(it, false) }
                audio = MediaCodecAudioEncoder(context) { deliver(it, true) }
                video!!.start()
                audio!!.start()
                camera.startStreaming(video!!.inputSurface())
                ready()
            } catch (_: Exception) {
                accepting = false
                failed("Could not start camera/microphone encoding. Check permissions and hardware support.")
                stop()
            }
        }
    }
    private fun deliver(unit: EncodedAccessUnit, audio: Boolean) {
        if (!accepting) return
        if (unit.encoderFailure) { fail("Encoder failed. Capture stopped; queued video is retained."); return }
        if (waitingBytes.addAndGet(unit.data.size.toLong()) > 12 * 1024 * 1024) {
            waitingBytes.addAndGet(-unit.data.size.toLong())
            fail("Capture processing queue full. Capture stopped; queued video is retained.")
            return
        }
        // Camera HAL timestamps can use a different clock from AudioRecord's monotonic clock.
        val timedUnit = if (!audio && unit.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
            val offset = videoPtsOffset ?: (System.nanoTime() / 1000 - unit.presentationTimeUs).also { videoPtsOffset = it }
            unit.copy(presentationTimeUs = unit.presentationTimeUs + offset)
        } else unit
        muxWorker.execute {
            try { segmenter?.add(timedUnit, audio) }
            catch (error: Exception) { fail(error.message?.takeIf { it.startsWith("Disk queue full") }
                ?: "Could not save capture. Capture stopped; queued video is retained.") }
            finally { waitingBytes.addAndGet(-unit.data.size.toLong()) }
        }
    }
    private fun fail(message: String) {
        if (!accepting) return
        accepting = false
        failed(message)
        stop()
    }
    fun stop(done: () -> Unit = {}) {
        accepting = false
        lifecycle.execute {
            camera.stopStreaming()
            audio?.stop(); audio = null
            video?.stop(); video = null
            try {
                muxWorker.submit {
                    try { segmenter?.finish() }
                    finally { segmenter?.close(); segmenter = null; queue.finishCapture() }
                }.get()
            } catch (_: Exception) { failed("Final segment could not be saved. Earlier queued video is retained.") }
            done()
        }
    }
    fun close() { stop { lifecycle.shutdown(); muxWorker.shutdown() } }

    private class Segmenter(private val queue: DiskQueue) : AutoCloseable {
        private data class Frame(val unit: EncodedAccessUnit, val audio: Boolean)
        private val pending = PriorityQueue<Frame>(compareBy<Frame> { it.unit.presentationTimeUs }.thenBy { it.audio })
        private val mux = NativeMux()
        private var pendingBytes = 0
        private var videoConfig = false
        private var audioConfig = false
        private var latestVideo = Long.MIN_VALUE
        private var latestAudio = Long.MIN_VALUE
        private var basePts = -1L
        private var segmentPts = -1L
        private var segmentCaptureTime = 0L
        private var lastPts = 0L
        private var bytes = ByteArrayOutputStream()
        var lastVideoWallUs = System.nanoTime() / 1000
            private set
        fun add(unit: EncodedAccessUnit, audio: Boolean) {
            if (unit.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                mux.write(unit.data, 0, unit.flags, audio)
                if (audio) audioConfig = true else videoConfig = true
                return
            }
            if (audio) latestAudio = unit.presentationTimeUs else {
                latestVideo = unit.presentationTimeUs
                lastVideoWallUs = System.nanoTime() / 1000
            }
            pending.add(Frame(unit, audio))
            pendingBytes += unit.data.size
            check(pendingBytes <= 8 * 1024 * 1024) { "Encoder interleave queue full" }
            val watermark = minOf(latestAudio, latestVideo)
            while (pending.isNotEmpty() && pending.peek()!!.unit.presentationTimeUs <= watermark) process(pending.remove())
            if (latestAudio != Long.MIN_VALUE && latestVideo != Long.MIN_VALUE) {
                check(kotlin.math.abs(latestAudio - latestVideo) < 3_000_000) { "Audio/video clocks diverged" }
            }
        }
        private fun process(frame: Frame) {
            val unit = frame.unit
            pendingBytes -= unit.data.size
            val key = !frame.audio && unit.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            val pts = unit.presentationTimeUs
            if (basePts < 0) {
                if (!key || !videoConfig || !audioConfig) return
                basePts = pts
            }
            if (pts < basePts) return
            if (key && segmentPts >= 0 && pts - segmentPts >= 200_000) finishSegment(pts)
            if (segmentPts < 0) {
                check(key) { "Segment must start with a video keyframe" }
                segmentPts = pts
                segmentCaptureTime = System.currentTimeMillis() - (System.nanoTime() / 1000 - pts) / 1000
            }
            check(pts - segmentPts <= 5_900_000) { "Encoder keyframe interval exceeded relay segment limit" }
            val output = mux.write(unit.data, pts - basePts, unit.flags, frame.audio)
            check(bytes.size() + output.size <= 8 * 1024 * 1024) { "Segment exceeds upload limit" }
            bytes.write(output)
            lastPts = maxOf(lastPts, pts)
        }
        private fun finishSegment(endPts: Long) {
            if (segmentPts < 0 || bytes.size() == 0) return
            val duration = ((endPts - segmentPts) / 1000).toInt().coerceAtLeast(200)
            queue.append(bytes.toByteArray(), duration, segmentCaptureTime)
            bytes = ByteArrayOutputStream()
            segmentPts = -1
        }
        fun finish() {
            while (pending.isNotEmpty()) process(pending.remove())
            finishSegment(lastPts + 33_333)
        }
        override fun close() { mux.close() }
    }
}
