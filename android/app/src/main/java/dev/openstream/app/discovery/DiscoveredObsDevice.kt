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
    val displayEndpoint: String
        get() = "$host:$port"

    val displayLabel: String
        get() = slotLabel.ifBlank { name }

    val availabilityLabel: String
        get() = if (busy) "Busy" else "Available"
}
