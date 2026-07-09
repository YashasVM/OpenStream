package dev.openstream.app.control

import android.util.Log
import dev.openstream.app.camera.Camera2Controller
import dev.openstream.app.camera.CameraLens
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Authenticated HTTP control server for a paired OBS source.
 *
 * The pairing URL supplies the token; it is deliberately kept in memory only.
 * Every endpoint, including status, requires `X-OpenStream-Token`.
 */
class CameraControlServer(
    private val port: Int = CONTROL_PORT,
    private val authTokenProvider: () -> String?,
    private val cameraProvider: () -> Camera2Controller,
    private val lensListProvider: () -> List<CameraLens>,
    private val currentLensProvider: () -> CameraLens,
    private val onSwitchLens: (CameraLens) -> Unit,
    private val onToggleTorch: (Boolean) -> Unit,
    private val reservationProvider: () -> String?,
    private val onReserve: (String, String, Int?) -> Boolean,
    private val onRelease: (String) -> Boolean,
    private val onIdentify: (String, String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    @Volatile private var clients: ThreadPoolExecutor? = null
    private var serverSocket: ServerSocket? = null
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        clients = Executors.newFixedThreadPool(MAX_CONCURRENT_CLIENTS) as ThreadPoolExecutor
        worker = Thread(::run, "OpenStreamControlServer").apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        clients?.shutdownNow()
        clients = null
        serverSocket = null
        worker = null
    }

    private fun run() {
        try {
            ServerSocket(port).use { socket ->
                serverSocket = socket
                socket.soTimeout = ACCEPT_TIMEOUT_MS
                Log.i(TAG, "Camera control server listening on port $port")
                while (running.get()) {
                    val client = try { socket.accept() }
                    catch (_: java.net.SocketTimeoutException) { continue }
                    catch (e: java.net.SocketException) {
                        if (running.get()) Log.w(TAG, "Socket error", e)
                        break
                    }
                    val executor = clients
                    if (executor == null || executor.queue.size >= MAX_QUEUED_CLIENTS) {
                        client.close()
                    } else {
                        executor.execute { handleClient(client) }
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) Log.e(TAG, "Control server error", e)
        } finally {
            serverSocket = null
        }
    }

    private fun handleClient(client: Socket) {
        client.use {
            try {
                it.soTimeout = CLIENT_TIMEOUT_MS
                val input = it.getInputStream()
                val writer = PrintWriter(it.getOutputStream(), false, StandardCharsets.UTF_8)
                val requestLine = readAsciiLine(input, MAX_REQUEST_LINE_BYTES)
                    ?: return sendResponse(writer, 400, "{\"error\":\"bad request\"}")
                val parts = requestLine.split(' ')
                if (parts.size != 3 || !parts[2].startsWith("HTTP/")) {
                    return sendResponse(writer, 400, "{\"error\":\"bad request\"}")
                }
                val headers = readHeaders(input) ?: return sendResponse(writer, 400, "{\"error\":\"bad headers\"}")
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                if (contentLength !in 0..MAX_BODY_BYTES) {
                    return sendResponse(writer, 413, "{\"error\":\"request too large\"}")
                }
                val suppliedToken = headers[AUTH_HEADER.lowercase()]
                if (!isAuthorized(suppliedToken)) {
                    return sendResponse(writer, 401, "{\"error\":\"unauthorized\"}")
                }
                val body = readBody(input, contentLength)
                    ?: return sendResponse(writer, 400, "{\"error\":\"incomplete request\"}")
                val (method, path) = parts
                val response = when {
                    method == "GET" && path == "/status" -> handleStatus()
                    method == "POST" && path == "/zoom" -> handleZoom(body)
                    method == "POST" && path == "/torch" -> handleTorch(body)
                    method == "POST" && path == "/lens" -> handleLens(body)
                    method == "POST" && path == "/reserve" -> handleReserve(body)
                    method == "POST" && path == "/release" -> handleRelease(body)
                    method == "POST" && path == "/identify" -> handleIdentify(body)
                    else -> return sendResponse(writer, 404, "{\"error\":\"not found\"}")
                }
                sendResponse(writer, 200, response)
            } catch (e: Exception) {
                Log.w(TAG, "Error handling control request", e)
            }
        }
    }

    private fun readHeaders(input: InputStream): Map<String, String>? {
        val headers = linkedMapOf<String, String>()
        var total = 0
        repeat(MAX_HEADER_COUNT) {
            val line = readAsciiLine(input, MAX_HEADER_BYTES - total) ?: return null
            total += line.length
            if (total > MAX_HEADER_BYTES) return null
            if (line.isEmpty()) return headers
            val separator = line.indexOf(':')
            if (separator <= 0) return null
            headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
        }
        return null
    }

    private fun readBody(input: InputStream, contentLength: Int): String? {
        if (contentLength == 0) return ""
        val bytes = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val read = input.read(bytes, offset, contentLength - offset)
            if (read < 0) return null
            offset += read
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    /** HTTP request lines and headers are ASCII; body length is handled as bytes. */
    private fun readAsciiLine(input: InputStream, maxBytes: Int): String? {
        val line = ByteArrayOutputStream()
        while (line.size() <= maxBytes) {
            val next = input.read()
            if (next < 0) return null
            if (next == '\n'.code) {
                val bytes = line.toByteArray()
                val length = if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, length, StandardCharsets.US_ASCII)
            }
            line.write(next)
        }
        return null
    }

    private fun isAuthorized(suppliedToken: String?): Boolean {
        val expectedToken = authTokenProvider()?.takeIf(::isValidAuthToken) ?: return false
        val supplied = suppliedToken?.takeIf(::isValidAuthToken) ?: return false
        return MessageDigest.isEqual(
            expectedToken.toByteArray(StandardCharsets.UTF_8),
            supplied.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun sendResponse(writer: PrintWriter, code: Int, body: String) {
        val status = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 413 -> "Payload Too Large"
            else -> "Error"
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        writer.print("HTTP/1.1 $code $status\r\n")
        writer.print("Content-Type: application/json; charset=utf-8\r\n")
        writer.print("Content-Length: ${bytes.size}\r\n")
        writer.print("Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n")
        writer.print(body)
        writer.flush()
    }

    private fun handleStatus(): String {
        val camera = cameraProvider()
        return JSONObject().put("zoom", camera.zoomRatio.toDouble()).put("zoomMin", camera.zoomRange.start.toDouble())
            .put("zoomMax", camera.zoomRange.endInclusive.toDouble()).put("currentLens", currentLensProvider().shortLabel)
            .put("availableLenses", lensListProvider().map { it.shortLabel }).put("reservedBy", reservationProvider().orEmpty()).toString()
    }

    private fun handleZoom(body: String): String { val applied = cameraProvider().setZoom(JSONObject(body).getDouble("value").toFloat()); return "{\"ok\":true,\"zoom\":$applied}" }
    private fun handleTorch(body: String): String { val enabled = JSONObject(body).getBoolean("enabled"); onToggleTorch(enabled); return "{\"ok\":true,\"torch\":$enabled}" }
    private fun handleLens(body: String): String {
        val target = lensListProvider().firstOrNull { it.shortLabel == JSONObject(body).getString("lens") }
            ?: return "{\"error\":\"lens not found\"}"
        onSwitchLens(target); return "{\"ok\":true,\"lens\":\"${target.shortLabel}\"}"
    }
    private fun handleReserve(body: String): String {
        val json = JSONObject(body); val source = json.optString("sourceInstanceId").trim()
        if (source.isEmpty()) return "{\"error\":\"missing sourceInstanceId\"}"
        val bitrateMbps = if (json.has("bitrateMbps")) json.optInt("bitrateMbps").coerceIn(1, 200) else null
        val accepted = onReserve(source, json.optString("slotLabel", ""), bitrateMbps)
        return JSONObject().put("ok", accepted).put("busy", !accepted).put("reservedBy", if (accepted) source else reservationProvider().orEmpty()).toString()
    }
    private fun handleRelease(body: String): String { val source = JSONObject(body).optString("sourceInstanceId").trim(); return if (source.isEmpty()) "{\"error\":\"missing sourceInstanceId\"}" else "{\"ok\":${onRelease(source)}}" }
    private fun handleIdentify(body: String): String { val json = JSONObject(body); onIdentify(json.optString("label", "CAM").ifBlank { "CAM" }, json.optString("subtitle", "")); return "{\"ok\":true}" }

    companion object {
        private const val TAG = "OpenStreamControl"
        const val CONTROL_PORT = 9001
        private const val AUTH_HEADER = "X-OpenStream-Token"
        private const val ACCEPT_TIMEOUT_MS = 1_000
        private const val CLIENT_TIMEOUT_MS = 5_000
        private const val MAX_REQUEST_LINE_BYTES = 2_048
        private const val MAX_HEADER_BYTES = 8_192
        private const val MAX_HEADER_COUNT = 32
        private const val MAX_BODY_BYTES = 8_192
        private const val MAX_CONCURRENT_CLIENTS = 4
        private const val MAX_QUEUED_CLIENTS = 16

        /** 256-bit hexadecimal token generated by the OBS source. */
        fun isValidAuthToken(value: String): Boolean = value.length == 64 && value.all {
            it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F'
        }
    }
}
