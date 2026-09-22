package dev.openstream.app.discovery

import org.junit.Assert.assertEquals
import org.junit.Test

class ObsDiscoveryProtocolTest {
    @Test
    fun discoveredObsTargetUsesDatagramPeerAddressForConnection() {
        assertEquals(
            "192.168.1.24",
            ObsDiscoveryProtocol.connectionHost("192.168.1.24", "10.0.0.77"),
        )
    }

    @Test
    fun discoveryFallsBackToAdvertisedHostWithoutDatagramPeer() {
        assertEquals(
            "10.0.0.77",
            ObsDiscoveryProtocol.connectionHost("", "10.0.0.77"),
        )
    }

    @Test
    fun manualBeaconUsesExplicitHostVerbatim() {
        assertEquals(
            "10.0.0.77",
            ObsDiscoveryProtocol.manualConnectionHost("  10.0.0.77  "),
        )
        assertEquals(
            "",
            ObsDiscoveryProtocol.manualConnectionHost("   "),
        )
    }

    @Test
    fun beaconWithoutAnyHostIsRejected() {
        val beacon = """SHIN/1 {"type":"dev.shin.listener","version":1,"listenerPort":7000,"host":""}"""
        assertEquals(
            null,
            ObsDiscoveryProtocol.parseBeacon(beacon, "", 0L),
        )
    }

    @Test
    fun legacyOpenStreamBeaconIsAdaptedAtDiscoveryBoundary() {
        val beacon = """OPENSTREAM/1 {"type":"dev.openstream.listener","version":1,"listenerPort":9000,"host":"10.0.0.7","name":"Legacy OBS"}"""
        val device = ObsDiscoveryProtocol.parseBeacon(beacon, "192.168.1.24", 42L)

        assertEquals("Legacy OBS", device?.name)
        assertEquals("192.168.1.24", device?.host)
        assertEquals(9000, device?.port)
    }
}
