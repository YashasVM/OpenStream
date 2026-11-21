package dev.openstream.app.stream

/**
 * Thermal mitigation policy for sustained phone camera streaming.
 *
 * Numeric levels match [android.os.PowerManager] `THERMAL_STATUS_*` constants
 * (API 29+) so the policy can be evaluated and unit-tested without Android
 * framework stubs.
 */
data class ThermalMitigation(
    val targetBitrateBps: Int,
    val bitrateScale: Float,
    val autoDimDisplay: Boolean,
    val statusLabel: String?,
) {
    val isThrottled: Boolean
        get() = bitrateScale < 1.0f || autoDimDisplay
}

object ThermalPolicy {
    const val STATUS_NONE = 0
    const val STATUS_LIGHT = 1
    const val STATUS_MODERATE = 2
    const val STATUS_SEVERE = 3
    const val STATUS_CRITICAL = 4

    const val MIN_THERMAL_BITRATE_BPS = 4_000_000

    fun evaluate(thermalStatus: Int, baseBitrateBps: Int): ThermalMitigation {
        val safeBase = baseBitrateBps.coerceAtLeast(1)
        val (scale, autoDim, label) = when {
            thermalStatus >= STATUS_CRITICAL -> Triple(0.35f, true, "Thermal limit")
            thermalStatus >= STATUS_SEVERE -> Triple(0.50f, true, "Thermal saver")
            thermalStatus >= STATUS_MODERATE -> Triple(0.75f, false, "Thermal saver")
            else -> Triple(1.0f, false, null)
        }
        val floor = minOf(MIN_THERMAL_BITRATE_BPS, safeBase)
        val scaled = if (scale >= 1.0f) {
            safeBase
        } else {
            (safeBase.toDouble() * scale.toDouble()).toInt().coerceIn(floor, safeBase)
        }
        return ThermalMitigation(
            targetBitrateBps = scaled,
            bitrateScale = scale,
            autoDimDisplay = autoDim,
            statusLabel = label,
        )
    }
}
