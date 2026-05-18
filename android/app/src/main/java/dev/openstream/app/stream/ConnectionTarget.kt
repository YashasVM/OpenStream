package dev.openstream.app.stream

import android.net.Uri
import dev.openstream.app.discovery.DiscoveredObsDevice

data class ConnectionTarget(
    val name: String,
    val host: String,
    val port: Int,
    val latencyMs: Int,
) {
    fun toSrtCallerUrl(): String = "srt://$host:$port?mode=caller&latency=$latencyMs"

    companion object {
        const val DEFAULT_NAME = "OpenStream Phone Link"
        const val DEFAULT_HOST = "192.168.1.2"
        const val DEFAULT_PORT = 9000
        const val DEFAULT_LATENCY_MS = 120

        fun fromDiscoveredDevice(device: DiscoveredObsDevice): ConnectionTarget {
            return ConnectionTarget(
                name = device.name,
                host = device.host,
                port = device.port,
                latencyMs = device.latencyMs,
            )
        }

        fun fromPairingUri(uri: Uri): ConnectionTarget? {
            if (uri.scheme != "openstream" || uri.host != "connect") return null
            val host = uri.getQueryParameter("host")?.trim().orEmpty()
            if (host.isBlank()) return null
            val port = uri.getQueryParameter("port")?.toIntOrNull()?.coerceIn(1, 65535) ?: DEFAULT_PORT
            val latencyMs = uri.getQueryParameter("latency")?.toIntOrNull()?.coerceIn(80, 200) ?: DEFAULT_LATENCY_MS
            val name = uri.getQueryParameter("name")?.ifBlank { DEFAULT_NAME } ?: DEFAULT_NAME
            return ConnectionTarget(
                name = name,
                host = host,
                port = port,
                latencyMs = latencyMs,
            )
        }
    }
}
