package dev.openstream.app.irl

import kotlin.math.pow
import kotlin.random.Random

enum class LiveFailure { TRANSIENT, AUTH, CONFIG }

object FailureClassifier {
    fun classify(reason: String): LiveFailure {
        val lower = reason.lowercase()
        return when {
            listOf("auth", "denied", "badname", "invalid stream", "invalid key", "rejected", "403", "401").any { it in lower } -> LiveFailure.AUTH
            listOf("endpoint malformed", "certificate", "sslhandshake", "hostname", "not supported").any { it in lower } -> LiveFailure.CONFIG
            else -> LiveFailure.TRANSIENT
        }
    }
}

class ReconnectPolicy(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: () -> Double = { Random.nextDouble() },
) {
    private var attempts = 0
    var retryAtMs: Long = 0
        private set

    fun next(failure: LiveFailure): Long? {
        if (failure != LiveFailure.TRANSIENT) return null
        val base = minOf(15_000.0, 1000.0 * 2.0.pow(attempts.coerceAtMost(4))).toLong()
        attempts++
        val delay = (base * (0.8 + random().coerceIn(0.0, 1.0) * 0.4)).toLong()
        retryAtMs = clock() + delay
        return delay
    }

    fun reset() { attempts = 0; retryAtMs = 0 }
    fun remainingSeconds(): Long = ((retryAtMs - clock()).coerceAtLeast(0) + 999) / 1000
}

class AdaptiveBitratePolicy(val target: Int, private val clock: () -> Long = System::currentTimeMillis) {
    var current: Int = target
        private set
    private val minimum = minOf(600_000, target)
    private var healthySince = clock()
    private var lastDrop = Long.MIN_VALUE / 2
    private var previousQueue = 0

    fun sample(congested: Boolean, queuedFrames: Int, newDrops: Long): Int {
        val now = clock()
        val growing = queuedFrames > previousQueue + 5
        previousQueue = queuedFrames
        if (congested || growing || newDrops > 0) {
            healthySince = now
            if (now - lastDrop >= 2000) {
                current = (current * 7L / 10).toInt().coerceAtLeast(minimum)
                lastDrop = now
            }
        } else if (now - healthySince >= 15_000 && queuedFrames <= 5) {
            current = (current + maxOf(100_000, target / 20)).coerceAtMost(target)
            healthySince = now
        }
        return current
    }
}
