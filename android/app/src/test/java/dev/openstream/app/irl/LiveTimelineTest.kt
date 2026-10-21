package dev.openstream.app.irl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTimelineTest {
    private var monotonicUs = 50_000_000_000L
    private var bootAheadUs = 0L
    private val timeline = LiveTimeline { MediaClockSample(monotonicUs, monotonicUs + bootAheadUs) }

    @Test
    fun keyframeStartsTheTimelineAtZero() {
        assertNull(timeline.video(monotonicUs - 40_000, keyframe = false))
        assertNull(timeline.audio(monotonicUs - 30_000))
        assertFalse(timeline.started)
        assertEquals(0L, timeline.video(monotonicUs - 30_000, keyframe = true))
        assertTrue(timeline.started)
        assertEquals(33_333L, timeline.video(monotonicUs + 3_333, keyframe = false))
    }

    @Test
    fun boottimeVideoIsAlignedWithMonotonicAudio() {
        bootAheadUs = 7_200_000_000L // two hours of deep sleep since boot
        assertEquals(0L, timeline.video(monotonicUs + bootAheadUs - 60_000, keyframe = true))
        // Audio captured 40 ms after the keyframe lands 40 ms after it on the wire.
        assertEquals(40_000L, timeline.audio(monotonicUs - 20_000))
        monotonicUs += 1_000_000
        assertEquals(1_000_000L, timeline.video(monotonicUs + bootAheadUs - 60_000, keyframe = false))
        assertEquals(1_040_000L, timeline.audio(monotonicUs - 20_000))
    }

    @Test
    fun monotonicVideoIsNotShiftedWhenClocksDiverge() {
        bootAheadUs = 7_200_000_000L
        assertEquals(0L, timeline.video(monotonicUs - 60_000, keyframe = true))
        assertEquals(40_000L, timeline.audio(monotonicUs - 20_000))
    }

    @Test
    fun audioBeforeTheKeyframeIsDroppedNotClamped() {
        assertEquals(0L, timeline.video(monotonicUs - 10_000, keyframe = true))
        assertNull(timeline.audio(monotonicUs - 30_000))
        assertEquals(1_333L, timeline.audio(monotonicUs - 30_000 + 21_333))
    }

    @Test
    fun outputIsStrictlyIncreasingPerStream() {
        assertEquals(0L, timeline.video(monotonicUs, keyframe = true))
        assertEquals(1L, timeline.video(monotonicUs, keyframe = false))
        assertEquals(2L, timeline.video(monotonicUs - 5_000, keyframe = false))
        assertEquals(10_000L, timeline.audio(monotonicUs + 10_000))
        assertEquals(10_001L, timeline.audio(monotonicUs + 9_000))
    }

    @Test
    fun unknownClockIsTreatedAsCapturedNow() {
        // e.g. an encoder that restarts PTS at zero.
        assertEquals(0L, timeline.video(0, keyframe = true))
        assertEquals(33_333L, timeline.video(33_333, keyframe = false))
        assertEquals(5_000L, timeline.audio(monotonicUs + 5_000))
    }

    @Test
    fun offsetPrefersTheDomainWithTheSmallestNonNegativeLag() {
        val now = MediaClockSample(monotonicUs = 1_000_000_000, boottimeUs = 4_000_000_000)
        assertEquals(0L, LiveTimeline.monotonicOffsetUs(999_950_000, now))
        assertEquals(-3_000_000_000L, LiveTimeline.monotonicOffsetUs(3_999_950_000, now))
        // Clock reads are not atomic: a few ms in the future still counts as this domain.
        assertEquals(-3_000_000_000L, LiveTimeline.monotonicOffsetUs(4_000_005_000, now))
        assertEquals(1_000_000_000L - 123, LiveTimeline.monotonicOffsetUs(123, now))
    }
}
