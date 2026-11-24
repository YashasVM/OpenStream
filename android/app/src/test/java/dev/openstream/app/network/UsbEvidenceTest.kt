package dev.openstream.app.network

import org.junit.Assert.*
import org.junit.Test

class UsbEvidenceTest {
    @Test fun gadgetRequiresActiveNetworkingAndTetheredInterface() {
        assertTrue(UsbStreamRoute.gadgetEvidence(true, true, true, "rndis0"))
        assertTrue(UsbStreamRoute.gadgetEvidence(true, true, true, "ncm0"))
        assertFalse(UsbStreamRoute.gadgetEvidence(false, true, true, "rndis0"))
        assertFalse(UsbStreamRoute.gadgetEvidence(true, false, true, "rndis0"))
        assertFalse(UsbStreamRoute.gadgetEvidence(true, true, false, "rndis0"))
        assertFalse(UsbStreamRoute.gadgetEvidence(true, true, true, "wlan0"))
    }
    @Test fun routeLossOrPeerChangeInvalidatesHighSession() {
        val route = UsbStreamRoute.Verified("192.168.42.129", "rndis0", "192.168.42.2")
        assertTrue(UsbStreamRoute.matches(route, route.copy()))
        assertFalse(UsbStreamRoute.matches(route, null))
        assertFalse(UsbStreamRoute.matches(route, route.copy(interfaceName = "wlan0")))
        assertFalse(UsbStreamRoute.matches(route, route.copy(targetAddress = "192.168.42.3")))
    }
}
