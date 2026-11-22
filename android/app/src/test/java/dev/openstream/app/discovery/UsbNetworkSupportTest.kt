package dev.openstream.app.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbNetworkSupportTest {
    @Test
    fun identifiesAndroidUsbTetheringInterfaceNames() {
        assertTrue(UsbNetworkSupport.isUsbInterfaceName("rndis0"))
        assertTrue(UsbNetworkSupport.isUsbInterfaceName("RNDIS1"))
        assertTrue(UsbNetworkSupport.isUsbInterfaceName("usb0"))
        assertTrue(UsbNetworkSupport.isUsbInterfaceName("ncm0"))
        assertFalse(UsbNetworkSupport.isUsbInterfaceName("wlan0"))
        assertFalse(UsbNetworkSupport.isUsbInterfaceName("rmnet_data0"))
        assertFalse(UsbNetworkSupport.isUsbInterfaceName("lo"))
    }

    @Test
    fun matchesIpv4SubnetsForUsbPeerDetection() {
        val endpoints = listOf(
            NetworkEndpoint("wlan0", "192.168.1.50", "192.168.1.255", 24, isUsb = false),
            NetworkEndpoint("rndis0", "192.168.42.129", "192.168.42.255", 24, isUsb = true),
        )
        assertTrue(UsbNetworkSupport.isHostOnUsbSubnet("192.168.42.10", endpoints))
        assertFalse(UsbNetworkSupport.isHostOnUsbSubnet("192.168.1.10", endpoints))
        assertFalse(UsbNetworkSupport.isHostOnUsbSubnet("not-an-ip", endpoints))
        assertTrue(UsbNetworkSupport.isOnSameIpv4Subnet("172.16.10.2", "172.16.99.5", 16))
        assertFalse(UsbNetworkSupport.isOnSameIpv4Subnet("172.16.10.2", "172.17.10.2", 16))
    }

    @Test
    fun prefersUsbAddressOverWifiFallback() {
        val mixed = listOf(
            NetworkEndpoint("wlan0", "192.168.1.50", "192.168.1.255", 24, isUsb = false),
            NetworkEndpoint("rndis0", "192.168.42.129", "192.168.42.255", 24, isUsb = true),
        )
        assertEquals("192.168.42.129", UsbNetworkSupport.selectPreferredHostAddress(mixed, "192.168.1.50"))
        assertEquals("192.168.1.50", UsbNetworkSupport.selectPreferredHostAddress(emptyList(), "192.168.1.50"))
    }

    @Test
    fun keepsFreshUsbRouteOverWifiAndAllowsFallbackAfterHoldExpires() {
        val usbDevice = sampleDevice(host = "192.168.42.10", lastSeenMs = 1_000L, isUsb = true)
        val wifiDevice = sampleDevice(host = "192.168.1.10", lastSeenMs = 2_000L, isUsb = false)
        val freshUsbUpdate = sampleDevice(host = "192.168.42.10", lastSeenMs = 2_000L, isUsb = true)
        val wifiAfterHold = sampleDevice(host = "192.168.1.10", lastSeenMs = 4_500L, isUsb = false)

        assertFalse(UsbNetworkSupport.shouldReplaceDiscoveredDevice(usbDevice, wifiDevice, 2_000L, 3_000L))
        assertTrue(UsbNetworkSupport.shouldReplaceDiscoveredDevice(usbDevice, freshUsbUpdate, 2_000L, 3_000L))
        assertTrue(UsbNetworkSupport.shouldReplaceDiscoveredDevice(wifiDevice, freshUsbUpdate, 2_000L, 3_000L))
        assertTrue(UsbNetworkSupport.shouldReplaceDiscoveredDevice(usbDevice, wifiAfterHold, 4_500L, 3_000L))
    }

    private fun sampleDevice(host: String, lastSeenMs: Long, isUsb: Boolean): DiscoveredObsDevice {
        return DiscoveredObsDevice(
            name = "OBS PC",
            host = host,
            port = 9100,
            latencyMs = 120,
            bitrateMbps = 12,
            instanceId = "obs-1",
            sourceInstanceId = "obs-1",
            slotId = "obs-1",
            slotLabel = "Phone Camera",
            pairingUrl = "",
            lastSeenMs = lastSeenMs,
            busy = false,
            isUsb = isUsb,
        )
    }
}
