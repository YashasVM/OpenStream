package dev.openstream.app.stream

/** Cumulative SRT counters, sampled without clearing the native statistics. */
data class SrtTelemetry(
    val sessionGeneration: Long,
    val sentPackets: Long,
    val lostPackets: Long,
    val retransmittedPackets: Long,
    val rttMs: Double,
    val sendBufferMs: Double,
    val queuedBytes: Long,
    val sendBufferPackets: Int,
)

/** Rate changes preserve the codec surface, timestamps, audio and transport session. */
class AdaptiveBitrateController(
    private val maxBitrate: Int,
    private val minBitrate: Int = maxOf(250_000, maxBitrate / 4),
) {
    init { require(minBitrate > 0 && maxBitrate >= minBitrate) }
    var currentBitrate: Int = maxBitrate
        private set
    private var previous: SrtTelemetry? = null
    private var lastSampleMs = Long.MIN_VALUE
    private var lastChangeMs = Long.MIN_VALUE
    private var badSamples = 0
    private var goodSamples = 0
    private var baselineRtt = Double.POSITIVE_INFINITY

    fun update(sample: SrtTelemetry, nowMs: Long): Int? {
        if (!sample.rttMs.isFinite() || sample.rttMs < 0 ||
            !sample.sendBufferMs.isFinite() || sample.sendBufferMs < 0 ||
            sample.sentPackets < 0 || sample.lostPackets < 0 ||
            sample.retransmittedPackets < 0 || sample.queuedBytes < 0
        ) return null
        val old = previous
        if (old == null || old.sessionGeneration != sample.sessionGeneration ||
            sample.sentPackets < old.sentPackets || sample.lostPackets < old.lostPackets ||
            sample.retransmittedPackets < old.retransmittedPackets
        ) {
            previous = sample
            lastSampleMs = nowMs
            badSamples = 0
            goodSamples = 0
            baselineRtt = sample.rttMs.takeIf { it > 0 } ?: Double.POSITIVE_INFINITY
            return null
        }
        if (nowMs <= lastSampleMs || nowMs - lastSampleMs < 800) return null
        previous = sample
        lastSampleMs = nowMs
        if (sample.rttMs > 0) baselineRtt = minOf(baselineRtt, sample.rttMs)
        val sent = sample.sentPackets - old.sentPackets
        val loss = (sample.lostPackets - old.lostPackets).toDouble() / maxOf(1L, sent)
        val retransmits = (sample.retransmittedPackets - old.retransmittedPackets).toDouble() / maxOf(1L, sent)
        val congested = loss >= 0.02 || retransmits >= 0.04 || sample.sendBufferMs >= 120 ||
            sample.queuedBytes >= currentBitrate / 8 / 5 ||
            sample.rttMs > maxOf(120.0, baselineRtt * 2.5)
        val healthy = sent > 0 && loss < 0.005 && retransmits < 0.01 && sample.sendBufferMs < 60 &&
            sample.queuedBytes < currentBitrate / 8 / 10 &&
            sample.rttMs <= maxOf(80.0, baselineRtt * 1.5)
        badSamples = if (congested) badSamples + 1 else 0
        goodSamples = if (healthy) goodSamples + 1 else 0
        if (lastChangeMs != Long.MIN_VALUE && nowMs - lastChangeMs < 3000) return null
        val next = when {
            badSamples >= 2 -> maxOf(minBitrate, (currentBitrate.toLong() * 3 / 4).toInt())
            goodSamples >= 8 -> minOf(maxBitrate.toLong(), currentBitrate.toLong() + maxOf(100_000, currentBitrate / 12)).toInt()
            else -> currentBitrate
        }
        if (next == currentBitrate) return null
        currentBitrate = next
        lastChangeMs = nowMs
        badSamples = 0
        goodSamples = 0
        return next
    }
}
