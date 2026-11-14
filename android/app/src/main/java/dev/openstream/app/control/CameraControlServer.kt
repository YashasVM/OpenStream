package dev.openstream.app.control

import android.util.Log
import dev.openstream.app.camera.Camera2Controller
import dev.openstream.app.camera.CameraLens
import dev.openstream.app.stream.StreamConfig
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal fun torchCommandAllowed(enabled: Boolean, torchAvailable: Boolean): Boolean =
    !enabled || torchAvailable

internal fun strictLong(json: JSONObject, key: String): Long = when (val value = json.get(key)) {
    is Int -> value.toLong()
    is Long -> value
    else -> throw IllegalArgumentException("integer required")
}

internal fun strictInt(json: JSONObject, key: String): Int {
    val value = strictLong(json, key)
    require(value in Int.MIN_VALUE..Int.MAX_VALUE)
    return value.toInt()
}

internal fun strictFloat(json: JSONObject, key: String): Float {
    val value = json.get(key)
    require(value is Number)
    return value.toFloat().also { require(it.isFinite()) }
}

internal fun strictBoolean(json: JSONObject, key: String): Boolean =
    (json.get(key) as? Boolean) ?: throw IllegalArgumentException("boolean required")


/**
 * Lightweight HTTP control server that accepts camera control commands from OBS.
 * Runs on port 9101 by default. Provides endpoints:
 *
 * - POST /zoom       {"value": 2.5}
 * - POST /torch      {"enabled": true}
 * - POST /lens       {"lens": "Back"}
 * - POST /camera     {"command":"exposure","value":2} (supported controls in /status)
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
    private val onNegotiate: (JSONObject?, String, Int?) -> Boolean = { _, _, _ -> true },
    private val onReservationCommitted: () -> Unit = {},
    private val sessionProvider: () -> JSONObject = { JSONObject() },
    private val onRelease: (String) -> Boolean,
    private val onIdentify: (String, String) -> Unit,
    private val onError: ((String) -> Unit)? = null,
) {
    private val running = AtomicBoolean(false)
    private val pendingRestart = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val clientLock = Any()
    private var desiredRunning = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var activeClient: Socket? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var activeReservationToken: String? = null
    @Volatile private var previousReservationToken: String? = null
    @Volatile private var previousControllerAddress: String? = null
    @Volatile private var lastRebindControllerAddress: String? = null
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
        worker = Thread(::run, "shinControlServer").apply {
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
            synchronized(clientLock) { runCatching { activeClient?.close() } }
            worker.also { it?.interrupt() }
        }
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(STOP_TIMEOUT_MS) }
                .onFailure { Thread.currentThread().interrupt() }
        }
        synchronized(lifecycleLock) {
            if (worker === thread && thread?.isAlive != true) worker = null
            serverSocket = null
            synchronized(clientLock) { activeClient = null }
        }
    }

    private fun run() {
        var openedSocket: ServerSocket? = null
        try {
            val socket = try {
                ServerSocket(port)
            } catch (error: Exception) {
                Log.e(TAG, "Control server failed to bind port $port", error)
                onError?.invoke("Control port $port unavailable: ${error.message ?: "bind failed"}")
                return
            }
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
                synchronized(clientLock) { activeClient = client }
                try {
                    handleClient(client)
                } finally {
                    synchronized(clientLock) {
                        if (activeClient === client) activeClient = null
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) {
                Log.e(TAG, "Control server error", e)
                onError?.invoke("Control server error: ${e.message ?: "unknown"}")
            }
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
            val localAddress = client.localAddress?.hostAddress.orEmpty()

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
                "/zoom", "/torch", "/lens", "/reserve", "/release", "/identify", "/camera" -> true
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
            // Handlers return (HTTP status, JSON body) so peer/token failures
            // surface as 401/403/409 instead of always 200. Error bodies use
            // the envelope {"ok":false,"error":...} plus the legacy
            // unauthorized/busy/stale flags for compatibility.
            val (statusCode, response) = when {
                method == "GET" && path == "/status" -> 200 to handleStatus()
                method == "POST" && path == "/camera" -> handleCamera(body, controllerAddress)
                method == "POST" && path == "/zoom" -> handleZoom(body, controllerAddress)
                method == "POST" && path == "/torch" -> handleTorch(body, controllerAddress)
                method == "POST" && path == "/lens" -> handleLens(body, controllerAddress)
                method == "POST" && path == "/reserve" -> handleReserve(body, controllerAddress, localAddress)
                method == "POST" && path == "/release" -> handleRelease(body, controllerAddress)
                method == "POST" && path == "/identify" -> handleIdentify(body, controllerAddress)
                else -> {
                    sendResponse(writer, 404, """{"ok":false,"error":"not found"}""")
                    return
                }
            }
            sendResponse(writer, statusCode, response)
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
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
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
            .put("cameraControls", cameraControlsJson(camera))
        return json.toString()
    }

    private fun cameraControlsJson(camera: Camera2Controller): JSONObject {
        val caps = camera.controlCapabilities
        val state = camera.controlState
        return JSONObject()
            .put("exposureMin", caps.exposureMin).put("exposureMax", caps.exposureMax)
            .put("exposureStep", caps.exposureStep.toDouble())
            .put("aeLock", caps.aeLock).put("awbLock", caps.awbLock)
            .put("focusLock", caps.focusLock).put("tapFocus", caps.tapFocus)
            .put("exposureTimeMinNs", caps.exposureTimeMinNs).put("exposureTimeMaxNs", caps.exposureTimeMaxNs)
            .put("isoMin", caps.isoMin).put("isoMax", caps.isoMax)
            .put("focusMaxDiopters", caps.focusMaxDiopters.toDouble())
            .put("whiteBalanceModes", JSONArray(caps.whiteBalanceModes))
            .put("exposureCompensation", state.exposureCompensation)
            .put("aeLocked", state.aeLocked).put("awbLocked", state.awbLocked)
            .put("focusLocked", state.focusLocked)
            .put("exposureTimeNs", state.exposureTimeNs ?: JSONObject.NULL)
            .put("sensitivityIso", state.sensitivityIso ?: JSONObject.NULL)
            .put("focusDiopters", state.focusDiopters?.toDouble() ?: JSONObject.NULL)
            .put("whiteBalanceMode", state.whiteBalanceMode)
    }

    private fun handleCamera(body: String, controllerAddress: String): Pair<Int, String> {
        if (!isAuthorizedController(controllerAddress)) return 401 to unauthorizedControlResponse()
        val json = parseJson(body) ?: return malformedResponse("camera")
        val camera = cameraProvider()
        val accepted = try {
            when (json.getString("command")) {
                "exposure" -> camera.setExposureCompensation(strictInt(json, "value"))
                "aeLock" -> camera.setAeLock(strictBoolean(json, "enabled"))
                "awbLock" -> camera.setAwbLock(strictBoolean(json, "enabled"))
                "focusLock" -> camera.setFocusLock(strictBoolean(json, "enabled"))
                "focus" -> camera.focusAt(strictFloat(json, "x"), strictFloat(json, "y"))
                "manualExposure" -> camera.setManualExposure(
                    if (json.isNull("exposureTimeNs")) null else strictLong(json, "exposureTimeNs"),
                    if (json.isNull("sensitivityIso")) null else strictInt(json, "sensitivityIso"),
                )
                "manualFocus" -> camera.setManualFocus(if (json.isNull("diopters")) null else strictFloat(json, "diopters"))
                "whiteBalance" -> camera.setWhiteBalance(strictInt(json, "mode"))
                "reset" -> { camera.resetControls(); true }
                else -> return malformedResponse("camera")
            }
        } catch (_: IllegalArgumentException) {
            return malformedResponse("camera")
        } catch (_: JSONException) {
            return malformedResponse("camera")
        }
        return (if (accepted) 200 else 409) to JSONObject().put("ok", accepted)
            .put("cameraControls", cameraControlsJson(camera))
            .apply { if (!accepted) put("error", "unsupported control or value outside current lens range") }
            .toString()
    }

    private fun isAuthorizedController(controllerAddress: String): Boolean {
        return reservationProvider() != null &&
            controllerAddress.isNotEmpty() &&
            controllerAddress == activeControllerAddress
    }

    internal fun isPeerAuthorized(controllerAddress: String): Boolean =
        isAuthorizedController(controllerAddress)

    internal fun handleReserveRequest(
        body: String,
        controllerAddress: String,
        localAddress: String = "",
    ): Pair<Int, String> = handleReserve(body, controllerAddress, localAddress)

    private fun hasValidRebindProof(
        activeToken: String?,
        localAddress: String,
        nextToken: String?,
        providedProof: String?,
    ): Boolean {
        if (activeToken == null || activeToken.length < MIN_REBIND_TOKEN_CHARS ||
            nextToken == null || nextToken.length < MIN_REBIND_TOKEN_CHARS ||
            nextToken == activeToken ||
            localAddress.isEmpty() || providedProof.isNullOrEmpty()
        ) {
            return false
        }
        val expected = computeRebindProof(activeToken, localAddress, nextToken)
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            providedProof.lowercase().toByteArray(Charsets.UTF_8),
        )
    }

    private fun unauthorizedControlResponse(): String =
        """{"ok":false,"unauthorized":true,"error":"unauthorized"}"""

    private fun busyReservationResponse(currentReservation: String): String = JSONObject()
        .put("ok", false)
        .put("busy", true)
        .put("reservedBy", currentReservation)
        .put("error", "busy")
        .toString()

    /** Parses a control-request body; null means malformed JSON (caller replies 400). */
    private fun parseJson(body: String): JSONObject? {
        return try {
            JSONObject(body)
        } catch (_: JSONException) {
            null
        }
    }

    private fun malformedResponse(name: String): Pair<Int, String> =
        400 to """{"ok":false,"error":"malformed $name request"}"""

    private fun handleZoom(body: String, controllerAddress: String): Pair<Int, String> {
        if (!isAuthorizedController(controllerAddress)) return unauthorizedControlResponse().let { 401 to it }
        // Malformed JSON must return 400 with an error envelope, never hang
        // or close the connection without a response (bounded queue rule:
        // every request gets exactly one bounded response).
        val value = parseJson(body)?.let { json ->
            runCatching { json.getDouble("value").toFloat() }.getOrNull()
        } ?: return malformedResponse("zoom")
        val applied = cameraProvider().setZoom(value)
        return 200 to """{"ok":true,"zoom":$applied}"""
    }

    private fun handleTorch(body: String, controllerAddress: String): Pair<Int, String> {
        if (!isAuthorizedController(controllerAddress)) return unauthorizedControlResponse().let { 401 to it }
        val enabled = parseJson(body)?.let { json ->
            runCatching { json.getBoolean("enabled") }.getOrNull()
        } ?: return malformedResponse("torch")
        onToggleTorch(enabled)
        return 200 to """{"ok":true,"torch":$enabled}"""
    }

    private fun handleLens(body: String, controllerAddress: String): Pair<Int, String> {
        if (!isAuthorizedController(controllerAddress)) return unauthorizedControlResponse().let { 401 to it }
        val lensLabel = parseJson(body)?.let { json ->
            runCatching { json.getString("lens") }.getOrNull()
        } ?: return malformedResponse("lens")
        val available = lensListProvider()
        val target = available.firstOrNull { it.shortLabel == lensLabel }
            ?: return 404 to """{"ok":false,"error":"lens not found","available":${available.map { "\"${it.shortLabel}\"" }}}"""
        onSwitchLens(target)
        return 200 to """{"ok":true,"lens":"${target.shortLabel}"}"""
    }

    private fun handleReserve(
        body: String,
        controllerAddress: String,
        localAddress: String = "",
    ): Pair<Int, String> {
        val json = parseJson(body) ?: return malformedResponse("reserve")
        val sourceInstanceId = json.optString("sourceInstanceId").trim()
        if (sourceInstanceId.isEmpty()) return 400 to """{"ok":false,"error":"missing sourceInstanceId"}"""
        val reservationToken = json.optString("reservationToken").trim().ifEmpty { null }
        if (reservationToken != null && reservationToken.length > MAX_RESERVATION_TOKEN_CHARS) {
            return 400 to """{"ok":false,"error":"reservation token too large"}"""
        }
        val rebindProof = json.optString("rebindProof").trim().ifEmpty { null }
        val currentReservation = reservationProvider()
        if (currentReservation != null && currentReservation != sourceInstanceId) {
            return busyReservationResponse(currentReservation).let { 403 to it }
        }
        val currentControllerAddress = activeControllerAddress
        // UI-made (local-only) reservations leave currentControllerAddress == null
        // until the first network reserve binds the peer. Allow the legitimate
        // OBS host claiming the same sourceInstanceId to adopt/bind here instead
        // of failing closed and leaving the reservation sticky. Also allow the
        // same OBS host to rebind from a new address (e.g. USB unplug -> Wi-Fi
        // reconnect) after proving ownership with a SHA-256 proof bound to this
        // socket's local IP and a fresh rotated token so a spoofed LAN peer can
        // never steal or replay the active reservation token.
        val isUnboundLocalReservation = currentControllerAddress == null
        val matchedActiveRebind = controllerAddress.isNotEmpty() &&
            hasValidRebindProof(activeReservationToken, localAddress, reservationToken, rebindProof)
        val isKnownRebindRetryPeer = controllerAddress.isNotEmpty() &&
            (controllerAddress == lastRebindControllerAddress ||
                controllerAddress == previousControllerAddress)
        val matchedRetryRebind = !matchedActiveRebind &&
            isKnownRebindRetryPeer &&
            hasValidRebindProof(previousReservationToken, localAddress, reservationToken, rebindProof)
        val provedReservationOwnership = matchedActiveRebind || matchedRetryRebind
        if (currentReservation == sourceInstanceId &&
            !isUnboundLocalReservation &&
            !provedReservationOwnership &&
            (controllerAddress.isEmpty() ||
                controllerAddress != currentControllerAddress)
        ) {
            return unauthorizedControlResponse().let { 401 to it }
        }
        val rebindAck = when {
            matchedActiveRebind -> computeRebindAck(
                activeReservationToken!!,
                localAddress,
                reservationToken!!,
            )
            matchedRetryRebind -> computeRebindAck(
                previousReservationToken!!,
                localAddress,
                reservationToken!!,
            )
            else -> null
        }
        val slotLabel = json.optString("slotLabel", "")
        val bitrateMbps = if (json.has("bitrateMbps")) {
            // Strict validation: only plain integers are accepted. The previous
            // optInt() path coerced booleans, strings, and doubles to 0 and then
            // to 8 Mbps silently; non-integers are now a 400.
            val rawBitrate = json.opt("bitrateMbps")
            val intBitrate = when (rawBitrate) {
                is Int -> rawBitrate
                is Long -> if (rawBitrate in Int.MIN_VALUE..Int.MAX_VALUE) rawBitrate.toInt() else null
                else -> null
            } ?: return 400 to """{"ok":false,"error":"invalid bitrateMbps"}"""
            intBitrate.coerceIn(
                StreamConfig.MIN_BITRATE_MBPS,
                StreamConfig.MAX_BITRATE_MBPS,
            )
        } else null
        if (!onNegotiate(json.optJSONObject("receiverCapabilities"), controllerAddress, bitrateMbps)) {
            return 409 to JSONObject().put("ok", false).put("error", "Selected mode requires compatible receiver, camera and verified USB peer").toString()
        }
        val sameReservationConfig = currentReservation == sourceInstanceId &&
            activeReservationSlotLabel == slotLabel &&
            activeReservationBitrateMbps == bitrateMbps
        if (sameReservationConfig) {
            if (matchedActiveRebind) {
                previousReservationToken = activeReservationToken
                previousControllerAddress = currentControllerAddress
                lastRebindControllerAddress = controllerAddress
            } else if (!matchedRetryRebind) {
                previousReservationToken = null
                previousControllerAddress = null
                lastRebindControllerAddress = null
            }
            activeReservationToken = reservationToken
            activeControllerAddress = controllerAddress.ifEmpty { null }
            onReservationCommitted()
            return 200 to JSONObject()
                .put("ok", true)
                .put("reservedBy", sourceInstanceId)
                .apply { if (rebindAck != null) put("rebindAck", rebindAck) }
                .put("session", sessionProvider())
                .toString()
        }
        val accepted = onReserve(sourceInstanceId, slotLabel, bitrateMbps)
        return if (accepted) {
            if (matchedActiveRebind) {
                previousReservationToken = activeReservationToken
                previousControllerAddress = currentControllerAddress
                lastRebindControllerAddress = controllerAddress
            } else if (!matchedRetryRebind) {
                previousReservationToken = null
                previousControllerAddress = null
                lastRebindControllerAddress = null
            }
            activeReservationToken = reservationToken
            activeControllerAddress = controllerAddress.ifEmpty { null }
            activeReservationSlotLabel = slotLabel
            activeReservationBitrateMbps = bitrateMbps
            onReservationCommitted()
            200 to JSONObject()
                .put("ok", true)
                .put("reservedBy", sourceInstanceId)
                .apply { if (rebindAck != null) put("rebindAck", rebindAck) }
                .put("session", sessionProvider())
                .toString()
        } else {
            busyReservationResponse(reservationProvider().orEmpty()).let { 403 to it }
        }
    }

    private fun handleRelease(body: String, controllerAddress: String): Pair<Int, String> {
        val json = parseJson(body) ?: return malformedResponse("release")
        val sourceInstanceId = json.optString("sourceInstanceId").trim()
        if (sourceInstanceId.isEmpty()) return 400 to """{"ok":false,"error":"missing sourceInstanceId"}"""
        val reservationToken = json.optString("reservationToken").trim().ifEmpty { null }
        val currentReservation = reservationProvider()
        // Same local-only adoption rule as reserve: an unbound reservation
        // (activeControllerAddress == null, made from the phone UI) may be
        // released by the matching sourceInstanceId so it cannot stick forever.
        // Bound reservations still require the matching controller peer first.
        val unboundLocalRelease = activeControllerAddress == null
        if (currentReservation == sourceInstanceId &&
            !unboundLocalRelease &&
            (controllerAddress.isEmpty() ||
                controllerAddress != activeControllerAddress)
        ) {
            return unauthorizedControlResponse().let { 401 to it }
        }
        if (currentReservation == sourceInstanceId &&
            activeReservationToken != null &&
            reservationToken != activeReservationToken
        ) {
            return 409 to """{"ok":false,"stale":true,"error":"stale reservation token"}"""
        }
        val released = onRelease(sourceInstanceId)
        if (released && reservationProvider() != sourceInstanceId) {
            activeReservationToken = null
            activeControllerAddress = null
            activeReservationSlotLabel = null
            activeReservationBitrateMbps = null
            previousReservationToken = null
            previousControllerAddress = null
            lastRebindControllerAddress = null
        }
        return 200 to """{"ok":$released}"""
    }

    private fun handleIdentify(body: String, controllerAddress: String): Pair<Int, String> {
        if (!isAuthorizedController(controllerAddress)) return unauthorizedControlResponse().let { 401 to it }
        val json = parseJson(body) ?: return malformedResponse("identify")
        val label = json.optString("label", "CAM").ifBlank { "CAM" }
        val subtitle = json.optString("subtitle", "")
        onIdentify(label, subtitle)
        return 200 to """{"ok":true}"""
    }

    companion object {
        private const val TAG = "shinControl"
        const val CONTROL_PORT = 9101
        private const val MAX_REQUEST_LINE_BYTES = 2_048
        private const val MAX_HEADER_LINE_BYTES = 2_048
        private const val MAX_HEADER_BYTES = 8_192
        private const val MAX_BODY_BYTES = 8_192
        private const val MIN_REBIND_TOKEN_CHARS = 32
        private const val MAX_RESERVATION_TOKEN_CHARS = 256
        private const val STOP_TIMEOUT_MS = 1_000L

        internal fun computeRebindProof(
            activeToken: String,
            localAddress: String,
            nextToken: String,
        ): String = sha256Hex("$activeToken|$localAddress|$nextToken")

        internal fun computeRebindAck(
            activeToken: String,
            localAddress: String,
            nextToken: String,
        ): String = sha256Hex("$activeToken|ack|$localAddress|$nextToken")

        private fun sha256Hex(material: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(material.toByteArray(Charsets.UTF_8))
            return buildString(digest.size * 2) {
                for (byte in digest) {
                    append("%02x".format(byte.toInt() and 0xff))
                }
            }
        }
    }
}
