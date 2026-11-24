package dev.openstream.app.stream

import org.junit.Assert.*
import org.junit.Test

class AdaptiveBitrateControllerTest {
    private fun sample(n: Int, loss: Boolean = false, session: Long = 1) = SrtTelemetry(
        session, n * 100L, if (loss) n * 10L else 0, if (loss) n * 8L else 0,
        20.0, if (loss) 160.0 else 10.0, 0, 0,
    )

    @Test fun congestionReducesAfterTwoSamplesAndRecoversSlowly() {
        val control = AdaptiveBitrateController(4_000_000)
        assertNull(control.update(sample(0, true), 0))
        assertNull(control.update(sample(1, true), 1000))
        assertEquals(3_000_000, control.update(sample(2, true), 2000))
        assertNull(control.update(sample(3, true), 3000))
        assertNull(control.update(sample(4, true), 4000))
        assertEquals(2_250_000, control.update(sample(5, true), 5000))
        // Counter reset establishes a new baseline without interpreting a negative delta.
        assertNull(control.update(sample(6), 6000))
        for (n in 7..13) assertNull(control.update(sample(n), n * 1000L))
        assertEquals(2_437_500, control.update(sample(14), 14000))
    }

    @Test fun floorAndSessionResetAndFastPollingAreSafe() {
        val control = AdaptiveBitrateController(1_000_000, 750_000)
        control.update(sample(0, true), 0)
        assertNull(control.update(sample(1, true), 100))
        assertNull(control.update(sample(1, true), 1000))
        assertEquals(750_000, control.update(sample(2, true), 2000))
        for (n in 3..10) assertNull(control.update(sample(n, true), n * 1000L))
        assertNull(control.update(sample(0, session = 2), 11000))
        assertEquals(750_000, control.currentBitrate)
        assertNull(control.update(sample(1).copy(rttMs = Double.NaN), 12000))
    }

    @Test fun idleTrafficCannotTriggerRecovery() {
        val control = AdaptiveBitrateController(4_000_000)
        control.update(sample(0, true), 0)
        control.update(sample(1, true), 1000)
        control.update(sample(2, true), 2000)
        for (n in 3..20) assertNull(control.update(sample(2, true).copy(sendBufferMs = 10.0), n * 1000L))
        assertEquals(3_000_000, control.currentBitrate)
    }
    @Test fun stalledSenderStillReactsToQueuePressure() {
        val control = AdaptiveBitrateController(4_000_000)
        val blocked = sample(0).copy(queuedBytes = 200_000)
        assertNull(control.update(blocked, 0))
        assertNull(control.update(blocked, 1000))
        assertEquals(3_000_000, control.update(blocked, 2000))
    }

}
