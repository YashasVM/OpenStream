package dev.openstream.app.telemetry

import android.content.Context
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build

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
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        return DeviceTelemetry(
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
            streamUrl = streamUrl,
            codec = codec,
            width = width,
            height = height,
            fps = fps,
            bitrate = bitrate,
            batteryPercent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            wifiRssi = wifi.connectionInfo?.rssi,
            temperatureCelsius = null,
            encoderState = "streaming",
        )
    }
}
