package dev.openstream.app

/** Pure settings validation shared by the UI and local unit tests. */
object SettingsValidator {
    fun isValidHost(host: String, required: Boolean): Boolean {
        if (host.isBlank()) return !required
        return host.none { it.isWhitespace() } && "://" !in host
    }

    fun parseNumber(raw: String, defaultValue: Int, validRange: IntRange): Int? {
        if (raw.isBlank()) return defaultValue
        return raw.toIntOrNull()?.takeIf { it in validRange }
    }
}
