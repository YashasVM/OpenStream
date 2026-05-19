package dev.openstream.app.control

import android.util.Log
import dev.openstream.app.camera.Camera2Controller
import dev.openstream.app.camera.CameraLens
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
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
 * - GET  /status     Returns current camera state
 */
class CameraControlServer(
    private val port: Int = CONTROL_PORT,
    private val cameraProvider: () -> Camera2Controller,
    private val lensListProvider: () -> List<CameraLens>,
    private val currentLensProvider: () -> CameraLens,
    private val onSwitchLens: (CameraLens) -> Unit,
    private val onToggleTorch: (Boolean) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread(::run, "OpenStreamControlServer").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        worker = null
    }

    private fun run() {
        try {
            val socket = ServerSocket(port)
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
                handleClient(client)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Control server error", e)
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val writer = PrintWriter(client.getOutputStream(), true)

            // Parse request line
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                sendResponse(writer, 400, """{"error":"bad request"}""")
                return
            }
            val method = parts[0]
            val path = parts[1]

            // Read headers to get Content-Length
            var contentLength = 0
            var line = reader.readLine()
            while (line != null && line.isNotEmpty()) {
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
                line = reader.readLine()
            }

            // Read body if present
            val body = if (contentLength > 0) {
                val chars = CharArray(contentLength)
                reader.read(chars, 0, contentLength)
                String(chars)
            } else ""

            // Route request
            val response = when {
                method == "GET" && path == "/status" -> handleStatus()
                method == "POST" && path == "/zoom" -> handleZoom(body)
                method == "POST" && path == "/torch" -> handleTorch(body)
                method == "POST" && path == "/lens" -> handleLens(body)
                method == "OPTIONS" -> """{"ok":true}"""
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
            else -> "Error"
        }
        writer.print("HTTP/1.1 $code $status\r\n")
        writer.print("Content-Type: application/json\r\n")
        writer.print("Access-Control-Allow-Origin: *\r\n")
        writer.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        writer.print("Access-Control-Allow-Headers: Content-Type\r\n")
        writer.print("Content-Length: ${body.length}\r\n")
        writer.print("Connection: close\r\n")
        writer.print("\r\n")
        writer.print(body)
        writer.flush()
    }

    private fun handleStatus(): String {
        val camera = cameraProvider()
        val json = JSONObject()
            .put("zoom", camera.zoomRatio.toDouble())
            .put("zoomMin", camera.zoomRange.start.toDouble())
            .put("zoomMax", camera.zoomRange.endInclusive.toDouble())
            .put("currentLens", currentLensProvider().shortLabel)
            .put("availableLenses", lensListProvider().map { it.shortLabel })
        return json.toString()
    }

    private fun handleZoom(body: String): String {
        val json = JSONObject(body)
        val value = json.getDouble("value").toFloat()
        val applied = cameraProvider().setZoom(value)
        return """{"ok":true,"zoom":$applied}"""
    }

    private fun handleTorch(body: String): String {
        val json = JSONObject(body)
        val enabled = json.getBoolean("enabled")
        onToggleTorch(enabled)
        return """{"ok":true,"torch":$enabled}"""
    }

    private fun handleLens(body: String): String {
        val json = JSONObject(body)
        val lensLabel = json.getString("lens")
        val available = lensListProvider()
        val target = available.firstOrNull { it.shortLabel == lensLabel }
            ?: return """{"error":"lens not found","available":${available.map { "\"${it.shortLabel}\"" }}}"""
        onSwitchLens(target)
        return """{"ok":true,"lens":"${target.shortLabel}"}"""
    }

    companion object {
        private const val TAG = "OpenStreamControl"
        const val CONTROL_PORT = 9001
    }
}
