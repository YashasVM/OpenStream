package dev.openstream.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.io.File
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/** Fails closed when Android does not expose proof that the selected route is USB. */
class UsbStreamRoute(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    data class Verified(val localAddress: String, val interfaceName: String, val targetAddress: String)
    data class Result(val route: Verified?, val reason: String)

    /** Run off the UI thread. A cable's charging state and interface names are not transport proof. */
    fun verify(targetHost: String, targetPort: Int): Result = try {
        require(targetPort in 1..65535)
        val targets = InetAddress.getAllByName(targetHost).filterIsInstance<Inet4Address>()
        var verified: Verified? = null
        for (target in targets) {
            val local = DatagramSocket().use { socket ->
                socket.connect(InetSocketAddress(target, targetPort))
                socket.localAddress
            }
            val networkInterface = NetworkInterface.getByInetAddress(local) ?: continue
            if (!networkInterface.isUp || networkInterface.isLoopback) continue
            // Restrict to a directly attached peer. Internet/default routes cannot certify the OBS cable.
            if (networkInterface.interfaceAddresses.none { address ->
                address.address == local && sameSubnet(local.address, target.address, address.networkPrefixLength.toInt())
            }) continue
            val declaredUsb = Build.VERSION.SDK_INT >= 31 && connectivity.allNetworks.any { network ->
                val capabilities = connectivity.getNetworkCapabilities(network)
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_USB) == true &&
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN).not() &&
                    connectivity.getLinkProperties(network)?.interfaceName == networkInterface.name
            }
            val devicePath = runCatching {
                File("/sys/class/net/${networkInterface.name}/device").canonicalPath
            }.getOrDefault("")
            val usbDevice = devicePath.split('/').any { it.matches(Regex("usb[0-9]+")) }
            if (!declaredUsb && !usbDevice) continue
            verified = Verified(checkNotNull(local.hostAddress), networkInterface.name, checkNotNull(target.hostAddress))
            break
        }
        if (verified == null) Result(null,
            "No verified USB route to OBS. Enable USB tethering and use the computer's USB network address; this Android build may hide USB route evidence.")
        else Result(verified, "USB route verified on ${verified.interfaceName}")
    } catch (error: Exception) {
        Result(null, "Cannot verify USB route: ${error.message}")
    }

    /** Recheck before connecting and periodically while live; stop high modes on any mismatch. */
    fun isCurrent(route: Verified, targetPort: Int): Boolean =
        verify(route.targetAddress, targetPort).route == route

    companion object {
        internal fun sameSubnet(local: ByteArray, peer: ByteArray, prefix: Int): Boolean {
            if (local.size != 4 || peer.size != 4 || prefix !in 1..32) return false
            for (index in local.indices) {
                val bits = (prefix - index * 8).coerceIn(0, 8)
                val mask = (0xff shl (8 - bits)) and 0xff
                if ((local[index].toInt() and mask) != (peer[index].toInt() and mask)) return false
            }
            return true
        }
    }
}
