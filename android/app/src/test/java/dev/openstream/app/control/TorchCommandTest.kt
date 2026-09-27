package dev.openstream.app.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorchCommandTest {
    @Test
    fun enablingTorchRequiresFlashCapability() {
        assertTrue(torchCommandAllowed(enabled = true, torchAvailable = true))
        assertFalse(torchCommandAllowed(enabled = true, torchAvailable = false))
    }

    @Test
    fun disablingTorchRemainsAllowedAfterSwitchingToAFlashlessCamera() {
        assertTrue(torchCommandAllowed(enabled = false, torchAvailable = false))
    }
}
