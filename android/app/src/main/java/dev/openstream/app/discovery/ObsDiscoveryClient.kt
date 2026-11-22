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
    // Access-ordered so a re-put refreshes recency; the eldest entry (oldest
    // last-seen beacon) is evicted first when the cap is reached.
    private val devices = object : LinkedHashMap<String, DiscoveredObsDevice>(MAX_DEVICES + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DiscoveredObsDevice>): Boolean {
            if (size > MAX_DEVICES) {
                Log.w(TAG, "Discovery device cap ($MAX_DEVICES) reached; evicting ${eldest.key}")
                return true
            }
            return false
        }
    }
    private val socketLock = Any()
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
        worker = Thread(::receiveLoop, "shinDiscovery").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        pendingRestart = false
        running.set(false)
        synchronized(socketLock) {
            runCatching { socket?.close() }
            socket = null
        }
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
        multicastLock = wifiManager.createMulticastLock("shinDiscovery").apply {
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
            var activeInterfaces = activeMulticastInterfaces()
            var joinedSignature = multicastInterfaceSignature(activeInterfaces)
            var multicast = openMulticastSocket(activeInterfaces) ?: return
            udp = multicast
            val buffer = ByteArray(4096)
            var cachedEndpoints = UsbNetworkSupport.collectIpv4Endpoints()
            var lastInterfaceRefreshNs = System.nanoTime()

            while (running.get()) {
                val refreshNowNs = System.nanoTime()
                if (refreshNowNs - lastInterfaceRefreshNs >= INTERFACE_REFRESH_NS) {
                    cachedEndpoints = UsbNetworkSupport.collectIpv4Endpoints()
                    val currentInterfaces = activeMulticastInterfaces()
                    val currentSignature = multicastInterfaceSignature(currentInterfaces)
                    if (currentSignature != joinedSignature) {
                        // Recreating the socket releases stale kernel multicast
                        // memberships from vanished tether adapters so repeated
                        // USB reconnects never hit ENOBUFS.
                        closeTrackedSocket(multicast)
                        udp = null
                        val reopened = openMulticastSocket(currentInterfaces) ?: break
                        multicast = reopened
                        udp = reopened
                        activeInterfaces = currentInterfaces
                        joinedSignature = currentSignature
                    }
                    lastInterfaceRefreshNs = refreshNowNs
                }
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    multicast.receive(packet)
                    val payload = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
                    val host = packet.address.hostAddress ?: continue
                    val parsed = ObsDiscoveryProtocol.parseBeacon(payload, host, nowMs()) ?: continue
                    val isUsb = UsbNetworkSupport.isHostOnUsbSubnet(parsed.host, cachedEndpoints)
                    val device = if (isUsb) parsed.copy(isUsb = true) else parsed
                    val updated = synchronized(devices) {
                        val key = device.instanceId.ifBlank { "${device.host}:${device.port}" }
                        val existing = devices[key]
                        if (UsbNetworkSupport.shouldReplaceDiscoveredDevice(
                                existing = existing,
                                incoming = device,
                                nowMs = device.lastSeenMs,
                                usbHoldMs = USB_ROUTE_HOLD_MS,
                            )
                        ) {
                            devices[key] = device
                            true
                        } else {
                            false
                        }
                    }
                    val pruned = pruneExpired()
                    if (updated || pruned) {
                        publishDevicesDebounced()
                    }
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
            closeTrackedSocket(udp)
            releaseMulticastLock()
            if (worker === Thread.currentThread()) worker = null
            running.set(false)
            if (pendingRestart) {
                pendingRestart = false
                start()
            }
        }
    }

    private fun openMulticastSocket(interfaces: List<NetworkInterface>): MulticastSocket? {
        val fresh = MulticastSocket(null).apply {
            reuseAddress = true
            soTimeout = 500
            bind(InetSocketAddress(DISCOVERY_PORT))
        }
        val active = synchronized(socketLock) {
            if (!running.get()) {
                false
            } else {
                socket = fresh
                true
            }
        }
        if (!active) {
            runCatching { fresh.close() }
            return null
        }
        joinDiscoveryMulticast(fresh, interfaces)
        return fresh
    }

    private fun closeTrackedSocket(target: MulticastSocket?) {
        if (target == null) return
        synchronized(socketLock) {
            if (socket === target) socket = null
        }
        runCatching { target.close() }
    }

    private fun activeMulticastInterfaces(): List<NetworkInterface> {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return emptyList()
        return interfaces.asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .toList()
    }

    private fun multicastInterfaceSignature(interfaces: List<NetworkInterface>): Set<String> =
        interfaces.map { iface ->
            val ipv4s = runCatching {
                iface.inetAddresses.asSequence()
                    .filterIsInstance<java.net.Inet4Address>()
                    .mapNotNull { it.hostAddress }
                    .sorted()
                    .joinToString(",")
            }.getOrDefault("")
            "${iface.index}:${iface.name}:$ipv4s"
        }.toSet()

    private fun joinDiscoveryMulticast(
        socket: MulticastSocket,
        interfaces: List<NetworkInterface>,
    ) {
        val group = InetAddress.getByName(DISCOVERY_MULTICAST_ADDRESS)
        interfaces.forEach { iface ->
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
        private const val TAG = "shinDiscovery"
        const val DISCOVERY_PORT = 51615
        const val DISCOVERY_MULTICAST_ADDRESS = "239.255.43.99"
        const val DEVICE_TTL_MS = 5_000L
        private const val STOP_TIMEOUT_MS = 1_000L
        private const val MAX_DEVICES = 64
        private const val PUBLISH_DEBOUNCE_MS = 300L
        private const val INTERFACE_REFRESH_NS = 2_000_000_000L
        private const val USB_ROUTE_HOLD_MS = 3_000L
    }
}

