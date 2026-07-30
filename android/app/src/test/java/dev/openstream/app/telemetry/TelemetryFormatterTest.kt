package dev.openstream.app.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryFormatterTest {
    @Test
    fun `formats healthy wifi telemetry for compact hud`() {
        val hud = TelemetryFormatter.forHud(sample(wifiRssi = -60))

        assertEquals("BAT 82%", hud.battery)
        assertEquals("NOMINAL 31°C", hud.thermal)
        assertEquals("WI-FI 3/4", hud.network)
        assertFalse(hud.isBatteryLow)
        assertFalse(hud.isThermalWarning)
    }

    @Test
    fun `marks production-impacting conditions`() {
        val hud = TelemetryFormatter.forHud(
            sample(
                batteryPercent = 10,
                thermalStatus = "SEVERE",
                networkType = "OFFLINE",
                wifiRssi = null,
            ),
        )

        assertTrue(hud.isBatteryLow)
        assertTrue(hud.isThermalWarning)
        assertTrue(hud.isNetworkWeak)
    }

    @Test
    fun `maps signal strength at documented boundaries`() {
        assertEquals(4, TelemetryFormatter.wifiSignalLevel(-55))
        assertEquals(3, TelemetryFormatter.wifiSignalLevel(-67))
        assertEquals(2, TelemetryFormatter.wifiSignalLevel(-75))
        assertEquals(1, TelemetryFormatter.wifiSignalLevel(-76))
    }

    @Test
    fun `does not fabricate unavailable battery capacity`() {
        assertEquals("BAT --", TelemetryFormatter.forHud(sample(batteryPercent = -1)).battery)
    }

    private fun sample(
        batteryPercent: Int = 82,
        wifiRssi: Int? = -60,
        thermalStatus: String = "NOMINAL",
        networkType: String = "WI-FI",
    ) = DeviceTelemetry(
        deviceName = "Test phone",
        streamUrl = "srt://test",
        codec = "H.264",
        width = 1920,
        height = 1080,
        fps = 60,
        bitrate = 12_000_000,
        batteryPercent = batteryPercent,
        wifiRssi = wifiRssi,
        temperatureCelsius = 31.5f,
        thermalStatus = thermalStatus,
        networkType = networkType,
        encoderState = "streaming",
    )
}
