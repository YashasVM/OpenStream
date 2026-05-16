package dev.openstream.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.SurfaceView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.openstream.app.camera.Camera2Controller
import dev.openstream.app.encoder.MediaCodecVideoEncoder
import dev.openstream.app.stream.StreamConfig
import dev.openstream.app.stream.SrtStreamClient
import dev.openstream.app.telemetry.DeviceTelemetry
import dev.openstream.app.telemetry.TelemetrySampler

class MainActivity : Activity() {
    private lateinit var preview: SurfaceView
    private lateinit var status: TextView
    private lateinit var target: EditText
    private lateinit var camera: Camera2Controller
    private lateinit var encoder: MediaCodecVideoEncoder
    private lateinit var streamClient: SrtStreamClient
    private lateinit var telemetry: TelemetrySampler
    private val streamConfig = StreamConfig.Default1080p30

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()

        preview = SurfaceView(this)
        status = TextView(this)
        target = EditText(this).apply {
            hint = "srt://192.168.1.20:9000?mode=caller&latency=120"
            setSingleLine(true)
        }
        val start = Button(this).apply {
            text = "Start"
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
                addView(target)
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
        )
    }

    private fun startStream() {
        val url = target.text.toString().ifBlank { target.hint.toString() }
        status.text = "Connecting $url"
        streamClient.connect(url)
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
        status.text = "Streaming ${sample.codec} ${sample.width}x${sample.height}@${sample.fps}"
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

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
    }
}
