package dev.openstream.app

/** Lifecycle facts owned by the long-lived phone session, independent of an Activity. */
internal sealed interface SessionOwnerState {
    data object Available : SessionOwnerState
    data class Foreground(val reservationId: String?) : SessionOwnerState
    data class Background(val reservationId: String?) : SessionOwnerState
    data object Stopped : SessionOwnerState
    data object PermissionRequired : SessionOwnerState
}

internal enum class SessionOwnerEvent {
    ActivityVisible,
    ActivityHidden,
    TaskRemoved,
    Stop,
    Start,
    CameraPermissionRevoked,
    ProcessRecreated,
}

/**
 * Pure lifecycle transition model. UI visibility never owns the active reservation; explicit Stop,
 * permission loss, or process death clears it so a later Activity cannot inherit stale ownership.
 */
internal fun transitionSessionOwner(
    state: SessionOwnerState,
    event: SessionOwnerEvent,
): SessionOwnerState = when (event) {
    SessionOwnerEvent.ActivityVisible -> when (state) {
        is SessionOwnerState.Background -> SessionOwnerState.Foreground(state.reservationId)
        SessionOwnerState.Available -> SessionOwnerState.Foreground(null)
        else -> state
    }
    SessionOwnerEvent.ActivityHidden,
    SessionOwnerEvent.TaskRemoved,
    -> when (state) {
        is SessionOwnerState.Foreground -> SessionOwnerState.Background(state.reservationId)
        else -> state
    }
    SessionOwnerEvent.Stop -> SessionOwnerState.Stopped
    SessionOwnerEvent.Start -> when (state) {
        SessionOwnerState.Stopped, SessionOwnerState.PermissionRequired -> SessionOwnerState.Available
        else -> state
    }
    SessionOwnerEvent.CameraPermissionRevoked -> SessionOwnerState.PermissionRequired
    // A sticky restart is deliberately not used. If Android recreates the process, the old socket
    // and reservation are gone; rebuild an available session only after a foreground UI launch.
    SessionOwnerEvent.ProcessRecreated -> SessionOwnerState.Available
}
