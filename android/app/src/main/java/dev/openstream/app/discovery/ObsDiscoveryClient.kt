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
import java.util.concurrent.atomic.AtomicLong

class ObsDiscoveryClient(
    private val context: Context,
    private val onDevicesChanged: (List<DiscoveredObsDevice>) -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val running = AtomicBoolean(false)
    private val publishGeneration = AtomicLong(0L)
    private val lifecycleLock = Any()
    private var desiredRunning = false
    private var pendingRestart = false
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
    @Volatile private var socket: MulticastSocket? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var lastPublishMs = 0L
    @Volatile private var publishPending = false

    fun start() {
        synchronized(lifecycleLock) {
            desiredRunning = true
            startLocked()
        }
    }

    private fun startLocked() {
        if (!desiredRunning || running.get()) return
        if (worker?.isAlive == true) {
            pendingRestart = true
            Log.w(TAG, "Discovery worker is still stopping; restart will follow")
            return
        }
        if (!running.compareAndSet(false, true)) return
        pendingRestart = false
        publishGeneration.incrementAndGet()
        worker = Thread(::receiveLoop, "shinDiscovery").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        val thread = synchronized(lifecycleLock) {
            desiredRunning = false
            pendingRestart = false
            running.set(false)
            runCatching { socket?.close() }
            socket = null
            worker.also { it?.interrupt() }
        }
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(STOP_TIMEOUT_MS) }
                .onFailure { Thread.currentThread().interrupt() }
        }
        val emptyPublishGen: Long? = synchronized(lifecycleLock) {
            if (worker === thread && thread?.isAlive != true) worker = null
            if (!desiredRunning) {
                val gen = publishGeneration.incrementAndGet()
                lastPublishMs = nowMs()
                mainHandler.removeCallbacks(publishRunnable)
                publishPending = false
                synchronized(devices) {
                    devices.clear()
                }
                gen
            } else {
                null
            }
        }
        if (emptyPublishGen != null) {
            mainHandler.post {
                if (publishGeneration.get() == emptyPublishGen) {
                    onDevicesChanged(emptyList())
                }
            }
        }
    }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        return wifiManager.createMulticastLock("shinDiscovery").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock(lock: WifiManager.MulticastLock?) {
        if (lock != null && lock.isHeld) {
            runCatching { lock.release() }
        }
    }

    private fun receiveLoop() {
        // Each worker owns its own MulticastLock and socket, acquired here and
        // released exclusively in this worker's finally block so a concurrent
        // stop()/start() transition can never release a replacement worker's lock.
        var udp: MulticastSocket? = null
        var ownedLock: WifiManager.MulticastLock? = null
        try {
            ownedLock = runCatching { acquireMulticastLock() }
                .onFailure { Log.w(TAG, "Could not acquire multicast lock", it) }
                .getOrNull()
            val multicast = MulticastSocket(null)
            udp = multicast
            multicast.reuseAddress = true
            multicast.soTimeout = 0
            multicast.bind(InetSocketAddress(DISCOVERY_PORT))
            synchronized(lifecycleLock) {
                if (!running.get()) {
                    runCatching { multicast.close() }
                    return
                }
                socket = multicast
            }
            joinDiscoveryMulticast(multicast)
            val buffer = ByteArray(4096)

            while (running.get()) {
                try {
                    // Block indefinitely when no OBS devices are cached so the
                    // CPU can stay asleep; only poll for expiry while devices exist.
                    multicast.soTimeout = if (hasDevices()) EXPIRY_POLL_MS else 0
                    val packet = DatagramPacket(buffer, buffer.size)
                    multicast.receive(packet)
                    val payload = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
                    val host = packet.address.hostAddress ?: continue
                    val device = ObsDiscoveryProtocol.parseBeacon(payload, host, nowMs()) ?: continue
                    synchronized(devices) {
                        val key = device.instanceId.ifBlank { "${device.host}:${device.port}" }
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
            releaseMulticastLock(ownedLock)
            synchronized(lifecycleLock) {
                if (socket === udp) socket = null
                if (worker === Thread.currentThread()) worker = null
                running.set(false)
                if (pendingRestart && desiredRunning) {
                    pendingRestart = false
                    startLocked()
                }
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

    private fun hasDevices(): Boolean = synchronized(devices) { devices.isNotEmpty() }

    private fun pruneExpired(): Boolean {
        val cutoff = nowMs() - DEVICE_TTL_MS
        synchronized(devices) {
            val before = devices.size
            devices.entries.removeAll { it.value.lastSeenMs < cutoff }
            return before != devices.size
        }
    }

    private fun publishDevices() {
        val gen = synchronized(lifecycleLock) {
            if (!desiredRunning) return
            lastPublishMs = nowMs()
            publishGeneration.get()
        }
        val snapshot = synchronized(devices) {
            devices.values.sortedWith(
                compareBy<DiscoveredObsDevice> { it.displayLabel }
                    .thenBy { it.sourceInstanceId }
            ).toList()
        }
        mainHandler.post {
            if (publishGeneration.get() == gen) {
                onDevicesChanged(snapshot)
            }
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
        private const val EXPIRY_POLL_MS = 1_500
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
