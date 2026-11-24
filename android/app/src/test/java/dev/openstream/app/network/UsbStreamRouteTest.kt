package dev.openstream.app.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbStreamRouteTest {
    private val local = byteArrayOf(192.toByte(), 168.toByte(), 42, 1)
    @Test fun directlyAttachedPeerMustMatchPrefix() {
        assertTrue(UsbStreamRoute.sameSubnet(local, byteArrayOf(192.toByte(), 168.toByte(), 42, 8), 24))
        assertFalse(UsbStreamRoute.sameSubnet(local, byteArrayOf(192.toByte(), 168.toByte(), 43, 8), 24))
        assertFalse(UsbStreamRoute.sameSubnet(local, byteArrayOf(192.toByte(), 168.toByte(), 42, 8), 32))
        assertTrue(UsbStreamRoute.sameSubnet(local, local, 32))
        assertFalse(UsbStreamRoute.sameSubnet(local, local, 0))
        assertFalse(UsbStreamRoute.sameSubnet(local, local, 33))
    }
    @Test fun partialOctetMasksAreApplied() {
        assertTrue(UsbStreamRoute.sameSubnet(local, byteArrayOf(192.toByte(), 168.toByte(), 43, 8), 23))
        assertFalse(UsbStreamRoute.sameSubnet(local, byteArrayOf(192.toByte(), 168.toByte(), 44, 8), 23))
        assertFalse(UsbStreamRoute.sameSubnet(local, ByteArray(16), 24))
    }
}
