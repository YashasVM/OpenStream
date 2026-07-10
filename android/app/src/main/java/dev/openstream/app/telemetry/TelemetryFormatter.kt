package dev.openstream.app.telemetry

data class HudTelemetry(
    val battery: String,
    val thermal: String,
    val network: String,
    val isBatteryLow: Boolean,
    val isThermalWarning: Boolean,
    val isNetworkWeak: Boolean,
)

object TelemetryFormatter {
    fun forHud(telemetry: DeviceTelemetry): HudTelemetry {
        val signalLevel = telemetry.wifiRssi?.let(::wifiSignalLevel)
        return HudTelemetry(
            battery = telemetry.batteryPercent.takeIf { it in 0..100 }
                ?.let { "BAT $it%" }
                ?: "BAT --",
            thermal = telemetry.temperatureCelsius?.let {
                "${telemetry.thermalStatus} ${it.toInt()}°C"
            } ?: telemetry.thermalStatus,
            network = when {
                telemetry.networkType == "WI-FI" && signalLevel != null -> "WI-FI $signalLevel/4"
                else -> telemetry.networkType
            },
            isBatteryLow = telemetry.batteryPercent in 0..15,
            isThermalWarning = telemetry.thermalStatus in setOf(
                "HOT", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN",
            ),
            isNetworkWeak = telemetry.networkType == "OFFLINE" || signalLevel == 1,
        )
    }

    /** Stable four-step RSSI mapping; avoids framework-version differences in calculateSignalLevel. */
    fun wifiSignalLevel(rssi: Int): Int = when {
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -75 -> 2
        else -> 1
    }
}
