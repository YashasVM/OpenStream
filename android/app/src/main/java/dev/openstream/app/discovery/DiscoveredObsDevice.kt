package dev.openstream.app.discovery

data class DiscoveredObsDevice(
    val name: String,
    val host: String,
    val port: Int,
    val latencyMs: Int,
    val bitrateMbps: Int,
    val instanceId: String,
    val lastSeenMs: Long,
    val busy: Boolean,
) {
    val displayEndpoint: String
        get() = "$host:$port"
}
