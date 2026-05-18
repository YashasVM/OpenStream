package dev.openstream.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import dev.openstream.app.camera.Camera2Controller
import dev.openstream.app.camera.CameraLens
import dev.openstream.app.discovery.PhoneDiscoveryAdvertiser
import dev.openstream.app.encoder.MediaCodecVideoEncoder
import dev.openstream.app.stream.ConnectionTarget
import dev.openstream.app.stream.StreamConfig
import dev.openstream.app.stream.SrtStreamClient
import dev.openstream.app.telemetry.DeviceTelemetry
import dev.openstream.app.telemetry.TelemetrySampler

class MainActivity : Activity() {
    private lateinit var preview: SurfaceView
    private lateinit var status: TextView
    private lateinit var devicesContainer: LinearLayout
    private lateinit var manualContainer: LinearLayout
    private lateinit var obsHost: EditText
    private lateinit var obsPort: EditText
    private lateinit var latency: EditText
    private lateinit var lensSelector: Spinner
    private lateinit var targetUrl: TextView
    private lateinit var camera: Camera2Controller
    private lateinit var encoder: MediaCodecVideoEncoder
    private lateinit var streamClient: SrtStreamClient
    private lateinit var telemetry: TelemetrySampler
    private lateinit var phoneAdvertiser: PhoneDiscoveryAdvertiser
    private val streamConfig = StreamConfig.Default1080p30
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeTargetName: String? = null
    @Volatile private var phoneServerRunning = false
    @Volatile private var phoneConnected = false
    private var listenerThread: Thread? = null
    private val statsTicker = object : Runnable {
        override fun run() {
            renderStreamStats()
            if (activeTargetName != null) {
                mainHandler.postDelayed(this, 1_000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        buildUi()

        streamClient = SrtStreamClient()
        telemetry = TelemetrySampler(this)
        phoneAdvertiser = PhoneDiscoveryAdvertiser(
            context = this,
            config = streamConfig,
            port = ConnectionTarget.DEFAULT_PORT,
            busyProvider = { phoneConnected },
        )
        encoder = MediaCodecVideoEncoder(
            preference = streamConfig.codecPreference,
            width = streamConfig.width,
            height = streamConfig.height,
            fps = streamConfig.fps,
            bitrate = streamConfig.bitrate,
            keyframeIntervalSeconds = streamConfig.keyframeIntervalSeconds,
            onEncodedAccessUnit = { accessUnit ->
                val sent = streamClient.sendVideoAccessUnit(accessUnit)
                if (!sent) {
                    phoneConnected = false
                    runOnUiThread { renderStreamStats(forceFailure = true) }
                }
            },
        )
        camera = Camera2Controller(
            context = this,
            previewSurfaceProvider = { preview.holder.surface },
            lensProvider = { selectedLens() },
        )
        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                startPreviewIfAllowed()
                startPhoneServerIfAllowed()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = stopPhoneServer()
        })

        handlePairingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        phoneAdvertiser.start()
        startPhoneServerIfAllowed()
    }

    override fun onStop() {
        stopPhoneServer()
        camera.stop()
        phoneAdvertiser.stop()
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startPreviewIfAllowed()
            startPhoneServerIfAllowed()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingIntent(intent)
    }

    private fun buildUi() {
        preview = SurfaceView(this)
        status = TextView(this).apply {
            text = "Camera preview is ready. OBS can discover this phone on the same Wi-Fi."
        }
        devicesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        manualContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        obsHost = EditText(this).apply {
            hint = ConnectionTarget.DEFAULT_HOST
            setSingleLine(true)
        }
        obsPort = EditText(this).apply {
            hint = ConnectionTarget.DEFAULT_PORT.toString()
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        latency = EditText(this).apply {
            hint = ConnectionTarget.DEFAULT_LATENCY_MS.toString()
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        lensSelector = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                CameraLens.entries.map { it.displayName },
            )
        }
        targetUrl = TextView(this)

        val manualToggle = Button(this).apply {
            text = "Manual fallback"
            setOnClickListener {
                manualContainer.visibility =
                    if (manualContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        val manualStart = Button(this).apply {
            text = "Connect manually"
            setOnClickListener { startStream(connectionTargetFromManualFields()) }
        }
        val stop = Button(this).apply {
            text = "Stop"
            setOnClickListener { stopPhoneServer() }
        }

        manualContainer.addView(TextView(this).apply { text = "OBS PC IP" })
        manualContainer.addView(obsHost)
        manualContainer.addView(TextView(this).apply { text = "OBS listener port" })
        manualContainer.addView(obsPort)
        manualContainer.addView(TextView(this).apply { text = "Latency ms" })
        manualContainer.addView(latency)
        manualContainer.addView(manualStart)
        renderReadyState()

        setContentView(
            ScrollView(this).apply {
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(
                            preview,
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                resources.displayMetrics.heightPixels / 3,
                            ),
                        )
                        addView(TextView(this@MainActivity).apply { text = "OBS workflow" })
                        addView(devicesContainer)
                        addView(TextView(this@MainActivity).apply { text = "Camera" })
                        addView(lensSelector)
                        addView(targetUrl)
                        addView(manualToggle)
                        addView(manualContainer)
                        addView(stop)
                        addView(status)
                    },
                )
            },
        )
    }

    private fun renderReadyState() {
        devicesContainer.removeAllViews()
        devicesContainer.addView(TextView(this).apply {
            text = "Open OBS, add OpenStream Phone, choose this phone, then click Connect."
        })
        devicesContainer.addView(TextView(this).apply {
            text = "Phone SRT listener: port ${ConnectionTarget.DEFAULT_PORT}, ${streamConfig.width}x${streamConfig.height}@${streamConfig.fps}, ${streamConfig.bitrateMbps} Mbps"
        })
    }

    private fun handlePairingIntent(intent: Intent?) {
        val uri: Uri = intent?.data ?: return
        val target = ConnectionTarget.fromPairingUri(uri) ?: return
        startStream(target)
    }

    private fun startStream(target: ConnectionTarget) {
        stopStream(updateStatus = false)
        val url = target.toSrtCallerUrl()
        targetUrl.text = url
        status.text = "Connecting ${selectedLens().displayName} camera to ${target.name}"
        runCatching {
            streamClient.connect(
                url = url,
                codecMime = encoder.codecName,
                width = streamConfig.width,
                height = streamConfig.height,
                fps = streamConfig.fps,
            )
            encoder.start()
            camera.startStreaming(encoder.inputSurface())
            activeTargetName = target.name
            mainHandler.removeCallbacks(statsTicker)
            mainHandler.post(statsTicker)
            val sample: DeviceTelemetry = telemetry.sample(
                streamUrl = url,
                codec = encoder.codecName,
                width = streamConfig.width,
                height = streamConfig.height,
                fps = streamConfig.fps,
                bitrate = streamConfig.bitrate,
            )
            status.text = "Starting video: ${target.name}, ${sample.codec} ${sample.width}x${sample.height}@${sample.fps}"
        }.onFailure { error ->
            stopStream()
            status.text = "Could not start camera feed: ${error.message ?: "unknown error"}"
        }
    }

    private fun startPhoneServerIfAllowed() {
        if (phoneServerRunning) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (!preview.holder.surface.isValid) return

        phoneServerRunning = true
        phoneConnected = false
        activeTargetName = null
        val listenUrl = "srt://0.0.0.0:${ConnectionTarget.DEFAULT_PORT}?mode=listener&latency=${streamConfig.latencyMs}"
        targetUrl.text = listenUrl
        status.text = "Ready for OBS. Waiting on port ${ConnectionTarget.DEFAULT_PORT}."
        listenerThread = Thread({
            while (phoneServerRunning) {
                runCatching {
                    streamClient.listen(
                        url = listenUrl,
                        codecMime = encoder.codecName,
                        width = streamConfig.width,
                        height = streamConfig.height,
                        fps = streamConfig.fps,
                    )
                    if (!phoneServerRunning) return@runCatching
                    phoneConnected = true
                    activeTargetName = "OBS"
                    runOnUiThread {
                        status.text = "OBS connected. Starting camera stream."
                        mainHandler.removeCallbacks(statsTicker)
                        mainHandler.post(statsTicker)
                    }
                    encoder.start()
                    camera.startStreaming(encoder.inputSurface())
                    while (phoneServerRunning && phoneConnected) {
                        Thread.sleep(250)
                    }
                }.onFailure { error ->
                    runOnUiThread {
                        status.text = "Phone listener error: ${error.message ?: "unknown error"}"
                    }
                    Thread.sleep(750)
                }
                stopActiveEncoding(updateStatus = false)
                phoneConnected = false
                activeTargetName = null
                if (phoneServerRunning) {
                    runOnUiThread {
                        status.text = "Ready for OBS. Waiting on port ${ConnectionTarget.DEFAULT_PORT}."
                    }
                }
            }
        }, "OpenStreamPhoneSrtListener").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopPhoneServer() {
        phoneServerRunning = false
        phoneConnected = false
        activeTargetName = null
        mainHandler.removeCallbacks(statsTicker)
        streamClient.disconnect()
        stopActiveEncoding()
    }

    private fun stopStream(updateStatus: Boolean = true) {
        activeTargetName = null
        mainHandler.removeCallbacks(statsTicker)
        phoneConnected = false
        streamClient.disconnect()
        stopActiveEncoding(updateStatus)
    }

    private fun stopActiveEncoding(updateStatus: Boolean = true) {
        camera.stopStreaming()
        encoder.stop()
        if (updateStatus) {
            status.text = "Streaming stopped. Camera preview remains ready."
        }
    }

    private fun renderStreamStats(forceFailure: Boolean = false) {
        val targetName = activeTargetName ?: return
        val stats = streamClient.stats
        val megabits = stats.bytesSent * 8.0 / 1_000_000.0
        val prefix = if (forceFailure || stats.sendFailures > 0) {
            "Send issue"
        } else if (stats.accessUnitsSent == 0L) {
            "Waiting for encoded frames"
        } else {
            "Live"
        }
        status.text = String.format(
            "%s: %s | frames=%d keyframes=%d sent=%.1f Mb stream=%.1fs failures=%d",
            prefix,
            targetName,
            stats.accessUnitsSent,
            stats.keyframesSent,
            megabits,
            stats.secondsSent,
            stats.sendFailures,
        )
    }

    private fun startPreviewIfAllowed() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            preview.holder.surface.isValid
        ) {
            camera.startPreview()
        }
    }

    private fun requestRuntimePermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 100)
        }
    }

    private fun connectionTargetFromManualFields(): ConnectionTarget {
        val host = obsHost.text.toString().ifBlank { obsHost.hint.toString() }.trim()
        val port = obsPort.text.toString().toIntOrNull() ?: ConnectionTarget.DEFAULT_PORT
        val latencyMs = latency.text.toString().toIntOrNull() ?: ConnectionTarget.DEFAULT_LATENCY_MS
        return ConnectionTarget(
            name = ConnectionTarget.DEFAULT_NAME,
            host = host,
            port = port.coerceIn(1, 65535),
            latencyMs = latencyMs.coerceIn(80, 200),
        )
    }

    private fun selectedLens(): CameraLens {
        return if (lensSelector.selectedItemPosition == 1) CameraLens.Front else CameraLens.Back
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
        )
    }
}
