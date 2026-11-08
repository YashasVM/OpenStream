package dev.openstream.irl

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLStreamHandler
import java.net.URLStreamHandlerFactory
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class UploadTest {
    @Test fun backlogDrainsInBurstAndLostAcknowledgementRetriesIdenticalSequence() {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val lostAck = AtomicBoolean(true)
        val allUploaded = CountDownLatch(1)
        URL.setURLStreamHandlerFactory(URLStreamHandlerFactory { protocol ->
            if (protocol != "https") null else object : URLStreamHandler() {
                override fun openConnection(url: URL) = object : HttpURLConnection(url) {
                    override fun connect() = Unit
                    override fun disconnect() = Unit
                    override fun usingProxy() = false
                    override fun getOutputStream() = ByteArrayOutputStream()
                    override fun getResponseCode() = 200
                    override fun getInputStream(): ByteArrayInputStream {
                        requests.add(url.path)
                        if (url.path.endsWith("/segments/0") && lostAck.getAndSet(false)) {
                            throw java.io.IOException("Simulated lost acknowledgement")
                        }
                        if (url.path.endsWith("/end")) {
                            allUploaded.countDown()
                            return ByteArrayInputStream("{}".toByteArray())
                        }
                        val seq = url.path.substringAfterLast('/').toLong()
                        return ByteArrayInputStream("{\"nextSequence\":${seq + 1}}".toByteArray())
                    }
                }
            }
        })
        val dir = Files.createTempDirectory("irl-upload").toFile()
        val queue = DiskQueue(dir)
        queue.begin(Pairing("https://relay.example", "123e4567-e89b-12d3-a456-426614174000", "a".repeat(43)),
            "223e4567-e89b-12d3-a456-426614174000")
        repeat(6) { queue.append(byteArrayOf(0x47, it.toByte()), 1000, it * 1000L) }
        val started = System.nanoTime()
        val uploader = RelayUploader(queue, {}, { fail(it) })
        try {
            assertTrue("Backlog must drain faster than one chunk per second", allUploaded.await(4, TimeUnit.SECONDS))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 4000)
            val paths = requests.filter { it.contains("/segments/") }.map { it.substringAfterLast('/').toInt() }
            assertEquals(listOf(0, 0, 1, 2, 3, 4, 5), paths)
        } finally { uploader.close(); dir.deleteRecursively() }
    }
}
