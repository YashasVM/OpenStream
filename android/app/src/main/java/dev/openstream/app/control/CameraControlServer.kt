package dev.openstream.app.control

import android.util.Log
import dev.openstream.app.camera.AuthorityMode
import dev.openstream.app.camera.Camera2Controller
import dev.openstream.app.camera.CameraActor
import dev.openstream.app.camera.CameraCapabilities
import dev.openstream.app.camera.CameraControlResult
import dev.openstream.app.camera.CameraLens
import dev.openstream.app.camera.CameraSettings
import dev.openstream.app.camera.CameraSettingsPatch
import dev.openstream.app.camera.CameraState
import dev.openstream.app.camera.CameraTelemetry
import dev.openstream.app.camera.ExposureMode
import dev.openstream.app.camera.FocusActionMode
import dev.openstream.app.camera.FocusMode
import dev.openstream.app.camera.StabilizationMode
import dev.openstream.app.camera.WhiteBalanceMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** Lightweight HTTP/JSON control plane. V2 is authenticated; legacy routes are bootstrap-only. */
class CameraControlServer(
    private val pairingTokenStore: PairingTokenStore,
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
    private val onPaired: () -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread(::run, "OpenStreamControlServer").apply { isDaemon = true; start() }
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
            socket.soTimeout = 1_000
            Log.i(TAG, "Camera control server listening on port $port")
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (error: java.net.SocketException) {
                    if (running.get()) Log.w(TAG, "Socket error", error)
                    break
                }
                handleClient(client)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Control server error", error)
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 5_000
            val input = BufferedInputStream(client.getInputStream())
            val writer = PrintWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8), false)
            val requestLine = readAsciiLine(input, MAX_REQUEST_LINE_BYTES)
                ?: return sendResponse(writer, HttpResponse(400, errorJson("bad_request", "Missing request line")))
            val parts = requestLine.split(" ")
            if (parts.size < 2) return sendResponse(writer, HttpResponse(400, errorJson("bad_request", "Malformed request line")))
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            var contentLength = 0
            var headerBytes = 0
            val headers = linkedMapOf<String, String>()
            var line = readAsciiLine(input, MAX_HEADER_LINE_BYTES)
            while (line != null && line.isNotEmpty()) {
                headerBytes += line.toByteArray(Charsets.US_ASCII).size + 2
                if (headerBytes > MAX_HEADER_BYTES) {
                    return sendResponse(writer, HttpResponse(413, errorJson("headers_too_large", "Headers exceed limit")))
                }
                val separator = line.indexOf(':')
                if (separator > 0) {
                    val name = line.substring(0, separator).trim().lowercase()
                    val value = line.substring(separator + 1).trim()
                    headers[name] = value
                    if (name == "content-length") contentLength = value.toIntOrNull() ?: -1
                }
                line = readAsciiLine(input, MAX_HEADER_LINE_BYTES)
            }
            if (line == null || contentLength !in 0..MAX_BODY_BYTES) {
                val tooLarge = contentLength > MAX_BODY_BYTES
                return sendResponse(
                    writer,
                    HttpResponse(
                        if (tooLarge) 413 else 400,
                        errorJson(if (tooLarge) "request_too_large" else "bad_request", "Invalid request body length"),
                    ),
                )
            }
            val body = if (contentLength == 0) "" else readBody(input, contentLength)
                ?: return sendResponse(writer, HttpResponse(400, errorJson("incomplete_request", "Request body is incomplete")))
            val response = route(method, path, headers, body)
            sendResponse(writer, response)
        } catch (error: Exception) {
            Log.w(TAG, "Error handling control request", error)
        } finally {
            runCatching { client.close() }
        }
    }

    private fun route(method: String, path: String, headers: Map<String, String>, body: String): HttpResponse {
        if (method == "OPTIONS") return HttpResponse(200, JSONObject().put("ok", true).toString())
        if (method == "POST" && path == "/v2/pair") return handleV2Pair(body)
        if (path.startsWith("/v2/") && !pairingTokenStore.validateBearer(headers["authorization"])) {
            return HttpResponse(401, errorJson("unauthorized", "A valid bearer token is required"))
        }
        if (!path.startsWith("/v2/") && pairingTokenStore.hasPairedAdministrator() &&
            !pairingTokenStore.validateBearer(headers["authorization"])
        ) {
            return HttpResponse(401, errorJson("unauthorized", "This camera is paired; authenticate legacy requests"))
        }
        return try {
            when {
                method == "GET" && path == "/v2/capabilities" -> handleV2Capabilities()
                method == "GET" && path == "/v2/state" -> HttpResponse(200, stateJson(cameraProvider().currentState()).toString())
                method == "POST" && path == "/v2/settings" -> handleV2Settings(body)
                method == "POST" && path == "/v2/focus" -> handleV2Focus(body)
                method == "POST" && path == "/v2/authority" -> handleV2Authority(body)
                method == "POST" && path == "/v2/tally" -> handleV2Tally(body)
                method == "GET" && path == "/status" -> HttpResponse(200, handleStatus())
                method == "POST" && path == "/zoom" -> HttpResponse(200, handleZoom(body))
                method == "POST" && path == "/torch" -> HttpResponse(200, handleTorch(body))
                method == "POST" && path == "/lens" -> HttpResponse(200, handleLens(body))
                method == "POST" && path == "/reserve" -> HttpResponse(200, handleReserve(body))
                method == "POST" && path == "/release" -> HttpResponse(200, handleRelease(body))
                method == "POST" && path == "/identify" -> HttpResponse(200, handleIdentify(body))
                else -> HttpResponse(404, errorJson("not_found", "Endpoint not found"))
            }
        } catch (error: IllegalArgumentException) {
            HttpResponse(400, errorJson("invalid_request", error.message ?: "Invalid request"))
        } catch (error: org.json.JSONException) {
            HttpResponse(400, errorJson("invalid_json", error.message ?: "Invalid JSON"))
        }
    }

    private fun handleV2Pair(body: String): HttpResponse {
        val json = parseObject(body)
        return when (val result = pairingTokenStore.pair(
            sourceInstanceId = json.optString("sourceInstanceId"),
            sourceName = json.optString("sourceName"),
            suppliedCode = json.optString("pairingCode").takeIf { json.has("pairingCode") },
        )) {
            is PairingTokenStore.PairingResult.Paired -> {
                onPaired()
                HttpResponse(
                    200,
                    JSONObject().put("ok", true).put("token", result.token).put("protocolVersion", PROTOCOL_VERSION).toString(),
                )
            }
            is PairingTokenStore.PairingResult.Invalid -> HttpResponse(400, errorJson("invalid_request", result.reason))
            PairingTokenStore.PairingResult.CodeRejected -> HttpResponse(401, errorJson("pairing_code_rejected", "Pairing code is invalid or expired"))
        }
    }

    private fun handleV2Capabilities(): HttpResponse {
        val caps = cameraProvider().currentCapabilities()
            ?: return HttpResponse(503, errorJson("camera_not_ready", "Camera capabilities are not available yet"))
        return HttpResponse(200, capabilitiesJson(caps).toString())
    }

    private fun handleV2Settings(body: String): HttpResponse {
        val json = parseObject(body)
        val expectedRevision = requiredLong(json, "expectedRevision")
        val settings = json.optJSONObject("settings") ?: throw IllegalArgumentException("settings object is required")
        val patch = CameraSettingsPatch(
            exposureMode = enumValue(settings, "exposureMode", ExposureMode::fromWire),
            iso = optionalInt(settings, "iso"),
            shutterNs = optionalLong(settings, "shutterNs"),
            exposureCompensation = optionalInt(settings, "exposureCompensation"),
            whiteBalanceMode = enumValue(settings, "whiteBalanceMode", WhiteBalanceMode::fromWire),
            whiteBalanceKelvin = optionalInt(settings, "whiteBalanceKelvin"),
            whiteBalanceTint = optionalInt(settings, "whiteBalanceTint"),
            whiteBalanceLock = optionalBoolean(settings, "whiteBalanceLock"),
            focusMode = enumValue(settings, "focusMode", FocusMode::fromWire),
            focusDistanceDiopters = optionalFloat(settings, "focusDistanceDiopters"),
            zoomRatio = optionalFloat(settings, "zoomRatio"),
            torch = optionalBoolean(settings, "torch"),
            stabilizationMode = enumValue(settings, "stabilizationMode", StabilizationMode::fromWire),
            fps = optionalInt(settings, "fps"),
        )
        return controlResponse(cameraProvider().applySettings(patch, expectedRevision, CameraActor.Obs))
    }

    private fun handleV2Focus(body: String): HttpResponse {
        val json = parseObject(body)
        val expectedRevision = requiredLong(json, "expectedRevision")
        val x = requiredFloat(json, "x")
        val y = requiredFloat(json, "y")
        val modeText = json.optString("mode", FocusActionMode.Auto.wireValue)
        val mode = FocusActionMode.fromWire(modeText) ?: throw IllegalArgumentException("Unsupported focus mode: $modeText")
        return controlResponse(cameraProvider().focusAt(x, y, mode, expectedRevision, CameraActor.Obs))
    }

    private fun handleV2Authority(body: String): HttpResponse {
        val json = parseObject(body)
        val expectedRevision = requiredLong(json, "expectedRevision")
        val modeText = json.optString("mode")
        val mode = AuthorityMode.fromWire(modeText) ?: throw IllegalArgumentException("Unsupported authority mode: $modeText")
        return controlResponse(cameraProvider().setAuthority(mode, expectedRevision, CameraActor.Obs))
    }

    private fun handleV2Tally(body: String): HttpResponse {
        val json = parseObject(body)
        if (!json.has("program") || !json.has("preview")) throw IllegalArgumentException("program and preview are required")
        val state = cameraProvider().setTally(json.getBoolean("program"), json.getBoolean("preview"))
        return HttpResponse(200, JSONObject().put("ok", true).put("state", stateJson(state)).toString())
    }

    private fun controlResponse(result: CameraControlResult): HttpResponse = when (result) {
        is CameraControlResult.Applied -> HttpResponse(200, JSONObject().put("ok", true).put("state", stateJson(result.state)).toString())
        is CameraControlResult.Conflict -> HttpResponse(409, JSONObject().put("ok", false).put("error", "revision_conflict").put("state", stateJson(result.state)).toString())
        is CameraControlResult.Unsupported -> HttpResponse(422, controlError("unsupported", result.field, result.reason, result.state))
        is CameraControlResult.Invalid -> HttpResponse(400, controlError("invalid_value", result.field, result.reason, result.state))
        is CameraControlResult.Locked -> HttpResponse(423, JSONObject().put("ok", false).put("error", "obs_locked").put("state", stateJson(result.state)).toString())
    }

    private fun controlError(code: String, field: String, reason: String, state: CameraState): String = JSONObject()
        .put("ok", false).put("error", code).put("field", field).put("message", reason).put("state", stateJson(state)).toString()

    private fun capabilitiesJson(caps: CameraCapabilities): JSONObject = JSONObject()
        .put("protocolVersion", PROTOCOL_VERSION)
        .put("cameraId", caps.cameraId)
        .put("displayName", caps.displayName)
        .put("lensFacing", caps.lensFacing)
        .put("logicalMultiCamera", caps.logicalMultiCamera)
        .put("physicalCameraIds", JSONArray(caps.physicalCameraIds))
        .put("manualSensor", caps.manualSensor)
        .put("manualWhiteBalance", caps.manualWhiteBalance)
        .put("supportsAwbLock", caps.supportsAwbLock)
        .put("supportsTapFocus", caps.supportsTapFocus)
        .put("supportsAeRegions", caps.supportsAeRegions)
        .put("supportsTorch", caps.supportsTorch)
        .put("supportsZoomRatio", caps.supportsZoomRatio)
        .put("isoRange", caps.isoRange?.let { rangeJson(it.min, it.max) } ?: JSONObject.NULL)
        .put("shutterRangeNs", caps.shutterRangeNs?.let { rangeJson(it.min, it.max) } ?: JSONObject.NULL)
        .put("exposureCompensationRange", caps.exposureCompensationRange?.let { rangeJson(it.min, it.max) } ?: JSONObject.NULL)
        .put("focusDistanceRange", caps.focusDistanceRange?.let { rangeJson(it.min, it.max) } ?: JSONObject.NULL)
        .put("zoomRange", rangeJson(caps.zoomRange.min, caps.zoomRange.max))
        .put("fpsRanges", JSONArray(caps.fpsRanges.map { rangeJson(it.min, it.max) }))
        .put("focusModes", JSONArray(caps.focusModes.map { it.wireValue }.sorted()))
        .put("whiteBalanceModes", JSONArray(caps.whiteBalanceModes.map { it.wireValue }.sorted()))
        .put("stabilizationModes", JSONArray(caps.stabilizationModes.map { it.wireValue }.sorted()))

    private fun stateJson(state: CameraState): JSONObject = JSONObject()
        .put("protocolVersion", PROTOCOL_VERSION)
        .put("revision", state.revision)
        .put("lastActor", state.lastActor.wireValue)
        .put("authority", state.authority.wireValue)
        .put("tally", JSONObject().put("program", state.tally.program).put("preview", state.tally.preview))
        .put("settings", settingsJson(state.settings))
        .put("telemetry", telemetryJson(state.telemetry))

    private fun settingsJson(value: CameraSettings): JSONObject = JSONObject()
        .put("exposureMode", value.exposureMode.wireValue)
        .putNullable("iso", value.iso)
        .putNullable("shutterNs", value.shutterNs)
        .put("exposureCompensation", value.exposureCompensation)
        .put("whiteBalanceMode", value.whiteBalanceMode.wireValue)
        .putNullable("whiteBalanceKelvin", value.whiteBalanceKelvin)
        .put("whiteBalanceTint", value.whiteBalanceTint)
        .put("whiteBalanceLock", value.whiteBalanceLock)
        .put("focusMode", value.focusMode.wireValue)
        .putNullable("focusDistanceDiopters", value.focusDistanceDiopters)
        .put("zoomRatio", value.zoomRatio.toDouble())
        .put("torch", value.torch)
        .put("stabilizationMode", value.stabilizationMode.wireValue)
        .putNullable("fps", value.fps)

    private fun telemetryJson(value: CameraTelemetry): JSONObject = JSONObject()
        .putNullable("actualIso", value.actualIso)
        .putNullable("actualShutterNs", value.actualShutterNs)
        .putNullable("actualFocusDistanceDiopters", value.actualFocusDistanceDiopters)
        .put("actualZoomRatio", value.actualZoomRatio.toDouble())
        .putNullable("actualWhiteBalanceKelvin", value.actualWhiteBalanceKelvin)
        .put("focusStatus", value.focusStatus.wireValue)
        .put("aeState", value.aeState)
        .put("awbState", value.awbState)
        .put("frameNumber", value.frameNumber)
        .put("timestampNs", value.timestampNs)

    private fun handleStatus(): String {
        val camera = cameraProvider()
        return JSONObject()
            .put("zoom", camera.zoomRatio.toDouble())
            .put("zoomMin", camera.zoomRange.start.toDouble())
            .put("zoomMax", camera.zoomRange.endInclusive.toDouble())
            .put("currentLens", currentLensProvider().shortLabel)
            .put("availableLenses", lensListProvider().map { it.shortLabel })
            .put("reservedBy", reservationProvider().orEmpty())
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("pairingRequired", true)
            .toString()
    }

    private fun handleZoom(body: String): String {
        val applied = cameraProvider().setZoom(parseObject(body).getDouble("value").toFloat())
        return JSONObject().put("ok", true).put("zoom", applied.toDouble()).toString()
    }

    private fun handleTorch(body: String): String {
        val enabled = parseObject(body).getBoolean("enabled")
        onToggleTorch(enabled)
        return JSONObject().put("ok", true).put("torch", enabled).toString()
    }

    private fun handleLens(body: String): String {
        val lensLabel = parseObject(body).getString("lens")
        val available = lensListProvider()
        val target = available.firstOrNull { it.shortLabel == lensLabel }
            ?: return JSONObject().put("error", "lens not found").put("available", available.map { it.shortLabel }).toString()
        onSwitchLens(target)
        return JSONObject().put("ok", true).put("lens", target.shortLabel).toString()
    }

    private fun handleReserve(body: String): String {
        val json = parseObject(body)
        val sourceInstanceId = json.optString("sourceInstanceId").trim()
        if (sourceInstanceId.isEmpty()) return errorJson("missing_source", "sourceInstanceId is required")
        val slotLabel = json.optString("slotLabel", "")
        val bitrateMbps = if (json.has("bitrateMbps")) json.optInt("bitrateMbps").coerceIn(1, 200) else null
        val accepted = onReserve(sourceInstanceId, slotLabel, bitrateMbps)
        return JSONObject().put("ok", accepted).put("busy", !accepted)
            .put("reservedBy", if (accepted) sourceInstanceId else reservationProvider().orEmpty()).toString()
    }

    private fun handleRelease(body: String): String {
        val sourceInstanceId = parseObject(body).optString("sourceInstanceId").trim()
        if (sourceInstanceId.isEmpty()) return errorJson("missing_source", "sourceInstanceId is required")
        return JSONObject().put("ok", onRelease(sourceInstanceId)).toString()
    }

    private fun handleIdentify(body: String): String {
        val json = parseObject(body)
        onIdentify(json.optString("label", "CAM").ifBlank { "CAM" }, json.optString("subtitle", ""))
        return JSONObject().put("ok", true).toString()
    }

    private fun parseObject(body: String): JSONObject {
        if (body.isBlank()) throw IllegalArgumentException("JSON body is required")
        return JSONObject(body)
    }

    private fun requiredLong(json: JSONObject, name: String): Long {
        if (!json.has(name)) throw IllegalArgumentException("$name is required")
        return json.getLong(name)
    }

    private fun requiredFloat(json: JSONObject, name: String): Float {
        if (!json.has(name)) throw IllegalArgumentException("$name is required")
        return json.getDouble(name).toFloat()
    }

    private fun optionalInt(json: JSONObject, name: String): Int? = if (json.has(name) && !json.isNull(name)) json.getInt(name) else null
    private fun optionalLong(json: JSONObject, name: String): Long? = if (json.has(name) && !json.isNull(name)) json.getLong(name) else null
    private fun optionalFloat(json: JSONObject, name: String): Float? = if (json.has(name) && !json.isNull(name)) json.getDouble(name).toFloat() else null
    private fun optionalBoolean(json: JSONObject, name: String): Boolean? = if (json.has(name) && !json.isNull(name)) json.getBoolean(name) else null

    private fun <T> enumValue(json: JSONObject, name: String, parser: (String) -> T?): T? {
        if (!json.has(name) || json.isNull(name)) return null
        val value = json.getString(name)
        return parser(value) ?: throw IllegalArgumentException("Unsupported $name: $value")
    }

    private fun rangeJson(min: Number, max: Number) = JSONObject().put("min", min).put("max", max)
    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject = put(name, value ?: JSONObject.NULL)

    private fun errorJson(code: String, message: String): String = JSONObject()
        .put("ok", false).put("error", code).put("message", message).toString()

    private fun readBody(input: BufferedInputStream, contentLength: Int): String? {
        val bytes = ByteArray(contentLength)
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            if (read < 0) return null
            offset += read
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun sendResponse(writer: PrintWriter, response: HttpResponse) {
        val status = when (response.code) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            409 -> "Conflict"
            413 -> "Payload Too Large"
            422 -> "Unprocessable Content"
            423 -> "Locked"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val bytes = response.body.toByteArray(Charsets.UTF_8)
        writer.print("HTTP/1.1 ${response.code} $status\r\n")
        writer.print("Content-Type: application/json\r\n")
        writer.print("Access-Control-Allow-Origin: *\r\n")
        writer.print("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        writer.print("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
        if (response.code == 401) writer.print("WWW-Authenticate: Bearer\r\n")
        writer.print("Content-Length: ${bytes.size}\r\n")
        writer.print("Connection: close\r\n\r\n")
        writer.print(response.body)
        writer.flush()
    }

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

    private data class HttpResponse(val code: Int, val body: String)

    companion object {
        private const val TAG = "OpenStreamControl"
        const val CONTROL_PORT = 9001
        const val PROTOCOL_VERSION = 2
        private const val MAX_REQUEST_LINE_BYTES = 2_048
        private const val MAX_HEADER_LINE_BYTES = 2_048
        private const val MAX_HEADER_BYTES = 8_192
        private const val MAX_BODY_BYTES = 16_384
    }
}
