package dev.openstream.app.discovery

import java.net.Inet4Address
import java.net.NetworkInterface

data class NetworkEndpoint(
    val interfaceName: String,
    val hostAddress: String,
    val broadcastAddress: String?,
    val prefixLength: Short,
    val isUsb: Boolean,
    val networkInterface: NetworkInterface? = null,
)

object UsbNetworkSupport {
    const val TRANSPORT_USB = "usb"
    const val TRANSPORT_WIFI = "wifi"

    fun isUsbInterfaceName(name: String): Boolean {
        val lower = name.trim().lowercase()
        return lower.startsWith("rndis") ||
            lower.startsWith("usb") ||
            lower.startsWith("ncm")
    }

    fun collectIpv4Endpoints(): List<NetworkEndpoint> {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return emptyList()
        val result = ArrayList<NetworkEndpoint>()
        for (iface in interfaces.asSequence()) {
            val isUp = runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)
            if (!isUp) continue
            val isUsb = isUsbInterfaceName(iface.name.orEmpty())
            val addresses = runCatching { iface.interfaceAddresses }.getOrNull().orEmpty()
            for (interfaceAddress in addresses) {
                val addr = interfaceAddress.address as? Inet4Address ?: continue
                if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                val host = addr.hostAddress ?: continue
                val broadcast = (interfaceAddress.broadcast as? Inet4Address)?.hostAddress
                result += NetworkEndpoint(
                    interfaceName = iface.name.orEmpty(),
                    hostAddress = host,
                    broadcastAddress = broadcast,
                    prefixLength = interfaceAddress.networkPrefixLength,
                    isUsb = isUsb,
                    networkInterface = iface,
                )
            }
        }
        return result
    }

    fun parseIpv4Bits(host: String): Int? {
        val parts = host.trim().split('.')
        if (parts.size != 4) return null
        var value = 0
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet
        }
        return value
    }

    fun isOnSameIpv4Subnet(hostA: String, hostB: String, prefixLength: Short): Boolean {
        if (prefixLength !in 1..32) return false
        val a = parseIpv4Bits(hostA) ?: return false
        val b = parseIpv4Bits(hostB) ?: return false
        val mask = (-1) shl (32 - prefixLength.toInt())
        return (a and mask) == (b and mask)
    }

    fun isHostOnUsbSubnet(host: String, endpoints: List<NetworkEndpoint>): Boolean {
        return endpoints.any { endpoint ->
            endpoint.isUsb && isOnSameIpv4Subnet(host, endpoint.hostAddress, endpoint.prefixLength)
        }
    }

    fun selectPreferredHostAddress(
        endpoints: List<NetworkEndpoint>,
        wifiFallback: String?,
    ): String? {
        return endpoints.firstOrNull { it.isUsb }?.hostAddress
            ?: endpoints.firstOrNull()?.hostAddress
            ?: wifiFallback?.takeIf { it.isNotBlank() }
    }

    fun shouldReplaceDiscoveredDevice(
        existing: DiscoveredObsDevice?,
        incoming: DiscoveredObsDevice,
        nowMs: Long,
        usbHoldMs: Long,
    ): Boolean {
        if (existing != null && existing.isUsb && !incoming.isUsb) {
            val ageMs = nowMs - existing.lastSeenMs
            if (ageMs in 0 until usbHoldMs) {
                return false
            }
        }
        return true
    }
}