object ObsDiscoveryProtocol {
    private const val PREFIX = "SHIN/1 "
    private const val TYPE = "dev.shin.listener"

    fun parseBeacon(payload: String, packetHost: String, nowMs: Long): DiscoveredObsDevice? {
        if (!payload.startsWith(PREFIX)) return null
        val json = runCatching { JSONObject(payload.removePrefix(PREFIX)) }.getOrNull() ?: return null
        if (json.optString("type") != TYPE) return null
        if (json.optInt("version") != 1) return null

        val port = json.optInt("listenerPort", -1)
        if (port !in 1..65535) return null
        val advertisedHost = json.optString("host").trim()
        val host = connectionHost(packetHost, advertisedHost)
        if (host.isBlank()) return null
        val fallbackId = json.optString("instanceId", "$packetHost:$port")

        return DiscoveredObsDevice(
            name = json.optString("name", "OpenStream Camera").ifBlank { "OpenStream Camera" },
            host = host,
            port = port,
            latencyMs = json.optInt("latencyMs", 120).coerceIn(80, 200),
            bitrateMbps = json.optInt("bitrateMbps", StreamConfig.Default1080p30.bitrateMbps).coerceIn(
                StreamConfig.MIN_BITRATE_MBPS,
                StreamConfig.MAX_BITRATE_MBPS,
            ),
            instanceId = fallbackId,
            sourceInstanceId = json.optString("sourceInstanceId", fallbackId),
            slotId = json.optString("slotId", fallbackId),
            slotLabel = json.optString("slotLabel", json.optString("name", "CAM A")).ifBlank { "CAM A" },
            pairingUrl = json.optString("pairingUrl", ""),
            lastSeenMs = nowMs,
            busy = json.optBoolean("busy", false),
        )
    }

    /**
     * Discovery is a same-LAN control protocol, so the UDP peer is the
     * authoritative address for the follow-up SRT connection. An advertised
     * host can be stale, belong to another interface, or be a VPN address.
     * Keep it only as a fallback for callers that do not have a peer address
     * (for example, a persisted/manual beacon).
     *
     * Manual beacons must pass an empty [packetHost] so the explicit
     * [advertisedHost] is used verbatim. Multi-homed/VPN hosts that need to
     * override the UDP peer should use [manualConnectionHost].
     */
    internal fun connectionHost(packetHost: String, advertisedHost: String): String =
        packetHost.trim().ifBlank { advertisedHost.trim() }

    /**
     * Explicit manual override for persisted beacons and multi-homed/VPN hosts.
     * Returns the trimmed manual host, or empty if none was supplied so callers
     * can reject the beacon instead of connecting to a blank host.
     */
    internal fun manualConnectionHost(manualHost: String): String = manualHost.trim()
}
