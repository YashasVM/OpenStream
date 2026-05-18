package dev.openstream.app.discovery

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class ObsDiscoveryClient(
    private val onDevicesChanged: (List<DiscoveredObsDevice>) -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val running = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val devices = linkedMapOf<String, DiscoveredObsDevice>()
    private var socket: DatagramSocket? = null
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
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
        publishDevices()
    }

    private fun receiveLoop() {
        val udp = DatagramSocket(null).apply {
            reuseAddress = true
            soTimeout = 500
            bind(InetSocketAddress(DISCOVERY_PORT))
        }
        socket = udp
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

        return DiscoveredObsDevice(
            name = json.optString("name", "OpenStream Phone Link").ifBlank { "OpenStream Phone Link" },
            host = packetHost,
            port = port,
            latencyMs = json.optInt("latencyMs", 120).coerceIn(20, 2000),
            bitrateMbps = json.optInt("bitrateMbps", 12).coerceIn(1, 200),
            instanceId = json.optString("instanceId", "$packetHost:$port"),
            lastSeenMs = nowMs,
            busy = json.optBoolean("busy", false),
        )
    }
}
