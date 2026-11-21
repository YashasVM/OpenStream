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

