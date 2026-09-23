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
        private const val MAX_PAIRING_QUERY_LENGTH = 1024
        private const val MAX_HOST_LENGTH = 253
        private const val MAX_NAME_LENGTH = 128
        private const val MIN_LATENCY_MS = 80
        private const val MAX_LATENCY_MS = 200

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
            val scheme = uri.scheme ?: return null
            if (scheme != "shin" && scheme != "openstream") return null
            if (uri.encodedAuthority != "connect" || uri.path.orEmpty().isNotEmpty() || uri.fragment != null) return null
            if ((uri.encodedQuery?.length ?: 0) > MAX_PAIRING_QUERY_LENGTH) return null

            val host = uri.getQueryParameter("host") ?: return null
            if (!isValidPairingHost(host)) return null

            val rawPort = uri.getQueryParameter("port")
            val port = rawPort?.let { raw ->
                raw.toIntOrNull()?.takeIf { it in 1..65535 }
            } ?: if (rawPort == null) DEFAULT_PORT else return null
            val rawLatency = uri.getQueryParameter("latency")
            val latencyMs = rawLatency?.let { raw ->
                raw.toIntOrNull()?.takeIf { it in MIN_LATENCY_MS..MAX_LATENCY_MS }
            } ?: if (rawLatency == null) DEFAULT_LATENCY_MS else return null
            val bitrateMbps = uri.getQueryParameter("bitrateMbps")?.let { raw ->
                raw.toIntOrNull()?.takeIf {
                    it in StreamConfig.MIN_BITRATE_MBPS..StreamConfig.MAX_BITRATE_MBPS
                } ?: return null
            }
            val rawName = uri.getQueryParameter("name")
            if (rawName != null && (rawName.isBlank() || rawName.length > MAX_NAME_LENGTH)) return null
            val name = rawName?.ifBlank { DEFAULT_NAME } ?: DEFAULT_NAME
            return ConnectionTarget(
                name = name,
                host = host,
                port = port,
                latencyMs = latencyMs,
                bitrateMbps = bitrateMbps,
            )
        }

        private fun isValidPairingHost(host: String): Boolean {
            if (host.isEmpty() || host.length > MAX_HOST_LENGTH) return false
            if (host.any { it.isWhitespace() || it.isISOControl() || it in "/?#" }) return false

            val ipv6 = when {
                host.startsWith('[') && host.endsWith(']') -> host.substring(1, host.length - 1)
                '[' in host || ']' in host -> return false
                ':' in host -> host
                else -> null
            }
            if (ipv6 != null) {
                if (':' !in ipv6) return false
                return try {
                    java.net.URI("http://[$ipv6]/").host != null
                } catch (_: java.net.URISyntaxException) {
                    false
                }
            }

            val labels = host.split('.')
            if (labels.size == 4 && labels.all { label -> label.isNotEmpty() && label.all { it in '0'..'9' } }) {
                return labels.all { label -> label.toIntOrNull()?.let { it in 0..255 } == true }
            }
            return labels.all { label ->
                label.isNotEmpty() && label.length <= 63 &&
                    label.first().isAsciiAlphanumeric() && label.last().isAsciiAlphanumeric() &&
                    label.all { it.isAsciiAlphanumeric() || it == '-' }
            }
        }

        private fun Char.isAsciiAlphanumeric(): Boolean =
            this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
    }
}
