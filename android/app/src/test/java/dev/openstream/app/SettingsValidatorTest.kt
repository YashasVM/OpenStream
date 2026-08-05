package dev.openstream.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.openstream.app.stream.TransportMode

class SettingsValidatorTest {
    @Test
    fun hostValidationAllowsSavedEmptyHostButRequiresOneForManualConnect() {
        assertTrue(SettingsValidator.isValidHost("", required = false))
        assertFalse(SettingsValidator.isValidHost("", required = true))
        assertTrue(SettingsValidator.isValidHost("192.168.1.20", required = true))
        assertTrue(SettingsValidator.isValidHost("obs.local", required = true))
        assertTrue(SettingsValidator.isValidHost("[2001:db8::1]", required = true))
        assertFalse(SettingsValidator.isValidHost("srt://192.168.1.20", required = true))
        assertFalse(SettingsValidator.isValidHost("bad host", required = true))
        assertFalse(SettingsValidator.isValidHost("obs.local/path", required = true))
        assertFalse(SettingsValidator.isValidHost("obs.local?x=1", required = true))
        assertFalse(SettingsValidator.isValidHost("obs.local#fragment", required = true))
        assertFalse(SettingsValidator.isValidHost("obs.local:9000", required = true))
        assertFalse(SettingsValidator.isValidHost("999.1.1.1", required = true))
    }

    @Test
    fun numericValidationUsesDefaultsAndRejectsOutOfRangeValues() {
        assertEquals(9000, SettingsValidator.parseNumber("", 9000, 1..65535))
        assertEquals(120, SettingsValidator.parseNumber("120", 9000, 80..200))
        assertNull(SettingsValidator.parseNumber("79", 120, 80..200))
        assertNull(SettingsValidator.parseNumber("invalid", 120, 80..200))
    }

    @Test
    fun usbTransportUsesFixedLowLatencyWhileWifiRemainsBounded() {
        assertEquals(30, TransportMode.UsbTether.latencyMs(120))
        assertEquals(80, TransportMode.Wifi.latencyMs(20))
        assertEquals(200, TransportMode.Wifi.latencyMs(500))
        assertEquals(TransportMode.UsbTether, TransportMode.fromPreference("usb_adb"))
        assertEquals(TransportMode.Wifi, TransportMode.fromPreference("unknown"))
    }
}
