package dev.openstream.app

import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Serializes camera, codec, and transport lifecycle work away from the UI thread.
 * Capacity is one running operation plus 32 queued operations by default; when
 * full, the newest submission is rejected and reported without evicting teardown.
 */
internal class SessionWorker(
    queueCapacity: Int = 32,
    private val onFailure: (Throwable) -> Unit = { error ->
        Log.e("shinSession", "Session work failed", error)
    },
) : AutoCloseable {
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(queueCapacity.coerceAtLeast(1)),
        { task -> Thread(task, "shinSessionWorker").apply { isDaemon = true } },
    )

    init {
        executor.setRejectedExecutionHandler { _, _ ->
            throw RejectedExecutionException("Session worker queue is full")
        }
    }

    fun submit(work: () -> Unit): Boolean {
        try {
            executor.execute {
                try {
                    work()
                } catch (error: Throwable) {
                    onFailure(error)
                }
            }
            return true
        } catch (error: RejectedExecutionException) {
            onFailure(error)
            return false
        }
    }

    fun submitIfCurrent(
        generation: Long,
        currentGeneration: () -> Long,
        work: () -> Unit,
    ): Boolean = submit {
        if (currentGeneration() == generation) work()
    }

    override fun close() {
        executor.shutdown()
    }
}
