package dev.openstream.irl

import java.net.URI
import java.net.URLDecoder
import java.util.UUID

internal data class Pairing(val relay: String, val session: String, val token: String) {
    init {
        val origin = URI(relay)
        require(origin.scheme == "https" && !origin.host.isNullOrBlank() && origin.rawUserInfo == null &&
            origin.rawQuery == null && origin.rawFragment == null && origin.rawPath.orEmpty() in listOf("", "/") &&
            (origin.port == -1 || origin.port in 1..65535)) { "Relay must be an HTTPS origin" }
        require(isUuid(session)) { "Invalid session ID" }
        require(token.matches(Regex("(?:[A-Za-z0-9_-]{43}|[A-Fa-f0-9]{64})"))) { "Invalid publish token" }
    }
    val base: String get() = "${relay.trimEnd('/')}/api/sessions/$session"
    // Pairing values are credentials. Do not expose the data-class-generated representation.
    override fun toString() = "Pairing(redacted)"
    companion object {
        fun isUuid(value: String): Boolean = runCatching {
            UUID.fromString(value).toString().equals(value, ignoreCase = true)
        }.getOrDefault(false)
        fun parse(text: String): Pairing {
            val uri = URI(text.trim())
            require(uri.scheme == "openstreamirl" && uri.host == "pair" && uri.rawFragment == null) { "Paste an OpenStream-IRL pairing link" }
            val fields = uri.rawQuery.orEmpty().split('&').map {
                val parts = it.split('=', limit = 2)
                require(parts.size == 2) { "Invalid pairing link" }
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
            }
            require(fields.map { it.first }.toSet().size == fields.size) { "Duplicate pairing fields" }
            val params = fields.toMap()
            return Pairing(params["relay"] ?: "", params["session"] ?: "", params["token"] ?: "")
        }
    }
}
