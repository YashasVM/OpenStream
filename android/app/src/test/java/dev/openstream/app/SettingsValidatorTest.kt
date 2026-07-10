package dev.openstream.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsValidatorTest {
    @Test
    fun hostValidationAllowsSavedEmptyHostButRequiresOneForManualConnect() {
        assertTrue(SettingsValidator.isValidHost("", required = false))
        assertFalse(SettingsValidator.isValidHost("", required = true))
        assertTrue(SettingsValidator.isValidHost("192.168.1.20", required = true))
        assertFalse(SettingsValidator.isValidHost("srt://192.168.1.20", required = true))
        assertFalse(SettingsValidator.isValidHost("bad host", required = true))
    }

    @Test
    fun numericValidationUsesDefaultsAndRejectsOutOfRangeValues() {
        assertEquals(9000, SettingsValidator.parseNumber("", 9000, 1..65535))
        assertEquals(120, SettingsValidator.parseNumber("120", 9000, 80..200))
        assertNull(SettingsValidator.parseNumber("79", 120, 80..200))
        assertNull(SettingsValidator.parseNumber("invalid", 120, 80..200))
    }
}
