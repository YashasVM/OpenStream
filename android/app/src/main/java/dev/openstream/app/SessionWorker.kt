package dev.openstream.app

import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Serializes camera, codec, and transport lifecycle work away from the UI thread.
 * Normal work has [queueCapacity] waiting slots. One extra waiting slot is
 * reserved for critical teardown, so normal saturation cannot reject Stop or
 * disconnect cleanup. Work stays FIFO; normal overflow rejects the newest
 * normal task. Critical submissions can still be rejected if the entire
 * bounded queue, including its reserved slot, is already occupied.
 */
internal class SessionWorker(
    queueCapacity: Int = 32,
    private val onFailure: (Throwable) -> Unit = { error ->
        Log.e("shinSession", "Session work failed", error)
    },
) : AutoCloseable {
    private val normalQueueCapacity = queueCapacity.coerceAtLeast(1)
    private val submissionLock = Any()
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(normalQueueCapacity + 1),
        { task -> Thread(task, "shinSessionWorker").apply { isDaemon = true } },
    )

    init {
        executor.setRejectedExecutionHandler { _, _ ->
            throw RejectedExecutionException("Session worker queue is full")
        }
    }

    fun submit(work: () -> Unit): Boolean = enqueue(work, critical = false)

    val isShutdown: Boolean get() = executor.isShutdown

    /** Queues teardown in the reserved slot when all normal waiting slots are occupied. */
    fun submitCritical(work: () -> Unit): Boolean = enqueue(work, critical = true)

    private fun enqueue(work: () -> Unit, critical: Boolean): Boolean = synchronized(submissionLock) {
        val queued = executor.queue.size
        val limit = if (critical) normalQueueCapacity + 1 else normalQueueCapacity
        if (queued >= limit) {
            onFailure(RejectedExecutionException(
                if (critical) "Critical session worker queue is full"
                else "Session worker queue is full",
            ))
            return@synchronized false
        }
        try {
            executor.execute {
                try {
                    work()
                } catch (error: Throwable) {
                    onFailure(error)
                }
            }
            true
        } catch (error: RejectedExecutionException) {
            onFailure(error)
            false
        }
    }

    fun submitIfCurrent(
        generation: Long,
        currentGeneration: () -> Long,
        work: () -> Unit,
    ): Boolean = submit {
        if (currentGeneration() == generation) work()
    }

    /** Waits for a short worker operation; callers must be non-UI threads. */
    fun <T> submitAndWait(work: () -> T): T {
        val task = FutureTask(Callable { work() })
        try {
            synchronized(submissionLock) {
                if (executor.queue.size >= normalQueueCapacity) {
                    throw RejectedExecutionException("Session worker queue is full")
                }
                executor.execute(task)
            }
        } catch (error: RejectedExecutionException) {
            onFailure(error)
            throw error
        }
        return try {
            task.get()
        } catch (error: InterruptedException) {
            task.cancel(false)
            Thread.currentThread().interrupt()
            throw error
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    override fun close() {
        executor.shutdown()
    }
}
