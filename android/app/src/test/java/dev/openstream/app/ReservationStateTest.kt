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
        reservationToken = "token-$sourceInstanceId",
    )

    @Test
    fun pendingSelectionIsDiscoverableButNotBusyOrConfirmed() {
        val state = ReservationState()

        assertTrue(state.beginSelection(selection("source-a")))
        assertEquals("source-a", state.advertisedSourceInstanceId)
        assertEquals("source-a", state.pendingSourceInstanceId)
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
        assertTrue(state.release("source-a"))
    }

    @Test
    fun disconnectFromAnotherSourceCannotClearTheConnectedAssignment() {
        val state = ReservationState()
        state.confirm("source-a", "CAM A", 12)

        assertFalse(state.release("source-b"))
        assertEquals("source-a", state.confirmedSourceInstanceId)
        assertTrue(state.release("source-a"))
        assertNull(state.confirmedSourceInstanceId)
    }

    @Test
    fun explicitDisconnectRejectsRetriedOldConnectButAcceptsFreshConnect() {
        val state = ReservationState()
        assertTrue(state.confirm("source-a", "CAM A", 12, "token-old"))

        state.disconnect()

        assertFalse(state.confirm("source-a", "CAM A", 12, "token-old"))
        assertNull(state.confirmedSourceInstanceId)
        assertTrue(state.confirm("source-a", "CAM A", 12, "token-new"))
        assertEquals("source-a", state.confirmedSourceInstanceId)
    }

    @Test
    fun sameOwnerCanRenewWithFreshTokenAtReservationBoundary() {
        val state = ReservationState()
        assertTrue(state.confirm("source-a", "CAM A", 12, "token-old"))

        assertTrue(state.confirm("source-a", "CAM B", 8, "token-new"))
        assertEquals("CAM B", state.confirmedReservation?.slotLabel)
        assertEquals(8, state.confirmedReservation?.bitrateMbps)
        assertEquals("token-new", state.confirmedReservation?.reservationToken)
    }

    @Test
    fun explicitReselectOfSameSourceClearsItsRevokedToken() {
        val state = ReservationState()
        state.confirm("source-a", "CAM A", 12, "token-old")
        state.disconnect()

        assertTrue(state.beginSelection(selection("source-a")))
        assertTrue(state.confirm("source-a", "CAM A", 12, "token-old"))
    }

    @Test
    fun repeatedDisconnectDoesNotEraseReplayProtection() {
        val state = ReservationState()
        state.confirm("source-a", "CAM A", 12, "token-old")
        state.disconnect()
        state.disconnect()

        assertFalse(state.confirm("source-a", "CAM A", 12, "token-old"))
    }

    @Test
    fun revokedTokenForEarlierSourceSurvivesLaterOwnerDisconnect() {
        val state = ReservationState()
        state.confirm("source-a", "CAM A", 12, "token-a")
        state.disconnect()
        state.confirm("source-b", "CAM B", 12, "token-b")
        state.disconnect()

        assertFalse(state.confirm("source-a", "CAM A", 12, "token-a"))
        assertTrue(state.confirm("source-a", "CAM A", 12, "token-a-new"))
    }
}
