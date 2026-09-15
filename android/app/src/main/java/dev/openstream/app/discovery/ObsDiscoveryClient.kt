package dev.openstream.app.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.openstream.app.stream.StreamConfig
import org.json.JSONObject
import java.net.DatagramPacket
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
    @Volatile private var pendingRestart = false
    private val mainHandler = Handler(Looper.getMainLooper())
    // Bounded: every queue/map declares capacity + overflow policy (AGENTS.md rule 16).
    // Oldest-seen entries are evicted first when the cap is reached.
    private val devices = linkedMapOf<String, DiscoveredObsDevice>()
    @Volatile private var socket: MulticastSocket? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var lastPublishMs = 0L
    @Volatile private var publishPending = false

    fun start() {
        if (running.get()) return
        if (worker?.isAlive == true) {
            Log.w(TAG, "Discovery worker is still stopping; restart will follow")
            pendingRestart = true
            return
        }
        if (!running.compareAndSet(false, true)) return
        pendingRestart = false
        worker = Thread(::receiveLoop, "OpenStreamDiscovery").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        pendingRestart = false
        running.set(false)
        socket?.close()
        socket = null
        val thread = worker
        thread?.interrupt()
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(STOP_TIMEOUT_MS) }
                .onFailure { Thread.currentThread().interrupt() }
        }
        if (worker === thread && thread?.isAlive != true) worker = null
        mainHandler.removeCallbacks(publishRunnable)
        publishPending = false
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
        // The multicast lock lifetime is paired to this worker: acquired here
        // (off the UI thread) and released in finally, so start()/stop() can
        // never leak a held lock.
        var udp: MulticastSocket? = null
        try {
            runCatching { acquireMulticastLock() }
                .onFailure { Log.w(TAG, "Could not acquire multicast lock", it) }
            val multicast = MulticastSocket(null).apply {
                reuseAddress = true
                soTimeout = 500
                bind(InetSocketAddress(DISCOVERY_PORT))
            }
            udp = multicast
            socket = multicast
            joinDiscoveryMulticast(multicast)
            val buffer = ByteArray(4096)

            while (running.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    multicast.receive(packet)
                    val payload = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
                    val host = packet.address.hostAddress ?: continue
                    val device = ObsDiscoveryProtocol.parseBeacon(payload, host, nowMs()) ?: continue
                    synchronized(devices) {
                        val key = device.instanceId.ifBlank { "${device.host}:${device.port}" }
                        if (key !in devices && devices.size >= MAX_DEVICES) {
                            // Overflow policy: evict the oldest-seen entry first.
                            val oldest = devices.minByOrNull { it.value.lastSeenMs }?.key
                                ?: devices.keys.firstOrNull()
                            if (oldest != null) {
                                Log.w(TAG, "Discovery device cap ($MAX_DEVICES) reached; evicting $oldest")
                                devices.remove(oldest)
                            }
                        }
                        devices[key] = device
                    }
                    pruneExpired()
                    publishDevicesDebounced()
                } catch (_: SocketTimeoutException) {
                    if (pruneExpired()) {
                        publishDevicesDebounced()
                    }
                } catch (error: Exception) {
                    if (multicast.isClosed) {
                        if (running.get()) {
                            Log.w(TAG, "Discovery socket closed unexpectedly", error)
                        }
                        break
                    }
                    if (running.get()) {
                        Log.w(TAG, "Discovery receive failed", error)
                        if (pruneExpired()) {
                            publishDevicesDebounced()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) Log.w(TAG, "Discovery worker failed", e)
        } finally {
            runCatching { udp?.close() }
            if (socket === udp) socket = null
            releaseMulticastLock()
            if (worker === Thread.currentThread()) worker = null
            running.set(false)
            if (pendingRestart) {
                pendingRestart = false
                start()
            }
        }
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
        lastPublishMs = nowMs()
        val snapshot = synchronized(devices) {
            devices.values.sortedWith(
                compareBy<DiscoveredObsDevice> { it.displayLabel }
                    .thenBy { it.sourceInstanceId }
            ).toList()
        }
        mainHandler.post {
            onDevicesChanged(snapshot)
        }
    }

    private val publishRunnable = Runnable {
        publishPending = false
        publishDevices()
    }

    /** Coalesces beacon bursts so the UI is not republished more often than ~300 ms. */
    private fun publishDevicesDebounced() {
        val now = nowMs()
        if (now - lastPublishMs >= PUBLISH_DEBOUNCE_MS) {
            publishDevices()
            return
        }
        if (!publishPending) {
            publishPending = true
            val delay = (PUBLISH_DEBOUNCE_MS - (now - lastPublishMs)).coerceAtLeast(0L)
            mainHandler.postDelayed(publishRunnable, delay)
        }
    }

    companion object {
        private const val TAG = "OpenStreamDiscovery"
        const val DISCOVERY_PORT = 51515
        const val DISCOVERY_MULTICAST_ADDRESS = "239.255.42.99"
        const val DEVICE_TTL_MS = 5_000L
        private const val STOP_TIMEOUT_MS = 1_000L
        private const val MAX_DEVICES = 64
        private const val PUBLISH_DEBOUNCE_MS = 300L
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
            latencyMs = json.optInt("latencyMs", 120).coerceIn(80, 200),
            bitrateMbps = json.optInt("bitrateMbps", StreamConfig.Default1080p30.bitrateMbps).coerceIn(
                StreamConfig.MIN_BITRATE_MBPS,
                StreamConfig.MAX_BITRATE_MBPS,
            ),
            instanceId = json.optString("instanceId", "$packetHost:$port"),
            sourceInstanceId = json.optString("sourceInstanceId", json.optString("instanceId", "$packetHost:$port")),
            slotId = json.optString("slotId", json.optString("instanceId", "$packetHost:$port")),
            slotLabel = json.optString("slotLabel", json.optString("name", "CAM A")).ifBlank { "CAM A" },
            pairingUrl = json.optString("pairingUrl", ""),
            lastSeenMs = nowMs,
            busy = json.optBoolean("busy", false),
        )
    }
}
