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
    fun hostValidationAcceptsUnbracketedIpv6ButRejectsGarbage() {
        assertTrue(SettingsValidator.isValidHost("2001:db8::1", required = true))
        assertTrue(SettingsValidator.isValidHost("::1", required = true))
        assertTrue(SettingsValidator.isValidHost("::", required = true))
        assertTrue(SettingsValidator.isValidHost("fe80::1", required = true))
        assertTrue(SettingsValidator.isValidHost("2001:0db8:0000:0000:0000:ff00:0042:8329", required = true))
        assertTrue(SettingsValidator.isValidHost("::ffff:192.168.1.20", required = true))
        assertFalse(SettingsValidator.isValidHost("gggg::1", required = true))
        assertFalse(SettingsValidator.isValidHost("2001:db8::1 ", required = true))
        assertFalse(SettingsValidator.isValidHost("2001:db8::1/64", required = true))
        assertFalse(SettingsValidator.isValidHost("2001:db8::1:9000?", required = true))
        assertFalse(SettingsValidator.isValidHost("bad host::1", required = true))
        assertFalse(SettingsValidator.isValidHost("12345::", required = true))
    }

    @Test
    fun normalizeHostBracketsBareIpv6Only() {
        assertEquals("[2001:db8::1]", SettingsValidator.normalizeHost("2001:db8::1"))
        assertEquals("[::1]", SettingsValidator.normalizeHost("::1"))
        assertEquals("[2001:db8::1]", SettingsValidator.normalizeHost("[2001:db8::1]"))
        assertEquals("192.168.1.20", SettingsValidator.normalizeHost("192.168.1.20"))
        assertEquals("obs.local", SettingsValidator.normalizeHost("obs.local"))
        assertEquals("obs.local:9000", SettingsValidator.normalizeHost("obs.local:9000"))
    }

    @Test
    fun numericValidationUsesDefaultsAndRejectsOutOfRangeValues() {
        assertEquals(9000, SettingsValidator.parseNumber("", 9000, 1..65535))
        assertEquals(120, SettingsValidator.parseNumber("120", 9000, 80..200))
        assertNull(SettingsValidator.parseNumber("79", 120, 80..200))
        assertNull(SettingsValidator.parseNumber("invalid", 120, 80..200))
    }
}
