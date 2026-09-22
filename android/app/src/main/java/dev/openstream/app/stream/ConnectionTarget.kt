package dev.openstream.app.stream

import android.net.Uri
import dev.openstream.app.discovery.DiscoveredObsDevice

data class ConnectionTarget(
    val name: String,
    val host: String,
    val port: Int,
    val latencyMs: Int,
    val bitrateMbps: Int? = null,
) {
    fun toSrtCallerUrl(): String {
        return "srt://${bracketForUrl(host)}:$port?mode=caller&latency=$latencyMs"
    }

    companion object {
        const val DEFAULT_NAME = "shin Phone Link"
        const val DEFAULT_HOST = "192.168.1.2"
        const val DEFAULT_PORT = 9100
        const val DEFAULT_LATENCY_MS = 120

        /** Brackets a bare IPv6 literal so it is safe to embed in a URL authority. */
        internal fun bracketForUrl(host: String): String {
            return if (':' in host && !(host.startsWith("[") && host.endsWith("]"))) {
                "[$host]"
            } else {
                host
            }
        }

        fun fromDiscoveredDevice(device: DiscoveredObsDevice): ConnectionTarget {
            return ConnectionTarget(
                name = device.displayLabel,
                host = device.host,
                port = device.port,
                latencyMs = device.latencyMs,
                bitrateMbps = device.bitrateMbps,
            )
        }

        fun fromPairingUri(uri: Uri): ConnectionTarget? {
            if (uri.host != "connect") return null
            val legacy = uri.scheme == "openstream"
            if (!legacy && uri.scheme != "shin") return null
            val host = uri.getQueryParameter("host")?.trim().orEmpty()
            if (host.isBlank()) return null
            val defaultPort = if (legacy) LEGACY_DEFAULT_PORT else DEFAULT_PORT
            val port = uri.getQueryParameter("port")?.toIntOrNull()?.coerceIn(1, 65535) ?: defaultPort
            val latencyMs = uri.getQueryParameter("latency")?.toIntOrNull()?.coerceIn(80, 200) ?: DEFAULT_LATENCY_MS
            val bitrateMbps = uri.getQueryParameter("bitrateMbps")?.toIntOrNull()
                ?.coerceIn(StreamConfig.MIN_BITRATE_MBPS, StreamConfig.MAX_BITRATE_MBPS)
            val defaultName = if (legacy) LEGACY_DEFAULT_NAME else DEFAULT_NAME
            val name = uri.getQueryParameter("name")?.ifBlank { defaultName } ?: defaultName
            return ConnectionTarget(
                name = name,
                host = host,
                port = port,
                latencyMs = latencyMs,
                bitrateMbps = bitrateMbps,
            )
        }

        private const val LEGACY_DEFAULT_NAME = "OpenStream Phone Link"
        private const val LEGACY_DEFAULT_PORT = 9000
    }
}
