package dev.openstream.app.stream

import dev.openstream.app.SessionWorker
import org.junit.Assert.assertEquals
import org.junit.Test

class ListenerAcceptCoordinatorTest {
    @Test
    fun pendingAcceptYieldsWorkerToDisconnectBeforeTheNextPoll() {
        var generation = 7L
        val events = mutableListOf<String>()
        val worker = SessionWorker()
        val coordinator = ListenerAcceptCoordinator(
            sessionWorker = worker,
            pollIntervalMs = 250L,
            waitForNextPoll = { delay ->
                assertEquals(250L, delay)
                worker.submitAndWait {
                    events += "disconnect"
                    generation = 8L
                }
            },
        )
        try {
            val result = coordinator.awaitAccepted(
                expectedGeneration = 7L,
                currentGeneration = { generation },
                startListening = { events += "listen" },
                acceptPending = {
                    events += "poll"
                    SrtAcceptResult.Pending
                },
            )

            assertEquals(SrtAcceptResult.Cancelled, result)
            assertEquals(listOf("listen", "poll", "disconnect"), events)
        } finally {
            worker.close()
        }
    }

    @Test
    fun listenerReturnsOnlyAfterAWorkerPollAcceptsThePeer() {
        val generation = 3L
        var polls = 0
        var startThread = ""
        var acceptThread = ""
        val worker = SessionWorker()
        val coordinator = ListenerAcceptCoordinator(worker, pollIntervalMs = 0L, waitForNextPoll = {})
        try {
            val result = coordinator.awaitAccepted(
                expectedGeneration = 3L,
                currentGeneration = { generation },
                startListening = { startThread = Thread.currentThread().name },
                acceptPending = {
                    acceptThread = Thread.currentThread().name
                    polls += 1
                    if (polls == 1) SrtAcceptResult.Pending else SrtAcceptResult.Connected
                },
            )

            assertEquals(SrtAcceptResult.Connected, result)
            assertEquals(2, polls)
            assertEquals("shinSessionWorker", startThread)
            assertEquals("shinSessionWorker", acceptThread)
        } finally {
            worker.close()
        }
    }
}
