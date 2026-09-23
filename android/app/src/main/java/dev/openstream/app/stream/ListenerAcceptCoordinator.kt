package dev.openstream.app.stream

import dev.openstream.app.SessionWorker

/** Coordinates short listener setup/accept operations through the session worker. */
internal class ListenerAcceptCoordinator(
    private val sessionWorker: SessionWorker,
    private val pollIntervalMs: Long,
    private val waitForNextPoll: (Long) -> Unit = { Thread.sleep(it) },
) {
    fun awaitAccepted(
        expectedGeneration: Long,
        currentGeneration: () -> Long,
        startListening: () -> Unit,
        acceptPending: () -> SrtAcceptResult,
    ): SrtAcceptResult {
        if (currentGeneration() != expectedGeneration) return SrtAcceptResult.Cancelled
        sessionWorker.submitAndWait {
            if (currentGeneration() == expectedGeneration) startListening()
        }
        while (currentGeneration() == expectedGeneration) {
            val result = sessionWorker.submitAndWait(acceptPending)
            if (currentGeneration() != expectedGeneration) return SrtAcceptResult.Cancelled
            if (result != SrtAcceptResult.Pending) return result
            waitForNextPoll(pollIntervalMs)
        }
        return SrtAcceptResult.Cancelled
    }
}
