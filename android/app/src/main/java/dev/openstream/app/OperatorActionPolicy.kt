package dev.openstream.app

/** Keeps the minimal operator surface from starting duplicate listener sessions. */
object OperatorActionPolicy {
    fun canStart(phoneServerRunning: Boolean, isLive: Boolean): Boolean =
        !phoneServerRunning && !isLive

    fun shouldShowStop(remoteArmed: Boolean, isLive: Boolean): Boolean =
        remoteArmed || isLive
}
