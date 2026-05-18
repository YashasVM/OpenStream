package dev.openstream.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.SurfaceView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import dev.openstream.app.camera.Camera2Controller
import dev.openstream.app.camera.CameraLens
import dev.openstream.app.encoder.MediaCodecVideoEncoder
import dev.openstream.app.stream.ConnectionTarget
import dev.openstream.app.stream.StreamConfig
import dev.openstream.app.stream.SrtStreamClient
import dev.openstream.app.telemetry.DeviceTelemetry
import dev.openstream.app.telemetry.TelemetrySampler

class MainActivity : Activity() {
    private lateinit var preview: SurfaceView
    private lateinit var status: TextView
    private lateinit var obsHost: EditText
    private lateinit var obsPort: EditText
    private lateinit var latency: EditText
    private lateinit var lensSelector: Spinner
    private lateinit var targetUrl: TextView
    private lateinit var camera: Camera2Controller
    private lateinit var encoder: MediaCodecVideoEncoder
    private lateinit var streamClient: SrtStreamClient
    private lateinit var telemetry: TelemetrySampler
    private val streamConfig = StreamConfig.Default1080p30

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()

        preview = SurfaceView(this)
        status = TextView(this).apply {
            text = "Camera-only feed. Enter the OBS PC IP, then Start."
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
        val start = Button(this).apply {
            text = "Start camera feed"
            setOnClickListener { startStream() }
        }
        val stop = Button(this).apply {
            text = "Stop"
            setOnClickListener { stopStream() }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    preview,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    ),
                )
                addView(TextView(this@MainActivity).apply { text = "OBS PC IP" })
                addView(obsHost)
                addView(TextView(this@MainActivity).apply { text = "OBS listener port" })
                addView(obsPort)
                addView(TextView(this@MainActivity).apply { text = "Latency ms" })
                addView(latency)
                addView(lensSelector)
                addView(targetUrl)
                addView(start)
                addView(stop)
                addView(status)
            }
        )

        streamClient = SrtStreamClient()
        telemetry = TelemetrySampler(this)
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
            encodedSurfaceProvider = encoder::inputSurface,
            lensProvider = { selectedLens() },
        )
    }

    private fun startStream() {
        val target = connectionTarget()
        val url = target.toSrtCallerUrl()
        targetUrl.text = url
        status.text = "Connecting camera to OBS on ${target.host}:${target.port}"
        runCatching {
            streamClient.connect(
                url = url,
                codecMime = encoder.codecName,
                width = streamConfig.width,
                height = streamConfig.height,
                fps = streamConfig.fps,
            )
            encoder.start()
            camera.start()
            val sample: DeviceTelemetry = telemetry.sample(
                streamUrl = url,
                codec = encoder.codecName,
                width = streamConfig.width,
                height = streamConfig.height,
                fps = streamConfig.fps,
                bitrate = streamConfig.bitrate,
            )
            status.text = "Camera feed live: ${selectedLens().displayName}, ${sample.codec} ${sample.width}x${sample.height}@${sample.fps}"
        }.onFailure { error ->
            stopStream()
            status.text = "Could not start camera feed: ${error.message ?: "unknown error"}"
        }
    }

    private fun stopStream() {
        camera.stop()
        encoder.stop()
        streamClient.disconnect()
        status.text = "Stopped"
    }

    private fun requestRuntimePermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 100)
        }
    }

    private fun connectionTarget(): ConnectionTarget {
        val host = obsHost.text.toString().ifBlank { obsHost.hint.toString() }.trim()
        val port = obsPort.text.toString().toIntOrNull() ?: ConnectionTarget.DEFAULT_PORT
        val latencyMs = latency.text.toString().toIntOrNull() ?: ConnectionTarget.DEFAULT_LATENCY_MS
        return ConnectionTarget(
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
