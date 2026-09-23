package dev.openstream.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSessionStateTest {
    @Test
    fun duplicateReserveIsIdempotentAndCannotReplaceAnOwner() {
        val state = PhoneSessionState()
        state.select("source-a")
        assertTrue(state.reserve("source-a"))
        val reserved = state.snapshot

        assertTrue(state.reserve("source-a"))
        assertEquals(reserved, state.snapshot)
        assertFalse(state.reserve("source-b"))
        assertEquals(reserved, state.snapshot)
    }

    @Test
    fun rapidSelectionMakesEarlierCallbackStale() {
        val state = PhoneSessionState()
        state.select("source-a")
        val oldGeneration = state.beginConnection()
        state.select("source-b")

        assertFalse(state.connected(oldGeneration))
        assertEquals(PhoneSessionStatus.Selected, state.snapshot.status)
        assertEquals("source-b", state.snapshot.sourceInstanceId)
    }

    @Test
    fun timeoutOnlyClearsTheGenerationThatScheduledIt() {
        val state = PhoneSessionState()
        state.select("source-a")
        val oldGeneration = state.snapshot.generation
        state.select("source-b")

        assertFalse(state.timeout(oldGeneration))
        assertEquals(PhoneSessionStatus.Selected, state.snapshot.status)
        assertEquals("source-b", state.snapshot.sourceInstanceId)
        assertTrue(state.timeout(state.snapshot.generation))
        assertEquals(PhoneSessionStatus.Available, state.snapshot.status)
        assertEquals(null, state.snapshot.sourceInstanceId)
    }

    @Test
    fun lostConnectionReentersReconnectForTheSameReservation() {
        val state = PhoneSessionState()
        state.select("source-a")
        state.reserve("source-a")
        val generation = state.beginConnection()

        assertTrue(state.connected(generation))
        assertEquals(PhoneSessionStatus.Live, state.snapshot.status)
        assertTrue(state.connectionLost(generation))
        assertEquals(PhoneSessionStatus.Reconnecting, state.snapshot.status)
        assertFalse(state.connected(generation))
        assertEquals("source-a", state.snapshot.sourceInstanceId)
    }

    @Test
    fun reconnectingShowsStopAndStopPreventsAutomaticReconnect() {
        val state = PhoneSessionState()
        state.select("source-a")
        state.reserve("source-a")
        val generation = state.beginConnection()
        assertTrue(state.connected(generation))
        assertTrue(state.connectionLost(generation))

        assertEquals(PhoneSessionAction.Stop, actionForSessionStatus(state.snapshot.status))
        state.stop()
        val stopped = state.snapshot
        assertEquals(PhoneSessionStatus.Stopped, stopped.status)
        assertEquals(PhoneSessionAction.Start, actionForSessionStatus(stopped.status))
        assertFalse(state.connected(generation))
        assertEquals(stopped, state.snapshot)
    }

    @Test
    fun disconnectReleasesReservationButStopRequiresExplicitStart() {
        val state = PhoneSessionState()
        state.select("source-a")
        state.reserve("source-a")
        state.disconnect()

        assertEquals(PhoneSessionStatus.Available, state.snapshot.status)
        assertEquals(null, state.snapshot.sourceInstanceId)

        state.stop()
        val stopped = state.snapshot
        assertEquals(PhoneSessionStatus.Stopped, stopped.status)
        assertFalse(state.connected(stopped.generation))
        assertEquals(stopped, state.snapshot)
        state.start()
        assertEquals(PhoneSessionStatus.Available, state.snapshot.status)
        assertTrue(state.snapshot.generation > stopped.generation)
    }

    @Test
    fun disconnectDoesNotRestartAStoppedSession() {
        val state = PhoneSessionState()
        state.stop()
        val stopped = state.snapshot

        state.disconnect()

        assertEquals(stopped, state.snapshot)
        assertEquals(PhoneSessionStatus.Stopped, state.snapshot.status)
    }
}
