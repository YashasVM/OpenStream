package dev.openstream.app

import dev.openstream.app.stream.SrtAcceptResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SessionWorkerTest {
    @Test
    fun rapidStartStopRunsAfterTheActiveOperationInOrder() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val observed = mutableListOf<String>()
        val worker = SessionWorker()
        try {
            worker.submit {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                synchronized(observed) { observed += "running-start" }
                completed.countDown()
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            worker.submit { synchronized(observed) { observed += "latest-stop" }; completed.countDown() }
            release.countDown()

            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("running-start", "latest-stop"), observed)
        } finally {
            release.countDown()
            worker.close()
        }
    }

    @Test
    fun staleSurfaceAndLensCallbacksCannotRunAfterGenerationChanges() {
        var generation = 4L
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val secondRelease = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val observed = mutableListOf<String>()
        val worker = SessionWorker()
        try {
            worker.submit {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            worker.submit { synchronized(observed) { observed += "preview" } }
            release.countDown()
            worker.submit {
                secondEntered.countDown()
                secondRelease.await(2, TimeUnit.SECONDS)
            }
            assertTrue(secondEntered.await(2, TimeUnit.SECONDS))
            worker.submitIfCurrent(4L, { generation }) {
                synchronized(observed) { observed += "stale-lens-restart" }
            }
            generation = 5L
            worker.submit { completed.countDown() }
            secondRelease.countDown()

            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("preview"), observed)
        } finally {
            release.countDown()
            secondRelease.countDown()
            worker.close()
        }
    }

    @Test
    fun stalePreviewDetachCannotOverrideLatestReattachedSurface() {
        var previewGeneration = 4L
        var sessionWorkGeneration = 4L
        var activeSurface: String? = null
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val worker = SessionWorker()
        try {
            assertTrue(worker.submit {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            })
            assertTrue(entered.await(2, TimeUnit.SECONDS))

            assertTrue(worker.submitIfCurrent(4L, { previewGeneration }) { activeSurface = "old-preview" })
            previewGeneration = 5L
            assertTrue(worker.submitIfCurrent(5L, { previewGeneration }) { activeSurface = null })
            sessionWorkGeneration += 1 // A lens switch invalidates session work, not this surface.
            assertTrue(worker.submitIfCurrent(5L, { previewGeneration }) {
                activeSurface = "new-preview"
            })
            assertTrue(worker.submit { completed.countDown() })
            release.countDown()

            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(5L, previewGeneration)
            assertEquals(5L, sessionWorkGeneration)
            assertEquals("new-preview", activeSurface)
        } finally {
            release.countDown()
            worker.close()
        }
    }

    @Test
    fun surfaceLensSettingsAndPreviewTransitionsRunSerially() {
        val active = AtomicInteger()
        val overlap = AtomicInteger()
        val completed = CountDownLatch(4)
        val worker = SessionWorker()
        try {
            listOf("surface-destroy", "lens-change", "settings-connect", "preview-recreate")
                .forEach {
                    worker.submit {
                        if (active.incrementAndGet() != 1) overlap.incrementAndGet()
                        Thread.sleep(10)
                        active.decrementAndGet()
                        completed.countDown()
                    }
                }

            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(0, overlap.get())
        } finally {
            worker.close()
        }
    }

    @Test
    fun fullBoundedQueueRejectsNewestWorkWithoutDroppingAcceptedStop() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val rejected = AtomicInteger()
        val worker = SessionWorker(queueCapacity = 1, onFailure = { rejected.incrementAndGet() })
        try {
            worker.submit {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(worker.submit { stopped.countDown() })
            assertEquals(false, worker.submit { error("must be rejected") })
            release.countDown()

            assertTrue(stopped.await(2, TimeUnit.SECONDS))
            assertEquals(1, rejected.get())
        } finally {
            release.countDown()
            worker.close()
        }
    }

    @Test
    fun criticalTeardownUsesReservedSlotWhenNormalQueueIsFull() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val observed = mutableListOf<String>()
        val rejected = AtomicInteger()
        val worker = SessionWorker(queueCapacity = 1, onFailure = { rejected.incrementAndGet() })
        try {
            assertTrue(worker.submit {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
            })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(worker.submit {
                synchronized(observed) { observed += "normal" }
                completed.countDown()
            })

            // The normal waiting slot is occupied. A critical Stop still has
            // its reserved slot and remains serialized behind accepted work.
            assertTrue(worker.submitCritical {
                synchronized(observed) { observed += "teardown" }
                completed.countDown()
            })
            assertEquals(false, worker.submit { error("normal overflow must reject newest") })
            release.countDown()

            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("normal", "teardown"), observed)
            assertEquals(1, rejected.get())
        } finally {
            release.countDown()
            worker.close()
        }
    }

    @Test
    fun nonBlockingAcceptPollYieldsTheWorkerForDisconnectBetweenPolls() {
        var listenerActive = true
        val worker = SessionWorker()
        try {
            val firstPoll = worker.submitAndWait {
                if (listenerActive) SrtAcceptResult.Pending else SrtAcceptResult.Cancelled
            }
            assertEquals(SrtAcceptResult.Pending, firstPoll)

            val teardown = worker.submitAndWait {
                listenerActive = false
                "disconnected"
            }
            val nextPoll = worker.submitAndWait {
                if (!listenerActive) SrtAcceptResult.Cancelled else SrtAcceptResult.Pending
            }

            assertEquals("disconnected", teardown)
            assertEquals(SrtAcceptResult.Cancelled, nextPoll)
        } finally {
            worker.close()
        }
    }
}
