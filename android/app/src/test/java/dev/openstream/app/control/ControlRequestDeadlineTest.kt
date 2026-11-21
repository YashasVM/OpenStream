package dev.openstream.app.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ControlRequestDeadlineTest {
    @Test
    fun tricklingHeadersCannotBlockTheNextClient() = checkTrickleDeadline(body = false)

    @Test
    fun tricklingBodyCannotBlockTheNextClient() = checkTrickleDeadline(body = true)

    private fun checkTrickleDeadline(body: Boolean) {
        val workers = Executors.newFixedThreadPool(2)
        val accepted = CountDownLatch(1)
        ServerSocket(0).use { listener ->
            Socket("127.0.0.1", listener.localPort).use { slow ->
                Socket("127.0.0.1", listener.localPort).use { next ->
                    next.soTimeout = 1500
                    val served = workers.submit<Boolean> {
                        var expired = false
                        listener.accept().use { client ->
                            val input = ControlRequestInput(client, 200)
                            accepted.countDown()
                            try {
                                if (body) {
                                    val bytes = ByteArray(64)
                                    while (input.read(bytes, 0, bytes.size) >= 0) { }
                                } else {
                                    while (input.read() >= 0) { }
                                }
                            } catch (_: SocketTimeoutException) { expired = true }
                        }
                        listener.accept().use { it.getOutputStream().write(42) }
                        expired
                    }
                    assertTrue(accepted.await(1, TimeUnit.SECONDS))
                    val trickle = workers.submit {
                        try {
                            repeat(40) {
                                slow.getOutputStream().write('x'.code)
                                slow.getOutputStream().flush()
                                Thread.sleep(25)
                            }
                        } catch (_: Exception) { }
                    }
                    try {
                        assertTrue("Whole request must expire despite continued input", served.get(800, TimeUnit.MILLISECONDS))
                        assertEquals(42, next.getInputStream().read())
                    } finally {
                        slow.close()
                        trickle.cancel(true)
                        workers.shutdownNow()
                    }
                }
            }
        }
    }
}
