package dev.openstream.app.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.text.format.Formatter
import android.util.Log
import dev.openstream.app.control.CameraControlServer
import dev.openstream.app.encoder.advertisedMimeType
import dev.openstream.app.stream.StreamConfig
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class PhoneDiscoveryAdvertiser(
    private val context: Context,
    private val config: StreamConfig,
    private val port: Int,
    private val busyProvider: () -> Boolean,
    private val reservedByProvider: () -> String? = { null },
    private val selectedObsHostProvider: () -> String? = { null },
) {
    private val running = AtomicBoolean(false)
    @Volatile private var pendingRestart = false
    @Volatile private var worker: Thread? = null

    fun start() {
        if (running.get()) return
        if (worker?.isAlive == true) {
            pendingRestart = true
            return
        }
        if (!running.compareAndSet(false, true)) return
        pendingRestart = false
        worker = Thread(::run, "shinPhoneAdvertiser").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        pendingRestart = false
        running.set(false)
        val thread = worker
        thread?.interrupt()
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(STOP_TIMEOUT_MS) }
                .onFailure { Thread.currentThread().interrupt() }
        }
        if (worker === thread && thread?.isAlive != true) worker = null
    }

    private fun run() {
        val instanceId = runCatching {
            val preferences = context.applicationContext.getSharedPreferences(
                INSTANCE_PREFERENCES,
                Context.MODE_PRIVATE,
            )
            loadOrCreateInstanceId(
                existingId = preferences.getString(INSTANCE_ID_KEY, null),
                persist = { id -> preferences.edit().putString(INSTANCE_ID_KEY, id).commit() },
                create = { UUID.randomUUID().toString() },
            )
        }.getOrElse { error ->
            Log.e(TAG, "Could not load persistent phone discovery identity", error)
            finishWorkerAndRestart()
            return
        }
        val socket = runCatching {
            DatagramSocket().apply { broadcast = true }
        }.getOrElse {
            finishWorkerAndRestart()
            return
        }
        try {
            while (running.get()) {
                val bytes = beaconPayload(instanceId).toByteArray(StandardCharsets.UTF_8)
                val destinations = linkedSetOf(
                    InetAddress.getByName("255.255.255.255"),
                    InetAddress.getByName(DISCOVERY_MULTICAST_ADDRESS),
                )
                selectedObsHostProvider()?.trim()?.takeIf { it.isNotEmpty() }?.let { host ->
                    runCatching { destinations += InetAddress.getByName(host) }
                }
                destinations.forEach { destination ->
                    runCatching {
                        socket.send(DatagramPacket(bytes, bytes.size, destination, DISCOVERY_PORT))
                    }
                }
                try {
                    Thread.sleep(1_000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        } finally {
            socket.close()
            finishWorkerAndRestart()
        }
    }

    private fun finishWorkerAndRestart() {
        if (worker === Thread.currentThread()) worker = null
        running.set(false)
        if (pendingRestart) {
            pendingRestart = false
            start()
        }
    }

    private fun beaconPayload(instanceId: String): String {
        val json = JSONObject()
            .put("type", TYPE)
            .put("version", 1)
            .put("name", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("instanceId", instanceId)
            .put("host", localWifiAddress().orEmpty())
            .put("listenerPort", port)
            .put("latencyMs", config.latencyMs)
            .put("bitrateMbps", config.bitrateMbps)
            .put("codec", config.codecPreference.advertisedMimeType())
            .put("width", config.width)
            .put("height", config.height)
            .put("fps", config.fps)
            .put("controlPort", CameraControlServer.CONTROL_PORT)
            .put("busy", busyProvider())
            .put("reservedBy", reservedByProvider().orEmpty())
        return "$PREFIX $json"
    }

    private fun localWifiAddress(): String? {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        val ip = wifi.connectionInfo?.ipAddress ?: return null
        if (ip == 0) return null
        // WifiInfo reports little-endian; Formatter applies the same LSB-first order.
        return Formatter.formatIpAddress(ip)
    }

    companion object {
        private const val TAG = "shinPhoneAdvertiser"
        private const val INSTANCE_PREFERENCES = "shin_phone_discovery"
        private const val INSTANCE_ID_KEY = "phone_instance_id"
        const val DISCOVERY_PORT = 51615
        const val DISCOVERY_MULTICAST_ADDRESS = "239.255.43.99"
        const val PREFIX = "SHIN_PHONE/1"
        const val TYPE = "dev.shin.phone"
        private const val STOP_TIMEOUT_MS = 1_000L
    }
}
