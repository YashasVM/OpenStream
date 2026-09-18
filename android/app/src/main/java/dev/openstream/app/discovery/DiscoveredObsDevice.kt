package dev.openstream.app.discovery

data class DiscoveredObsDevice(
    val name: String,
    val host: String,
    val port: Int,
    val latencyMs: Int,
    val bitrateMbps: Int,
    val instanceId: String,
    val sourceInstanceId: String,
    val slotId: String,
    val slotLabel: String,
    val pairingUrl: String,
    val lastSeenMs: Long,
    val busy: Boolean,
) {
    /**
     * Solo-camera display label: prefer the OBS computer name. Legacy
     * production slot fields ([slotLabel]/[slotId]/[sourceInstanceId]) are
     * preserved untouched for protocol compatibility with multi-slot OBS
     * beacons; they are used as diff keys and reservation identity, not as
     * the primary user-facing picker text.
     */
    val displayLabel: String
        get() = name.ifBlank { slotLabel.ifBlank { "$host:$port" } }
}
