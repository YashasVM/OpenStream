package dev.openstream.app.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlAuthorizationTest {
    @Test
    fun missingOwnerOrPeerFailsClosed() {
        assertFalse(ControlAuthorization.isAuthorized(null, "10.0.0.2", "10.0.0.2"))
        assertFalse(ControlAuthorization.isAuthorized("source-a", "", "10.0.0.2"))
        assertFalse(ControlAuthorization.isAuthorized("source-a", "10.0.0.3", "10.0.0.2"))
    }

    @Test
    fun legacyPeerRequestAndMatchingModernIdentityAreAccepted() {
        assertTrue(ControlAuthorization.isAuthorized("source-a", "10.0.0.2", "10.0.0.2"))
        assertTrue(
            ControlAuthorization.isAuthorized(
                "source-a", "10.0.0.2", "10.0.0.2", "source-a", "token-a", "token-a",
            ),
        )
    }

    @Test
    fun wrongOrPartialModernIdentityIsRejected() {
        assertFalse(
            ControlAuthorization.isAuthorized(
                "source-a", "10.0.0.2", "10.0.0.2", "source-b", "token-a", "token-a",
            ),
        )
        assertFalse(
            ControlAuthorization.isAuthorized(
                "source-a", "10.0.0.2", "10.0.0.2", "source-a", "old-token", "token-a",
            ),
        )
        assertFalse(
            ControlAuthorization.isAuthorized(
                "source-a", "10.0.0.2", "10.0.0.2", "source-a", "", "token-a",
            ),
        )
    }
}
