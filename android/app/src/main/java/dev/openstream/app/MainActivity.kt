package dev.openstream.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
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
import dev.openstream.app.discovery.DiscoveredObsDevice
import dev.openstream.app.discovery.ObsDiscoveryClient
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
    private lateinit var discoveryClient: ObsDiscoveryClient
    private val streamConfig = StreamConfig.Default1080p30

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        buildUi()

        streamClient = SrtStreamClient()
        telemetry = TelemetrySampler(this)
        discoveryClient = ObsDiscoveryClient(this, ::renderDiscoveredDevices)
        encoder = MediaCodecVideoEncoder(
            preference = streamConfig.codecPreference,
            width = streamConfig.width,
            height = streamConfig.height,
            fps = streamConfig.fps,
            bitrate = streamConfig.bitrate,
            keyframeIntervalSeconds = streamConfig.keyframeIntervalSeconds,
            onEncodedAccessUnit = streamClient::sendVideoAccessUnit,
        )
        camera = Camera2Controller(
            context = this,
            previewSurfaceProvider = { preview.holder.surface },
            lensProvider = { selectedLens() },
        )
        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = startPreviewIfAllowed()
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = camera.stop()
        })

        handlePairingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        discoveryClient.start()
    }

    override fun onStop() {
        stopStream()
        camera.stop()
        discoveryClient.stop()
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
            text = "Camera preview is ready. Waiting for an OBS source on the same Wi-Fi."
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
            setOnClickListener { stopStream() }
        }

        manualContainer.addView(TextView(this).apply { text = "OBS PC IP" })
        manualContainer.addView(obsHost)
        manualContainer.addView(TextView(this).apply { text = "OBS listener port" })
        manualContainer.addView(obsPort)
        manualContainer.addView(TextView(this).apply { text = "Latency ms" })
        manualContainer.addView(latency)
        manualContainer.addView(manualStart)

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
                        addView(TextView(this@MainActivity).apply { text = "Available OBS devices" })
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

    private fun renderDiscoveredDevices(devices: List<DiscoveredObsDevice>) {
        devicesContainer.removeAllViews()
        if (devices.isEmpty()) {
            devicesContainer.addView(TextView(this).apply {
                text = "No OBS sources found yet. Check same Wi-Fi and firewall, or use manual fallback."
            })
            return
        }

        devices.forEach { device ->
            val row = Button(this).apply {
                text = buildString {
                    append(device.name)
                    append("\n")
                    append(device.displayEndpoint)
                    append("  ")
                    append(device.latencyMs)
                    append(" ms  ")
                    append(device.bitrateMbps)
                    append(" Mbps")
                    if (device.busy) append("\nBusy")
                }
                isEnabled = !device.busy
                setOnClickListener {
                    startStream(ConnectionTarget.fromDiscoveredDevice(device))
                }
            }
            devicesContainer.addView(row)
        }
    }

    private fun handlePairingIntent(intent: Intent?) {
        val uri: Uri = intent?.data ?: return
        val target = ConnectionTarget.fromPairingUri(uri) ?: return
        startStream(target)
    }

    private fun startStream(target: ConnectionTarget) {
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
            val sample: DeviceTelemetry = telemetry.sample(
                streamUrl = url,
                codec = encoder.codecName,
                width = streamConfig.width,
                height = streamConfig.height,
                fps = streamConfig.fps,
                bitrate = streamConfig.bitrate,
            )
            status.text = "Live in OBS: ${target.name}, ${sample.codec} ${sample.width}x${sample.height}@${sample.fps}"
        }.onFailure { error ->
            stopStream()
            status.text = "Could not start camera feed: ${error.message ?: "unknown error"}"
        }
    }

    private fun stopStream() {
        camera.stopStreaming()
        encoder.stop()
        streamClient.disconnect()
        status.text = "Streaming stopped. Camera preview remains ready."
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
