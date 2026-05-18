package dev.openstream.app.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class ObsDiscoveryClient(
    private val context: Context,
    private val onDevicesChanged: (List<DiscoveredObsDevice>) -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val running = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val devices = linkedMapOf<String, DiscoveredObsDevice>()
    private var socket: MulticastSocket? = null
    private var worker: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        acquireMulticastLock()
        worker = Thread(::receiveLoop, "OpenStreamDiscovery").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        socket?.close()
        socket = null
        worker = null
        synchronized(devices) {
            devices.clear()
        }
        releaseMulticastLock()
        publishDevices()
    }

    private fun acquireMulticastLock() {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java) ?: return
        multicastLock = wifiManager.createMulticastLock("OpenStreamDiscovery").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        multicastLock = null
    }

    private fun receiveLoop() {
        val udp = MulticastSocket(null).apply {
            reuseAddress = true
            soTimeout = 500
            bind(InetSocketAddress(DISCOVERY_PORT))
        }
        socket = udp
        joinDiscoveryMulticast(udp)
        val buffer = ByteArray(4096)

        while (running.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                udp.receive(packet)
                val payload = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
                val host = packet.address.hostAddress ?: continue
                val device = ObsDiscoveryProtocol.parseBeacon(payload, host, nowMs()) ?: continue
                synchronized(devices) {
                    devices[device.instanceId.ifBlank { "${device.host}:${device.port}" }] = device
                }
                pruneExpired()
                publishDevices()
            } catch (_: SocketTimeoutException) {
                if (pruneExpired()) {
                    publishDevices()
                }
            } catch (_: Exception) {
                if (running.get()) {
                    if (pruneExpired()) {
                        publishDevices()
                    }
                }
            }
        }
        udp.close()
    }

    private fun joinDiscoveryMulticast(socket: MulticastSocket) {
        val group = InetAddress.getByName(DISCOVERY_MULTICAST_ADDRESS)
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .forEach { iface ->
                runCatching {
                    socket.joinGroup(InetSocketAddress(group, DISCOVERY_PORT), iface)
                }
            }
    }

    private fun pruneExpired(): Boolean {
        val cutoff = nowMs() - DEVICE_TTL_MS
        synchronized(devices) {
            val before = devices.size
            devices.entries.removeAll { it.value.lastSeenMs < cutoff }
            return before != devices.size
        }
    }

    private fun publishDevices() {
        val snapshot = synchronized(devices) {
            devices.values.sortedWith(compareBy<DiscoveredObsDevice> { it.busy }.thenBy { it.name }).toList()
        }
        mainHandler.post {
            onDevicesChanged(snapshot)
        }
    }

    companion object {
        const val DISCOVERY_PORT = 51515
        const val DISCOVERY_MULTICAST_ADDRESS = "239.255.42.99"
        const val DEVICE_TTL_MS = 5_000L
    }
}

object ObsDiscoveryProtocol {
    private const val PREFIX = "OPENSTREAM/1 "
    private const val TYPE = "dev.openstream.listener"

    fun parseBeacon(payload: String, packetHost: String, nowMs: Long): DiscoveredObsDevice? {
        if (!payload.startsWith(PREFIX)) return null
        val json = runCatching { JSONObject(payload.removePrefix(PREFIX)) }.getOrNull() ?: return null
        if (json.optString("type") != TYPE) return null
        if (json.optInt("version") != 1) return null

        val port = json.optInt("listenerPort", -1)
        if (port !in 1..65535) return null
        val advertisedHost = json.optString("host").trim()
        val host = advertisedHost.ifBlank { packetHost }

        return DiscoveredObsDevice(
            name = json.optString("name", "OpenStream Phone Link").ifBlank { "OpenStream Phone Link" },
            host = host,
            port = port,
            latencyMs = json.optInt("latencyMs", 120).coerceIn(20, 2000),
            bitrateMbps = json.optInt("bitrateMbps", 12).coerceIn(1, 200),
            instanceId = json.optString("instanceId", "$packetHost:$port"),
            lastSeenMs = nowMs,
            busy = json.optBoolean("busy", false),
        )
    }
}
