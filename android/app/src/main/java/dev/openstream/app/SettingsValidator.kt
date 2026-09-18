package dev.openstream.app

/** Pure settings validation shared by the UI and local unit tests. */
object SettingsValidator {
    private val hostname = Regex("^(?=.{1,253}\\.?$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)*[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.?$")
    private val ipv4 = Regex("^(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")
    private val bracketedIpv6 = Regex("^\\[[0-9A-Fa-f:.]+]$")

    fun isValidHost(host: String, required: Boolean): Boolean {
        if (host.isBlank()) return !required
        if (host.all { it.isDigit() || it == '.' }) return ipv4.matches(host)
        if (isBareIpv6Literal(host)) return true
        return hostname.matches(host) || (bracketedIpv6.matches(host) && ':' in host)
    }

    private fun isBareIpv6Literal(host: String): Boolean {
        if (':' !in host) return false
        // A single colon denotes host:port, never a bare IPv6 literal.
        if (host.count { it == ':' } < 2) return false
        if (":::" in host) return false
        if (!host.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
            return false
        }
        if (host.windowed(2).count { it == "::" } > 1) return false
        val groups = host.split(':')
        // An embedded IPv4 tail occupies two 16-bit groups.
        val effectiveGroups = groups.count { it.isNotEmpty() } +
            if (groups.any { '.' in it }) 1 else 0
        // Only one "::" compression run is legal; ":::" never is.
        if ("::" in host) {
            if (effectiveGroups > 7) return false
        } else {
            if (groups.any { it.isEmpty() } || effectiveGroups != 8) return false
        }
        return groups.withIndex().all { (index, group) ->
            when {
                group.isEmpty() -> true
                // An embedded IPv4 tail is only legal as the final group.
                '.' in group -> index == groups.lastIndex && ipv4.matches(group)
                else -> group.length <= 4
            }
        }
    }

    fun parseNumber(raw: String, defaultValue: Int, validRange: IntRange): Int? {
        if (raw.isBlank()) return defaultValue
        return raw.toIntOrNull()?.takeIf { it in validRange }
    }
}
