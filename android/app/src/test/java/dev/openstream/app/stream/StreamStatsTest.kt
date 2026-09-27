package dev.openstream.app.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamStatsTest {
    @Test
    fun durationUsesElapsedMediaTimeRatherThanDeviceUptime() {
        val stats = StreamStats(
            firstPresentationTimeUs = 173_500_000_000,
            lastPresentationTimeUs = 173_502_500_000,
        )

        assertEquals(2.5, stats.secondsSent, 0.001)
        assertEquals(0.0, StreamStats().secondsSent, 0.001)
    }
}
