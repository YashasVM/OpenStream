package dev.openstream.app.irl

/** Both Android media clocks read at (nearly) the same instant, in microseconds. */
data class MediaClockSample(val monotonicUs: Long, val boottimeUs: Long)

/**
 * Puts one connection's audio and video on a shared timeline that starts at 0
 * with the first video keyframe.
 *
 * The streams arrive in different clock domains: audio PTS are System.nanoTime
 * (CLOCK_MONOTONIC) plus sample count, while video PTS come from the camera via
 * the encoder surface and may be CLOCK_BOOTTIME (elapsedRealtimeNanos), which
 * runs ahead of MONOTONIC by the total time spent in deep sleep. RootEncoder
 * writes raw PTS/1000 into FLV, so without rebasing the wire carries boot-relative
 * milliseconds and, after deep sleep, audio and video seconds or hours apart.
 *
 * Each stream's domain is detected from its first unit: a unit is always
 * delivered after it was captured, so its PTS lags "now" by a small, non-negative
 * amount only in its own domain. Both streams are mapped to MONOTONIC, then
 * shifted so the first keyframe is 0. Video is gated on that keyframe; audio
 * before it is dropped. Output is strictly increasing per stream and never
 * negative. Thread-safe: video and audio arrive on separate encoder threads.
 */
class LiveTimeline(private val clock: () -> MediaClockSample) {
    private val lock = Any()
    private var videoOffsetUs: Long? = null
    private var audioOffsetUs: Long? = null
    private var baseUs: Long? = null
    private var lastVideoUs = -1L
    private var lastAudioUs = -1L

    /** True once the first keyframe set the timeline origin. */
    val started: Boolean get() = synchronized(lock) { baseUs != null }

    /** Rebased PTS for a video frame, or null when it must be dropped. */
    fun video(ptsUs: Long, keyframe: Boolean): Long? = synchronized(lock) {
        if (baseUs == null && !keyframe) return null
        val offset = videoOffsetUs ?: monotonicOffsetUs(ptsUs, clock()).also { videoOffsetUs = it }
        val mapped = ptsUs + offset
        val base = baseUs ?: mapped.also { baseUs = it }
        nextAfter(mapped - base, lastVideoUs).also { lastVideoUs = it }
    }

    /** Rebased PTS for an audio frame, or null when it precedes the first keyframe. */
    fun audio(ptsUs: Long): Long? = synchronized(lock) {
        val base = baseUs ?: return null
        val offset = audioOffsetUs ?: monotonicOffsetUs(ptsUs, clock()).also { audioOffsetUs = it }
        val rebased = ptsUs + offset - base
        if (rebased < 0) return null
        nextAfter(rebased, lastAudioUs).also { lastAudioUs = it }
    }

    private fun nextAfter(value: Long, last: Long): Long = if (value <= last) last + 1 else value

    companion object {
        /** Clock reads are not atomic; tolerate a lag this far below zero. */
        const val CLOCK_READ_TOLERANCE_US = 10_000L

        /** Capture-to-delivery latency beyond this means the PTS is in neither domain. */
        const val MAX_PIPELINE_LAG_US = 2_000_000L

        /**
         * Offset to add to [ptsUs] to express it in CLOCK_MONOTONIC, picking the
         * domain where the unit's age is smallest and non-negative. A PTS in an
         * unknown clock is treated as captured now.
         */
        fun monotonicOffsetUs(ptsUs: Long, now: MediaClockSample): Long {
            val bootToMonotonic = now.monotonicUs - now.boottimeUs
            val monotonicLag = now.monotonicUs - ptsUs
            val boottimeLag = now.boottimeUs - ptsUs
            return listOf(0L to monotonicLag, bootToMonotonic to boottimeLag)
                .filter { (_, lag) -> lag in -CLOCK_READ_TOLERANCE_US..MAX_PIPELINE_LAG_US }
                .minByOrNull { (_, lag) -> lag }
                ?.first
                ?: monotonicLag
        }
    }
}
