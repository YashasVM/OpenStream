package dev.openstream.app.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

data class DeviceTelemetry(
    val deviceName: String,
    val streamUrl: String,
    val codec: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val batteryPercent: Int,
    val wifiRssi: Int?,
    val temperatureCelsius: Float?,
    val thermalStatus: String,
    val networkType: String,
    val encoderState: String,
)

class TelemetrySampler(private val context: Context) {
    fun sample(
        streamUrl: String,
        codec: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
    ): DeviceTelemetry {
        val battery = context.getSystemService(BatteryManager::class.java)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val batteryStatus = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val batteryPercent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 }
            ?: -1
        val activeCapabilities = connectivity.activeNetwork
            ?.let(connectivity::getNetworkCapabilities)
        return DeviceTelemetry(
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
            streamUrl = streamUrl,
            codec = codec,
            width = width,
            height = height,
            fps = fps,
            bitrate = bitrate,
            batteryPercent = batteryPercent,
            wifiRssi = if (activeCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                activeCapabilities.signalStrength.takeUnless {
                    it == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED
                }
            } else {
                null
            },
            temperatureCelsius = batteryStatus
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeUnless { it == Int.MIN_VALUE }
                ?.div(10f),
            thermalStatus = thermalStatusLabel(power.currentThermalStatus),
            networkType = networkType(activeCapabilities),
            encoderState = "streaming",
        )
    }

    private fun networkType(capabilities: NetworkCapabilities?): String = when {
        capabilities == null -> "OFFLINE"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WI-FI"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELL"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "NETWORK"
    }

    private fun thermalStatusLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "NOMINAL"
        PowerManager.THERMAL_STATUS_LIGHT -> "WARM"
        PowerManager.THERMAL_STATUS_MODERATE -> "HOT"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN"
    }
}
