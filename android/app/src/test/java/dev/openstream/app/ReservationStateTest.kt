package dev.openstream.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReservationStateTest {
    private fun selection(sourceInstanceId: String) = ReservationSelection(
        sourceInstanceId = sourceInstanceId,
        slotLabel = "Phone Camera",
        bitrateMbps = 12,
        obsHost = "192.168.1.24",
    )

    @Test
    fun pendingSelectionIsDiscoverableButNotBusyOrConfirmed() {
        val state = ReservationState()

        assertTrue(state.beginSelection(selection("source-a")))
        assertEquals("source-a", state.advertisedSourceInstanceId)
        assertEquals("source-a", state.pendingSourceInstanceId)
        assertTrue(state.hasReservationToDisconnect)
        assertNull(state.confirmedSourceInstanceId)
        assertFalse(state.isBusy(phoneConnected = false))
    }

    @Test
    fun failedReserveRollbackClearsPendingSelection() {
        val state = ReservationState()
        state.beginSelection(selection("source-a"))

        assertTrue(state.rollbackPending("source-a"))
        assertNull(state.advertisedSourceInstanceId)
        assertNull(state.pendingSourceInstanceId)
        assertNull(state.confirmedSourceInstanceId)
        assertFalse(state.hasReservationToDisconnect)
        assertFalse(state.isBusy(phoneConnected = false))
    }

    @Test
    fun onlyMatchingReservePromotesPendingSelection() {
        val state = ReservationState()
        state.beginSelection(selection("source-a"))

        assertFalse(state.confirm("source-b", "Other", 8))
        assertNull(state.confirmedSourceInstanceId)
        assertEquals("source-a", state.pendingSourceInstanceId)

        assertTrue(state.confirm("source-a", "Phone Camera", 12))
        assertEquals("source-a", state.confirmedSourceInstanceId)
        assertNull(state.pendingSourceInstanceId)
        assertTrue(state.hasReservationToDisconnect)
        assertTrue(state.isBusy(phoneConnected = false))
    }

    @Test
    fun releaseClearsConfirmedReservationAndIsIdempotentWhenIdle() {
        val state = ReservationState()
        assertTrue(state.confirm("source-a", "Phone Camera", 12))

        assertFalse(state.release("source-b"))
        assertEquals("source-a", state.confirmedSourceInstanceId)
        assertTrue(state.release("source-a"))
        assertNull(state.confirmedSourceInstanceId)
        assertFalse(state.hasReservationToDisconnect)
        assertTrue(state.release("source-a"))
    }

    @Test
    fun clearReleasesBothPendingAndConfirmedSelections() {
        val state = ReservationState()
        state.beginSelection(selection("source-a"))
        state.clear()
        assertNull(state.advertisedSourceInstanceId)
        assertFalse(state.hasReservationToDisconnect)
        assertFalse(state.isBusy(phoneConnected = false))

        state.confirm("source-b", "Phone Camera", 12)
        state.clear()
        assertNull(state.confirmedSourceInstanceId)
        assertFalse(state.hasReservationToDisconnect)
        assertFalse(state.isBusy(phoneConnected = false))
    }
}
