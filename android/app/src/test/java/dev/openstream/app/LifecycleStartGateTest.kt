package dev.openstream.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LifecycleStartGateTest {
    @Test
    fun returningBeforeStopTeardownFinishesDefersRestartUntilCleanupCompletes() {
        val gate = LifecycleStartGate()

        assertTrue(gate.onStart())
        gate.onStop()
        assertFalse(gate.canStart())

        assertFalse(gate.onStart())
        assertFalse(gate.canStart())
        assertTrue(gate.finishTeardown())
        assertTrue(gate.canStart())
    }

    @Test
    fun staleTeardownCompletionCannotRestartAfterAnotherStop() {
        val gate = LifecycleStartGate()
        gate.onStart()
        gate.onStop()
        gate.onStart()
        gate.onStop()

        assertFalse(gate.finishTeardown())
        assertFalse(gate.canStart())
        assertFalse(gate.finishTeardown())
        assertFalse(gate.canStart())
    }
}
