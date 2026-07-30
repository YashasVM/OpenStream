package dev.openstream.app.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

data class AudioLevel(
    val rmsDbfs: Float,
    val peakDbfs: Float,
) {
    companion object {
        val Silent = AudioLevel(MIN_DBFS, MIN_DBFS)
        const val MIN_DBFS = -60f
    }
}

object Pcm16AudioLevel {
    fun measure(pcm: ByteArray, length: Int): AudioLevel {
        val usableLength = length.coerceIn(0, pcm.size) and -2
        if (usableLength < 2) return AudioLevel.Silent

        var sumSquares = 0.0
        var peak = 0
        var sampleCount = 0
        var index = 0
        while (index < usableLength) {
            val sample = (pcm[index].toInt() and 0xff) or (pcm[index + 1].toInt() shl 8)
            val signedSample = sample.toShort().toInt()
            val magnitude = kotlin.math.abs(signedSample)
            peak = max(peak, magnitude)
            val normalized = signedSample / PCM16_FULL_SCALE
            sumSquares += normalized * normalized
            sampleCount++
            index += 2
        }
        if (sampleCount == 0) return AudioLevel.Silent
        return AudioLevel(
            rmsDbfs = toDbfs(sqrt(sumSquares / sampleCount)),
            peakDbfs = toDbfs(peak / PCM16_FULL_SCALE),
        )
    }

    private fun toDbfs(amplitude: Double): Float = if (amplitude <= 0.0) {
        AudioLevel.MIN_DBFS
    } else {
        (20.0 * log10(amplitude)).toFloat().coerceIn(AudioLevel.MIN_DBFS, 0f)
    }

    private const val PCM16_FULL_SCALE = 32768.0
}
