package dev.openstream.app.stream

data class ConnectionTarget(
    val host: String,
    val port: Int,
    val latencyMs: Int,
) {
    fun toSrtCallerUrl(): String = "srt://$host:$port?mode=caller&latency=$latencyMs"

    companion object {
        const val DEFAULT_HOST = "192.168.1.2"
        const val DEFAULT_PORT = 9000
        const val DEFAULT_LATENCY_MS = 120
    }
}
