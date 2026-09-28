package dev.openstream.app

/** Prevents a restarted Activity from starting services while an earlier stop is tearing them down. */
internal class LifecycleStartGate {
    private var started = false
    private var pendingTeardowns = 0

    @Synchronized
    fun onStart(): Boolean {
        started = true
        return pendingTeardowns == 0
    }

    @Synchronized
    fun onStop() {
        started = false
        pendingTeardowns += 1
    }

    @Synchronized
    fun finishTeardown(): Boolean {
        check(pendingTeardowns > 0) { "No lifecycle teardown is pending" }
        pendingTeardowns -= 1
        return started && pendingTeardowns == 0
    }

    @Synchronized
    fun canStart(): Boolean = started && pendingTeardowns == 0
}
