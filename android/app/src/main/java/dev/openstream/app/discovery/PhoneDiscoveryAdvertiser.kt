package dev.openstream.app.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
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
) {
    private val running = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null
    private val instanceId = UUID.randomUUID().toString()

    fun start() {
        if (running.get()) return
        if (worker?.isAlive == true) return
        if (!running.compareAndSet(false, true)) return
        worker = Thread(::run, "OpenStreamPhoneAdvertiser").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
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
        val socket = runCatching {
            DatagramSocket().apply { broadcast = true }
        }.getOrElse {
            running.set(false)
            if (worker === Thread.currentThread()) worker = null
            return
        }
        try {
            val destinations = listOf(
                InetAddress.getByName("255.255.255.255"),
                InetAddress.getByName(DISCOVERY_MULTICAST_ADDRESS),
            )
            while (running.get()) {
                val bytes = beaconPayload().toByteArray(StandardCharsets.UTF_8)
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
            if (worker === Thread.currentThread()) worker = null
            running.set(false)
        }
    }

    private fun beaconPayload(): String {
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
            .put("controlPort", 9001)
            .put("busy", busyProvider())
            .put("reservedBy", reservedByProvider().orEmpty())
        return "$PREFIX $json"
    }

    private fun localWifiAddress(): String? {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        val ip = wifi.connectionInfo?.ipAddress ?: return null
        if (ip == 0) return null
        return listOf(
            ip and 0xff,
            ip shr 8 and 0xff,
            ip shr 16 and 0xff,
            ip shr 24 and 0xff,
        ).joinToString(".")
    }

    companion object {
        const val DISCOVERY_PORT = 51515
        const val DISCOVERY_MULTICAST_ADDRESS = "239.255.42.99"
        const val PREFIX = "OPENSTREAM_PHONE/1"
        const val TYPE = "dev.openstream.phone"
        private const val STOP_TIMEOUT_MS = 1_000L
    }
}
