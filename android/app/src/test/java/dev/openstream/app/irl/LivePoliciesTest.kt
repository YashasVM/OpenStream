package dev.openstream.app.irl

import org.junit.Assert.*
import org.junit.Test

class LivePoliciesTest {
    @Test
    fun failureClassifierSeparatesAuthConfigAndTransientFailures() {
        listOf("Authentication failed", "Access DENIED", "NetStream.Publish.BadName", "Invalid stream", "invalid key", "rejected", "HTTP 403", "HTTP 401").forEach {
            assertEquals(it, LiveFailure.AUTH, FailureClassifier.classify(it))
        }
        listOf("endpoint malformed", "Certificate expired", "SSLHandshakeException", "hostname mismatch", "protocol not supported").forEach {
            assertEquals(it, LiveFailure.CONFIG, FailureClassifier.classify(it))
        }
        listOf("", "connection timeout", "Network unreachable", "socket closed", "HTTP 503").forEach {
            assertEquals(it, LiveFailure.TRANSIENT, FailureClassifier.classify(it))
        }
    }

    @Test
    fun reconnectBacksOffOneTwoFourEightFifteenSecondsAndCapsThere() {
        var now = 10_000L
        val policy = ReconnectPolicy(clock = { now }, random = { 0.5 })
        listOf(1000L, 2000L, 4000L, 8000L, 15_000L, 15_000L).forEach { delay ->
            assertEquals(delay, policy.next(LiveFailure.TRANSIENT))
            assertEquals(now + delay, policy.retryAtMs)
            now += delay
        }
    }

    @Test
    fun reconnectJitterStaysWithinTwentyPercentIncludingClampedRandomValues() {
        listOf(-1.0 to 0.8, 0.0 to 0.8, 1.0 to 1.2, 2.0 to 1.2).forEach { (random, multiplier) ->
            val policy = ReconnectPolicy(clock = { 0 }, random = { random })
            listOf(1000L, 2000L, 4000L, 8000L, 15_000L).forEach { base ->
                assertEquals((base * multiplier).toLong(), policy.next(LiveFailure.TRANSIENT))
            }
        }
    }

    @Test
    fun reconnectStopsForPermanentFailuresAndResetsAfterRecovery() {
        var now = 0L
        val policy = ReconnectPolicy(clock = { now }, random = { 0.5 })
        assertNull(policy.next(LiveFailure.AUTH))
        assertNull(policy.next(LiveFailure.CONFIG))
        assertEquals(0L, policy.retryAtMs)
        assertEquals(1000L, policy.next(LiveFailure.TRANSIENT))
        assertEquals(1L, policy.remainingSeconds())
        now = 999
        assertEquals(1L, policy.remainingSeconds())
        now = 1001
        assertEquals(0L, policy.remainingSeconds())
        policy.next(LiveFailure.TRANSIENT)
        policy.reset()
        assertEquals(0L, policy.retryAtMs)
        assertEquals(0L, policy.remainingSeconds())
        assertEquals(1000L, policy.next(LiveFailure.TRANSIENT))
    }

    @Test
    fun bitrateDropsImmediatelyAndAtMostOnceEveryTwoSeconds() {
        var now = 0L
        val policy = AdaptiveBitratePolicy(4_000_000, clock = { now })
        assertEquals(2_800_000, policy.sample(true, 0, 0))
        now = 1999
        assertEquals(2_800_000, policy.sample(true, 0, 0))
        now = 2000
        assertEquals(1_960_000, policy.sample(true, 0, 0))
    }

    @Test
    fun growingQueuesAndDroppedFramesAlsoReduceBitrate() {
        assertEquals(2_800_000, AdaptiveBitratePolicy(4_000_000, clock = { 0 }).sample(false, 6, 0))
        assertEquals(2_800_000, AdaptiveBitratePolicy(4_000_000, clock = { 0 }).sample(false, 0, 1))
        assertEquals(4_000_000, AdaptiveBitratePolicy(4_000_000, clock = { 0 }).sample(false, 5, 0))
    }

    @Test
    fun bitrateRaisesSlowlyAfterFifteenHealthySecondsAndNeverExceedsTarget() {
        var now = 0L
        val policy = AdaptiveBitratePolicy(2_000_000, clock = { now })
        assertEquals(1_400_000, policy.sample(true, 0, 0))
        now = 14_999
        assertEquals(1_400_000, policy.sample(false, 0, 0))
        now = 15_000
        assertEquals(1_500_000, policy.sample(false, 0, 0))
        now = 15_001
        assertEquals(1_500_000, policy.sample(false, 0, 0))
        repeat(10) {
            now += 15_000
            policy.sample(false, 0, 0)
        }
        assertEquals(2_000_000, policy.current)
    }

    @Test
    fun bitrateRaisesByFivePercentWithAMinimumStepOfOneHundredKbps() {
        var now = 0L
        val highTarget = AdaptiveBitratePolicy(4_000_000, clock = { now })
        val lowTarget = AdaptiveBitratePolicy(1_000_000, clock = { now })
        highTarget.sample(true, 0, 0)
        lowTarget.sample(true, 0, 0)
        now = 15_000
        assertEquals(3_000_000, highTarget.sample(false, 0, 0))
        assertEquals(800_000, lowTarget.sample(false, 0, 0))
    }

    @Test
    fun congestionRestartsTheHealthyIntervalAndQueuedFramesBlockRaises() {
        var now = 0L
        val policy = AdaptiveBitratePolicy(2_000_000, clock = { now })
        policy.sample(true, 6, 0)
        now = 15_000
        assertEquals(1_400_000, policy.sample(false, 6, 0))
        assertEquals(980_000, policy.sample(false, 6, 1))
        now = 29_999
        assertEquals(980_000, policy.sample(false, 0, 0))
        now = 30_000
        assertEquals(1_080_000, policy.sample(false, 0, 0))
    }

    @Test
    fun bitrateRespectsTheMinimumAndDoesNotRaiseLowTargetsToTheFloor() {
        var now = 0L
        val policy = AdaptiveBitratePolicy(2_000_000, clock = { now })
        repeat(30) {
            policy.sample(true, 0, 0)
            now += 2000
        }
        assertEquals(600_000, policy.current)
        val lowTarget = AdaptiveBitratePolicy(300_000, clock = { now })
        assertEquals(300_000, lowTarget.sample(true, 0, 0))
        now += 15_000
        assertEquals(300_000, lowTarget.sample(false, 0, 0))
    }
}
