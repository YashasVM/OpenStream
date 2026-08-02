package dev.openstream.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatorActionPolicyTest {
    @Test
    fun startIsOnlyAvailableWhenNoListenerOrLiveStreamExists() {
        assertTrue(OperatorActionPolicy.canStart(phoneServerRunning = false, isLive = false))
        assertFalse(OperatorActionPolicy.canStart(phoneServerRunning = true, isLive = false))
        assertFalse(OperatorActionPolicy.canStart(phoneServerRunning = false, isLive = true))
    }

    @Test
    fun stopRemainsVisibleForAnArmedOrLiveSession() {
        assertFalse(OperatorActionPolicy.shouldShowStop(remoteArmed = false, isLive = false))
        assertTrue(OperatorActionPolicy.shouldShowStop(remoteArmed = true, isLive = false))
        assertTrue(OperatorActionPolicy.shouldShowStop(remoteArmed = false, isLive = true))
    }
}
