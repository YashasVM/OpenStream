package dev.openstream.app.stream

enum class TransportMode(val preferenceValue: String, val displayName: String) {
    Wifi("wifi", "Wi-Fi"),
    UsbTether("usb", "USB tethering");

    fun latencyMs(wifiLatencyMs: Int): Int = when (this) {
        Wifi -> wifiLatencyMs.coerceIn(WIFI_MIN_LATENCY_MS, WIFI_MAX_LATENCY_MS)
        UsbTether -> USB_LATENCY_MS
    }

    companion object {
        const val USB_LATENCY_MS = 30
        const val WIFI_MIN_LATENCY_MS = 80
        const val WIFI_MAX_LATENCY_MS = 200

        fun fromPreference(value: String?): TransportMode =
            entries.firstOrNull { it.preferenceValue == value }
                ?: if (value == "usb_adb") UsbTether else Wifi
    }
}
