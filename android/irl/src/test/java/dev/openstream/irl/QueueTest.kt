package dev.openstream.irl

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class QueueTest {
    private val pairing = Pairing("https://relay.example", "123e4567-e89b-12d3-a456-426614174000", "a".repeat(43))
    private val runId = "223e4567-e89b-12d3-a456-426614174000"
    @Test fun pairingRejectsUnsafeOriginsAndAmbiguousLinks() {
        listOf("http://relay.example", "https://user@relay.example", "https://relay.example/path",
            "https://relay.example?token=x", "https://relay.example#x").forEach { origin ->
            assertThrows(IllegalArgumentException::class.java) { Pairing(origin, pairing.session, pairing.token) }
        }
        val link = "openstreamirl://pair?relay=https%3A%2F%2Frelay.example&session=${pairing.session}&token=${pairing.token}"
        assertEquals(pairing, Pairing.parse(link))
        assertThrows(IllegalArgumentException::class.java) { Pairing.parse("$link&token=${pairing.token}") }
        assertFalse(pairing.toString().contains(pairing.token))
    }
    @Test fun queueSurvivesRestartAndOnlyDeletesAcknowledgedSegments() {
        val dir = Files.createTempDirectory("irl-test").toFile()
        try {
            var queue = DiskQueue(dir)
            queue.begin(pairing, runId)
            queue.append(byteArrayOf(0x47, 1), 1000, 1234)
            queue.append(byteArrayOf(0x47, 2), 1000, 2234)
            queue = DiskQueue(dir)
            assertEquals(0L, queue.head()!!.sequence)
            assertArrayEquals(byteArrayOf(0x47, 1), queue.head()!!.file.readBytes())
            assertThrows(IllegalStateException::class.java) { queue.acknowledge(1) }
            queue.acknowledge(0)
            queue = DiskQueue(dir)
            assertEquals(1L, queue.head()!!.sequence)
            queue.append(byteArrayOf(0x47, 3), 1000, 3234)
            queue.finishCapture()
            assertThrows(IllegalStateException::class.java) { queue.clearFinished() }
            queue.acknowledge(1); queue.acknowledge(2)
            assertNull(queue.head())
            queue.clearFinished()
            assertNull(queue.run())
        } finally { dir.deleteRecursively() }
    }
    @Test fun queueStopsAtCapacityWithoutDeletingEarlierVideo() {
        val dir = Files.createTempDirectory("irl-limit").toFile()
        try {
            val queue = DiskQueue(dir)
            queue.begin(pairing, runId)
            repeat(20) { queue.append(byteArrayOf(0x47), 6000, it.toLong()) }
            assertThrows(IllegalStateException::class.java) { queue.append(byteArrayOf(0x47), 200, 20) }
            assertEquals(20, queue.chunks().size)
            assertEquals(0L, queue.head()!!.sequence)
        } finally { dir.deleteRecursively() }
    }
}
