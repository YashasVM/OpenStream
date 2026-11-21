package dev.openstream.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalPolicyTest {
    @Test
    fun normalAndLightThermalStatesKeepFullBitrateAndDisplay() {
        val none = ThermalPolicy.evaluate(ThermalPolicy.STATUS_NONE, 12_000_000)
        val light = ThermalPolicy.evaluate(ThermalPolicy.STATUS_LIGHT, 12_000_000)

        for (mitigation in listOf(none, light)) {
            assertEquals(12_000_000, mitigation.targetBitrateBps)
            assertEquals(1.0f, mitigation.bitrateScale, 0.001f)
            assertFalse(mitigation.autoDimDisplay)
            assertFalse(mitigation.isThrottled)
            assertNull(mitigation.statusLabel)
        }
    }

    @Test
    fun moderateThermalStateStepsDownBitrateWithoutForcingScreenDim() {
        val mitigation = ThermalPolicy.evaluate(ThermalPolicy.STATUS_MODERATE, 12_000_000)

        assertEquals(9_000_000, mitigation.targetBitrateBps)
        assertEquals(0.75f, mitigation.bitrateScale, 0.001f)
        assertFalse(mitigation.autoDimDisplay)
        assertTrue(mitigation.isThrottled)
        assertEquals("Thermal saver", mitigation.statusLabel)
    }

    @Test
    fun severeAndCriticalStatesStepDownMonotonicallyAndAutoDimDisplay() {
        val moderate = ThermalPolicy.evaluate(ThermalPolicy.STATUS_MODERATE, 25_000_000)
        val severe = ThermalPolicy.evaluate(ThermalPolicy.STATUS_SEVERE, 25_000_000)
        val critical = ThermalPolicy.evaluate(ThermalPolicy.STATUS_CRITICAL, 25_000_000)

        assertTrue(moderate.targetBitrateBps > severe.targetBitrateBps)
        assertTrue(severe.targetBitrateBps > critical.targetBitrateBps)
        assertTrue(severe.autoDimDisplay)
        assertTrue(critical.autoDimDisplay)
        assertEquals("Thermal limit", critical.statusLabel)
    }

    @Test
    fun thermalBitrateRespectsFloorAndNeverExceedsBaseBitrate() {
        val lowBase = ThermalPolicy.evaluate(ThermalPolicy.STATUS_CRITICAL, 8_000_000)
        assertEquals(ThermalPolicy.MIN_THERMAL_BITRATE_BPS, lowBase.targetBitrateBps)

        val tinyBase = ThermalPolicy.evaluate(ThermalPolicy.STATUS_CRITICAL, 2_000_000)
        assertEquals(2_000_000, tinyBase.targetBitrateBps)
    }
}
