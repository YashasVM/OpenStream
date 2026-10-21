package dev.openstream.irl

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One writer lock covers publication and ACK; HTTP never holds this lock. */
internal class DiskQueue(private val root: File) {
    data class Run(val pairing: Pairing, val id: String, val next: Long, val ended: Boolean)
    data class Chunk(val sequence: Long, val file: File, val duration: Int, val captureTime: Long)
    init {
        root.mkdirs()
        // Only unpublished transactions are removed. Committed segments survive process death.
        root.listFiles()?.filter { it.name.startsWith(".") }?.forEach { it.deleteRecursively() }
    }
    @Synchronized fun run(): Run? {
        val file = File(root, "run.json")
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        return Run(Pairing(json.getString("relay"), json.getString("session"), json.getString("token")),
            json.getString("runId"), json.getLong("next"), json.getBoolean("ended"))
    }
    @Synchronized fun begin(pairing: Pairing, runId: String) {
        check(run() == null) { "Previous run is still uploading" }
        require(Pairing.isUuid(runId)) { "Invalid relay run ID" }
        save(Run(pairing, runId, 0, false))
    }
    private fun save(run: Run) {
        val json = JSONObject().put("relay", run.pairing.relay).put("session", run.pairing.session)
            .put("token", run.pairing.token).put("runId", run.id).put("next", run.next).put("ended", run.ended)
        atomic(File(root, "run.json"), json.toString().toByteArray())
    }
    @Synchronized fun chunks(): List<Chunk> = root.listFiles().orEmpty()
        .filter { it.isDirectory && it.name.matches(Regex("[0-9]{12}")) }
        .sortedBy { it.name }.map { dir ->
            val json = JSONObject(File(dir, "meta.json").readText())
            Chunk(dir.name.toLong(), File(dir, "media.ts"), json.getInt("duration"), json.getLong("captureTime"))
        }
    @Synchronized fun append(bytes: ByteArray, duration: Int, captureTime: Long) {
        require(duration in 200..6000 && bytes.size <= 8 * 1024 * 1024)
        val run = checkNotNull(run())
        val pending = chunks().filter { it.sequence >= run.next }
        check(pending.sumOf { it.file.length() } + bytes.size <= MAX_BYTES &&
            pending.sumOf { it.duration.toLong() } + duration <= MAX_DURATION) {
            "Disk queue full (256 MiB / 120 seconds). Capture stopped; pending video will upload."
        }
        val sequence = maxOf(run.next, (pending.lastOrNull()?.sequence ?: -1) + 1)
        val stage = File(root, ".segment").apply { mkdirs() }
        syncWrite(File(stage, "media.ts"), bytes)
        syncWrite(File(stage, "meta.json"), JSONObject().put("duration", duration)
            .put("captureTime", captureTime).toString().toByteArray())
        Files.move(stage.toPath(), File(root, "%012d".format(java.util.Locale.ROOT, sequence)).toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
    @Synchronized fun acknowledge(sequence: Long) {
        val run = checkNotNull(run())
        check(sequence == run.next) { "Queue acknowledgement out of order" }
        save(run.copy(next = sequence + 1))
        File(root, "%012d".format(java.util.Locale.ROOT, sequence)).deleteRecursively()
    }
    @Synchronized fun head(): Chunk? {
        val run = run() ?: return null
        // A crash after persisting ACK but before deletion leaves a harmless stale file.
        val chunks = chunks()
        chunks.filter { it.sequence < run.next }.forEach { it.file.parentFile?.deleteRecursively() }
        return chunks.firstOrNull { it.sequence >= run.next }?.also { check(it.sequence == run.next) { "Queue sequence gap" } }
    }
    @Synchronized fun finishCapture() { run()?.let { save(it.copy(ended = true)) } }
    @Synchronized fun clearFinished() {
        val run = checkNotNull(run())
        check(run.ended && head() == null)
        check(File(root, "run.json").delete()) { "Could not finish local run" }
    }
    @Synchronized fun pendingDescription(): String {
        val pending = chunks()
        return "${pending.size} queued · ${pending.sumOf { it.duration } / 1000}s on disk"
    }
    companion object {
        const val MAX_BYTES = 256L * 1024 * 1024
        const val MAX_DURATION = 120_000L
        private fun syncWrite(file: File, bytes: ByteArray) {
            FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
        }
        private fun atomic(file: File, bytes: ByteArray) {
            val temp = File(file.parentFile, ".${file.name}")
            syncWrite(temp, bytes)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
