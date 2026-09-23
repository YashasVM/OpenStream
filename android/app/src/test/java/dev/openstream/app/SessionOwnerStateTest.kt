package dev.openstream.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionOwnerStateTest {
    @Test
    fun homeLockSettingsAndTaskRemovalKeepReservationInBackgroundOwner() {
        val live = SessionOwnerState.Foreground("obs-source-7")

        assertEquals(
            SessionOwnerState.Background("obs-source-7"),
            transitionSessionOwner(live, SessionOwnerEvent.ActivityHidden),
        )
        assertEquals(
            SessionOwnerState.Background("obs-source-7"),
            transitionSessionOwner(live, SessionOwnerEvent.TaskRemoved),
        )
        assertEquals(
            live,
            transitionSessionOwner(
                transitionSessionOwner(live, SessionOwnerEvent.ActivityHidden),
                SessionOwnerEvent.ActivityVisible,
            ),
        )
    }

    @Test
    fun stopPermissionRevocationAndProcessRecreationClearReservation() {
        val live = SessionOwnerState.Background("obs-source-7")

        assertEquals(SessionOwnerState.Stopped, transitionSessionOwner(live, SessionOwnerEvent.Stop))
        assertEquals(
            SessionOwnerState.PermissionRequired,
            transitionSessionOwner(live, SessionOwnerEvent.CameraPermissionRevoked),
        )
        assertEquals(
            SessionOwnerState.Available,
            transitionSessionOwner(live, SessionOwnerEvent.ProcessRecreated),
        )
        assertEquals(
            SessionOwnerState.Available,
            transitionSessionOwner(SessionOwnerState.Stopped, SessionOwnerEvent.Start),
        )
    }
}
