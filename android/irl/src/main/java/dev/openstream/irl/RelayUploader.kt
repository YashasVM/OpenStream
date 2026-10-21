package dev.openstream.irl

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class RelayUploader(private val queue: DiskQueue, private val status: (String) -> Unit,
                             private val fatal: (String) -> Unit) {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var blocked = false
    @Volatile private var closed = false
    private val deadlines = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var activeConnection: HttpURLConnection? = null
    init {
        // A process restart ends capture, but never abandons committed chunks from the prior run.
        queue.finishCapture()
        worker.execute { pump() }
    }
    fun start(pairing: Pairing, ready: () -> Unit, failed: (String) -> Unit) {
        worker.execute {
            try {
                check(queue.run() == null) { "Wait for the previous run to finish uploading" }
                val response = request(pairing, "/start", "POST")
                queue.begin(pairing, response.getString("runId"))
                blocked = false
                ready()
            } catch (_: Exception) { failed("Could not start. Check pairing, HTTPS relay, and network; then retry.") }
        }
    }
    fun retry() { blocked = false }
    fun finishCancelledStart() { worker.execute { queue.finishCapture() } }
    private fun pump() {
        var delayMs = 500L
        try {
            if (blocked || closed) return
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var uploaded = 0
            while (!closed && uploaded < 8 && System.nanoTime() < deadline) {
                val run = queue.run() ?: break
                val chunk = queue.head()
                if (chunk != null) {
                    val response = request(run.pairing, "/runs/${run.id}/segments/${chunk.sequence}", "PUT", chunk)
                    check(response.getLong("nextSequence") == chunk.sequence + 1) { "Invalid upload acknowledgement" }
                    queue.acknowledge(chunk.sequence)
                    uploaded++
                    status(queue.pendingDescription())
                } else {
                    if (run.ended) {
                        request(run.pairing, "/end", "POST")
                        queue.clearFinished()
                        status("Upload complete. Ready for a new run.")
                    }
                    break
                }
            }
            if (queue.head() != null) delayMs = 0
        } catch (error: RelayRejected) {
            blocked = true
            fatal("Relay rejected upload (HTTP ${error.code}). Queue retained. Check session/run before retrying.")
        } catch (_: Exception) {
            delayMs = 2000
            status("Connection interrupted. Retrying the same segment. ${queue.pendingDescription()}")
        } finally {
            if (!closed) worker.schedule({ pump() }, delayMs, TimeUnit.MILLISECONDS)
        }
    }
    private class RelayRejected(val code: Int) : Exception()
    private fun request(pairing: Pairing, path: String, method: String, chunk: DiskQueue.Chunk? = null): JSONObject {
        val connection = URL(pairing.base + path).openConnection() as HttpURLConnection
        activeConnection = connection
        // HttpURLConnection has no write timeout. Disconnect also bounds a stalled request body.
        val deadline = deadlines.schedule({ connection.disconnect() }, 30, TimeUnit.SECONDS)
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer ${pairing.token}")
            connection.setRequestProperty("Connection", "close")
            connection.doOutput = true
            if (chunk != null) {
                connection.setRequestProperty("Content-Type", "video/mp2t")
                connection.setRequestProperty("X-Duration-Ms", chunk.duration.toString())
                connection.setRequestProperty("X-Capture-Time-Ms", chunk.captureTime.toString())
                connection.setFixedLengthStreamingMode(chunk.file.length())
                connection.outputStream.use { output -> chunk.file.inputStream().use { it.copyTo(output) } }
            } else {
                connection.setFixedLengthStreamingMode(0)
                connection.outputStream.close()
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                if (code in 400..499 && code != 408 && code != 429) throw RelayRejected(code)
                throw java.io.IOException("Temporary relay failure")
            }
            val bytes = connection.inputStream.use { input ->
                val buffer = ByteArray(16_385)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                buffer.copyOf(count)
            }
            check(bytes.size <= 16_384) { "Oversized response" }
            return if (bytes.isEmpty()) JSONObject() else JSONObject(String(bytes, Charsets.UTF_8))
        } finally { deadline.cancel(false); connection.disconnect(); activeConnection = null }
    }
    fun close() { closed = true; activeConnection?.disconnect(); worker.shutdownNow(); deadlines.shutdownNow() }
}
