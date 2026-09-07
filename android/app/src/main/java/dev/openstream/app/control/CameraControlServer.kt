package dev.openstream.app.control

import android.util.Log
import dev.openstream.app.camera.Camera2Controller
import dev.openstream.app.camera.CameraLens
import dev.openstream.app.stream.StreamConfig
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight HTTP control server that accepts camera control commands from OBS.
 * Runs on port 9001 by default. Provides endpoints:
 *
 * - POST /zoom       {"value": 2.5}
 * - POST /torch      {"enabled": true}
 * - POST /lens       {"lens": "Back"}
 * - POST /reserve    {"sourceInstanceId": "...", "bitrateMbps": 12}
 * - POST /release    {"sourceInstanceId": "..."}
 * - POST /identify   {"label": "CAM B", "subtitle": "Close-up"}
 * - GET  /status     Returns current camera state
 */
class CameraControlServer(
    private val port: Int = CONTROL_PORT,
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
    private val pendingRestart = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private var desiredRunning = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var activeClient: Socket? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var activeReservationToken: String? = null
    @Volatile private var activeControllerAddress: String? = null
    @Volatile private var activeReservationSlotLabel: String? = null
    @Volatile private var activeReservationBitrateMbps: Int? = null

    fun start() {
        synchronized(lifecycleLock) {
            desiredRunning = true
            startLocked()
        }
    }

    private fun startLocked() {
        if (!desiredRunning || running.get()) return
        if (worker?.isAlive == true) {
            pendingRestart.set(true)
            Log.w(TAG, "Control server worker is still stopping; restart queued")
            return
        }
        if (!running.compareAndSet(false, true)) return
        pendingRestart.set(false)
        worker = Thread(::run, "OpenStreamControlServer").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        val thread = synchronized(lifecycleLock) {
            desiredRunning = false
            pendingRestart.set(false)
            running.set(false)
            runCatching { serverSocket?.close() }
            runCatching { activeClient?.close() }
            worker.also { it?.interrupt() }
        }
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(STOP_TIMEOUT_MS) }
                .onFailure { Thread.currentThread().interrupt() }
        }
        synchronized(lifecycleLock) {
            if (worker === thread && thread?.isAlive != true) worker = null
            serverSocket = null
            activeClient = null
        }
    }

    private fun run() {
        var openedSocket: ServerSocket? = null
        try {
            val socket = ServerSocket(port)
            openedSocket = socket
            serverSocket = socket
            socket.soTimeout = 1000
            Log.i(TAG, "Camera control server listening on port $port")

            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (e: java.net.SocketTimeoutException) {
                    continue
                } catch (e: java.net.SocketException) {
                    if (running.get()) Log.w(TAG, "Socket error", e)
                    break
                }
                activeClient = client
                try {
                    handleClient(client)
                } finally {
                    activeClient = null
                }
            }
        } catch (e: Exception) {
            if (running.get()) Log.e(TAG, "Control server error", e)
        } finally {
            runCatching { openedSocket?.close() }
            synchronized(lifecycleLock) {
                if (serverSocket === openedSocket) serverSocket = null
                running.set(false)
                if (worker === Thread.currentThread()) {
                    worker = null
                }
                val restart = pendingRestart.getAndSet(false)
                if (restart && desiredRunning) {
                    startLocked()
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 5000
            val input = BufferedInputStream(client.getInputStream())
            val writer = PrintWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8), false)
            val controllerAddress = client.inetAddress?.hostAddress.orEmpty()

            // Parse request line
            val requestLine = readAsciiLine(input, MAX_REQUEST_LINE_BYTES) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                sendResponse(writer, 400, """{"error":"bad request"}""")
                return
            }
            val method = parts[0]
            val path = parts[1]

            // Read headers to get Content-Length and validate native JSON requests.
            var contentLength = 0
            var contentType: String? = null
            var headerBytes = 0
            var line = readAsciiLine(input, MAX_HEADER_LINE_BYTES)
            while (line != null && line.isNotEmpty()) {
                headerBytes += line.toByteArray(Charsets.US_ASCII).size + 2
                if (headerBytes > MAX_HEADER_BYTES) {
                    sendResponse(writer, 413, """{"error":"headers too large"}""")
                    return
                }
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: -1
                }
                if (line.startsWith("Content-Type:", ignoreCase = true)) {
                    contentType = line.substringAfter(":").trim()
                }
                line = readAsciiLine(input, MAX_HEADER_LINE_BYTES)
            }
            if (line == null || contentLength !in 0..MAX_BODY_BYTES) {
                sendResponse(writer, if (contentLength > MAX_BODY_BYTES) 413 else 400,
                    if (contentLength > MAX_BODY_BYTES) """{"error":"request too large"}"""
                    else """{"error":"bad request"}""")
                return
            }

            val requiresJson = method == "POST" && when (path) {
                "/zoom", "/torch", "/lens", "/reserve", "/release", "/identify" -> true
                else -> false
            }
            val mediaType = contentType?.substringBefore(';')?.trim()
            if (requiresJson && !mediaType.equals("application/json", ignoreCase = true)) {
                sendResponse(writer, 415, """{"error":"application/json required"}""")
                return
            }

            // Read body if present
            val body = if (contentLength > 0) {
                val bytes = ByteArray(contentLength)
                var offset = 0
                while (offset < bytes.size) {
                    val read = input.read(bytes, offset, bytes.size - offset)
                    if (read < 0) {
                        sendResponse(writer, 400, """{"error":"incomplete request"}""")
                        return
                    }
                    offset += read
                }
                String(bytes, Charsets.UTF_8)
            } else ""

            // This is a native LAN control protocol, not a browser API. Do not
            // expose permissive CORS/preflight behavior: a web page running on
            // the reserving OBS host would otherwise share the same peer IP and
            // could satisfy the peer-authorization boundary. Requiring JSON for
            // every mutating route also forces browsers through preflight, which
            // this server intentionally does not support.
            val response = when {
                method == "GET" && path == "/status" -> handleStatus()
                method == "POST" && path == "/zoom" -> handleZoom(body, controllerAddress)
                method == "POST" && path == "/torch" -> handleTorch(body, controllerAddress)
                method == "POST" && path == "/lens" -> handleLens(body, controllerAddress)
                method == "POST" && path == "/reserve" -> handleReserve(body, controllerAddress)
                method == "POST" && path == "/release" -> handleRelease(body, controllerAddress)
                method == "POST" && path == "/identify" -> handleIdentify(body, controllerAddress)
                else -> {
                    sendResponse(writer, 404, """{"error":"not found"}""")
                    return
                }
            }
            sendResponse(writer, 200, response)
        } catch (e: Exception) {
            Log.w(TAG, "Error handling control request", e)
        } finally {
            runCatching { client.close() }
        }
    }

    private fun sendResponse(writer: PrintWriter, code: Int, body: String) {
        val status = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            415 -> "Unsupported Media Type"
            else -> "Error"
        }
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        writer.print("HTTP/1.1 $code $status\r\n")
        writer.print("Content-Type: application/json\r\n")
        writer.print("Content-Length: ${bodyBytes.size}\r\n")
        writer.print("Connection: close\r\n")
        writer.print("\r\n")
        writer.print(body)
        writer.flush()
    }

    /** Reads a CRLF-delimited HTTP line without decoding body bytes as characters. */
    private fun readAsciiLine(input: BufferedInputStream, maxBytes: Int): String? {
        val bytes = ArrayList<Byte>(minOf(maxBytes, 256))
        while (bytes.size <= maxBytes) {
            val value = input.read()
            if (value < 0) return null
            if (value == '\n'.code) {
                if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.removeAt(bytes.lastIndex)
                return bytes.toByteArray().toString(Charsets.US_ASCII)
            }
            bytes.add(value.toByte())
        }
        return null
    }

    private fun handleStatus(): String {
        val camera = cameraProvider()
        val json = JSONObject()
            .put("zoom", camera.zoomRatio.toDouble())
            .put("zoomMin", camera.zoomRange.start.toDouble())
            .put("zoomMax", camera.zoomRange.endInclusive.toDouble())
            .put("currentLens", currentLensProvider().shortLabel)
            .put("availableLenses", lensListProvider().map { it.shortLabel })
            .put("reservedBy", reservationProvider().orEmpty())
        return json.toString()
    }

    private fun isAuthorizedController(controllerAddress: String): Boolean {
        return reservationProvider() != null &&
            controllerAddress.isNotEmpty() &&
            controllerAddress == activeControllerAddress
    }

    private fun unauthorizedControlResponse(): String = """{"ok":false,"unauthorized":true}"""

    private fun busyReservationResponse(currentReservation: String): String = JSONObject()
        .put("ok", false)
        .put("busy", true)
        .put("reservedBy", currentReservation)
        .toString()

    private fun handleZoom(body: String, controllerAddress: String): String {
        if (!isAuthorizedController(controllerAddress)) return unauthorizedControlResponse()
        val json = JSONObject(body)
        val value = json.getDouble("value").toFloat()
        val applied = cameraProvider().setZoom(value)
        return """{"ok":true,"zoom":$applied}"""
    }

    private fun handleTorch(body: String, controllerAddress: String): String {
        if (!isAuthorizedController(controllerAddress)) return unauthorizedControlResponse()
        val json = JSONObject(body)
        val enabled = json.getBoolean("enabled")
        onToggleTorch(enabled)
        return """{"ok":true,"torch":$enabled}"""
    }

    private fun handleLens(body: String, controllerAddress: String): String {
        if (!isAuthorizedController(controllerAddress)) return unauthorizedControlResponse()
        val json = JSONObject(body)
        val lensLabel = json.getString("lens")
        val available = lensListProvider()
        val target = available.firstOrNull { it.shortLabel == lensLabel }
            ?: return """{"error":"lens not found","available":${available.map { "\"${it.shortLabel}\"" }}}"""
        onSwitchLens(target)
        return """{"ok":true,"lens":"${target.shortLabel}"}"""
    }

    private fun handleReserve(body: String, controllerAddress: String): String {
        val json = JSONObject(body)
        val sourceInstanceId = json.optString("sourceInstanceId").trim()
        if (sourceInstanceId.isEmpty()) return """{"error":"missing sourceInstanceId"}"""
        val reservationToken = json.optString("reservationToken").trim().ifEmpty { null }
        if (reservationToken != null && reservationToken.length > MAX_RESERVATION_TOKEN_CHARS) {
            return """{"error":"reservation token too large"}"""
        }
        val currentReservation = reservationProvider()
        if (currentReservation != null && currentReservation != sourceInstanceId) {
            return busyReservationResponse(currentReservation)
        }
        val currentControllerAddress = activeControllerAddress
        if (currentReservation == sourceInstanceId &&
            (currentControllerAddress == null ||
                controllerAddress.isEmpty() ||
                controllerAddress != currentControllerAddress)
        ) {
            return unauthorizedControlResponse()
        }
        val slotLabel = json.optString("slotLabel", "")
        val bitrateMbps = if (json.has("bitrateMbps")) {
            json.optInt("bitrateMbps").coerceIn(
                StreamConfig.MIN_BITRATE_MBPS,
                StreamConfig.MAX_BITRATE_MBPS,
            )
        } else null
        val sameReservationConfig = currentReservation == sourceInstanceId &&
            activeReservationSlotLabel == slotLabel &&
            activeReservationBitrateMbps == bitrateMbps
        if (sameReservationConfig) {
            activeReservationToken = reservationToken
            return JSONObject()
                .put("ok", true)
                .put("reservedBy", sourceInstanceId)
                .toString()
        }
        val accepted = onReserve(sourceInstanceId, slotLabel, bitrateMbps)
        return if (accepted) {
            activeReservationToken = reservationToken
            activeControllerAddress = controllerAddress.ifEmpty { null }
            activeReservationSlotLabel = slotLabel
            activeReservationBitrateMbps = bitrateMbps
            JSONObject()
                .put("ok", true)
                .put("reservedBy", sourceInstanceId)
                .toString()
        } else {
            busyReservationResponse(reservationProvider().orEmpty())
        }
    }

    private fun handleRelease(body: String, controllerAddress: String): String {
        val json = JSONObject(body)
        val sourceInstanceId = json.optString("sourceInstanceId").trim()
        if (sourceInstanceId.isEmpty()) return """{"error":"missing sourceInstanceId"}"""
        val reservationToken = json.optString("reservationToken").trim().ifEmpty { null }
        val currentReservation = reservationProvider()
        if (currentReservation == sourceInstanceId &&
            (activeControllerAddress == null ||
                controllerAddress.isEmpty() ||
                controllerAddress != activeControllerAddress)
        ) {
            return unauthorizedControlResponse()
        }
        if (currentReservation == sourceInstanceId &&
            activeReservationToken != null &&
            reservationToken != activeReservationToken
        ) {
            return """{"ok":false,"stale":true}"""
        }
        val released = onRelease(sourceInstanceId)
        if (released && reservationProvider() != sourceInstanceId) {
            activeReservationToken = null
            activeControllerAddress = null
            activeReservationSlotLabel = null
            activeReservationBitrateMbps = null
        }
        return """{"ok":$released}"""
    }

    private fun handleIdentify(body: String, controllerAddress: String): String {
        if (!isAuthorizedController(controllerAddress)) return unauthorizedControlResponse()
        val json = JSONObject(body)
        val label = json.optString("label", "CAM").ifBlank { "CAM" }
        val subtitle = json.optString("subtitle", "")
        onIdentify(label, subtitle)
        return """{"ok":true}"""
    }

    companion object {
        private const val TAG = "OpenStreamControl"
        const val CONTROL_PORT = 9001
        private const val MAX_REQUEST_LINE_BYTES = 2_048
        private const val MAX_HEADER_LINE_BYTES = 2_048
        private const val MAX_HEADER_BYTES = 8_192
        private const val MAX_BODY_BYTES = 8_192
        private const val MAX_RESERVATION_TOKEN_CHARS = 256
        private const val STOP_TIMEOUT_MS = 1_000L
    }
}