package dev.openstream.app.irl

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log

/**
 * Follows the system default network and reports (networkHandle, label) on
 * every change of network or transport type. A null handle means no network.
 * Callbacks arrive on the ConnectivityManager callback thread.
 */
class NetworkMonitor(context: Context, private val listener: (id: Long?, label: String) -> Unit) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private var current: Network? = null
    private var reported: Pair<Long?, String>? = null
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            update(network, runCatching { connectivity?.getNetworkCapabilities(network) }.getOrNull())
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            update(network, capabilities)
        }

        override fun onLost(network: Network) {
            synchronized(lock) {
                if (current != network) return
                current = null
                report(null, NONE)
            }
        }
    }

    fun start() = synchronized(lock) {
        if (registered) return
        val manager = connectivity ?: return report(null, NONE)
        registered = runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onFailure { Log.w(TAG, "Could not watch the default network", it) }
            .isSuccess
        if (!registered) report(null, NONE)
    }

    fun stop() = synchronized(lock) {
        if (!registered) return
        registered = false
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
        current = null
        reported = null
    }

    private fun update(network: Network, capabilities: NetworkCapabilities?) = synchronized(lock) {
        if (!registered) return
        current = network
        report(network.networkHandle, label(capabilities))
    }

    private fun report(id: Long?, label: String) {
        val next = id to label
        if (reported == next) return
        reported = next
        listener(id, label)
    }

    companion object {
        private const val TAG = "shinIrlNetwork"
        const val NONE = "None"

        fun label(capabilities: NetworkCapabilities?): String = when {
            capabilities == null -> "Unknown"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Other"
        }
    }
}
