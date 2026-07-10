package dev.openstream.app

/** Pure settings validation shared by the UI and local unit tests. */
object SettingsValidator {
    private val hostname = Regex("^(?=.{1,253}\\.?$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)*[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.?$")
    private val ipv4 = Regex("^(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")
    private val bracketedIpv6 = Regex("^\\[[0-9A-Fa-f:.]+]$")

    fun isValidHost(host: String, required: Boolean): Boolean {
        if (host.isBlank()) return !required
        if (host.all { it.isDigit() || it == '.' }) return ipv4.matches(host)
        return hostname.matches(host) || (bracketedIpv6.matches(host) && ':' in host)
    }

    fun parseNumber(raw: String, defaultValue: Int, validRange: IntRange): Int? {
        if (raw.isBlank()) return defaultValue
        return raw.toIntOrNull()?.takeIf { it in validRange }
    }
}
