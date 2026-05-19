package dev.openstream.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.openstream.app.camera.Camera2Controller
import dev.openstream.app.camera.CameraLens
import dev.openstream.app.control.CameraControlServer
import dev.openstream.app.discovery.PhoneDiscoveryAdvertiser
import dev.openstream.app.encoder.MediaCodecAudioEncoder
import dev.openstream.app.encoder.MediaCodecVideoEncoder
import dev.openstream.app.stream.ConnectionTarget
import dev.openstream.app.stream.StreamConfig
import dev.openstream.app.stream.SrtStreamClient
import dev.openstream.app.telemetry.TelemetrySampler

class MainActivity : Activity() {

    // ── Views ──
    private lateinit var cameraPreview: SurfaceView
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var connectionInfo: TextView
    private lateinit var manualContainer: LinearLayout
    private lateinit var inputObsHost: EditText
    private lateinit var inputObsPort: EditText
    private lateinit var inputLatency: EditText
    private lateinit var lensSelectorRow: LinearLayout
    private lateinit var liveBadge: View
    private lateinit var liveDot: View
    private lateinit var streamInfoChip: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var btnKeepScreenOn: TextView
    private lateinit var btnTorch: TextView
    private lateinit var btnFlipCamera: TextView
    private lateinit var btnManualToggle: TextView
    private lateinit var btnManualConnect: TextView
    private lateinit var btnStop: TextView

    // ── Core components ──
    private lateinit var camera: Camera2Controller
    private lateinit var encoder: MediaCodecVideoEncoder
    private lateinit var audioEncoder: MediaCodecAudioEncoder
    private lateinit var streamClient: SrtStreamClient
    private lateinit var telemetry: TelemetrySampler
    private lateinit var phoneAdvertiser: PhoneDiscoveryAdvertiser
    private lateinit var controlServer: CameraControlServer

    private val streamConfig = StreamConfig.Default1080p30
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeTargetName: String? = null
    @Volatile private var phoneServerRunning = false
    @Volatile private var phoneConnected = false
    private var listenerThread: Thread? = null
    private var keepScreenOn = false
    private var torchOn = false
    private var currentLens: CameraLens = CameraLens.Back
    private var availableLenses: List<CameraLens> = listOf(CameraLens.Back)
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var zoomHideRunnable: Runnable? = null
    private var liveDotAnimator: ObjectAnimator? = null

    private val statsTicker = object : Runnable {
        override fun run() {
            renderStreamStats()
            if (activeTargetName != null) {
                mainHandler.postDelayed(this, 1_000)
            }
        }
    }

    // ─────────────────────────── Lifecycle ───────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        setContentView(R.layout.activity_main)
        bindViews()
        setupGestureDetector()

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
        audioEncoder = MediaCodecAudioEncoder(
            sampleRate = 44100,
            channelCount = 1,
            bitrate = 128_000,
            onEncodedAccessUnit = { accessUnit ->
                streamClient.sendAudioAccessUnit(accessUnit)
            },
        )
        camera = Camera2Controller(
            context = this,
            previewSurfaceProvider = { cameraPreview.holder.surface },
            lensProvider = { currentLens },
        )
        controlServer = CameraControlServer(
            cameraProvider = { camera },
            lensListProvider = { availableLenses },
            currentLensProvider = { currentLens },
            onSwitchLens = { lens -> runOnUiThread { selectLens(lens) } },
            onToggleTorch = { enabled -> runOnUiThread {
                torchOn = enabled
                camera.setTorch(enabled)
                if (enabled) {
                    btnTorch.setBackgroundResource(R.drawable.bg_btn_accent)
                    btnTorch.setTextColor(getColor(R.color.os_black))
                } else {
                    btnTorch.setBackgroundResource(R.drawable.bg_btn_ghost)
                    btnTorch.setTextColor(getColor(R.color.os_text_secondary))
                }
            }},
        )

        cameraPreview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                initializeLenses()
                startPreviewIfAllowed()
                startPhoneServerIfAllowed()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = stopPhoneServer()
        })

        setupButtons()
        renderConnectionInfo()
        handlePairingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        phoneAdvertiser.start()
        controlServer.start()
        startPhoneServerIfAllowed()
    }

    override fun onStop() {
        stopPhoneServer()
        camera.stop()
        phoneAdvertiser.stop()
        controlServer.stop()
        stopLiveDotAnimation()
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            initializeLenses()
            startPreviewIfAllowed()
            startPhoneServerIfAllowed()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingIntent(intent)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        event?.let { scaleGestureDetector.onTouchEvent(it) }
        return super.onTouchEvent(event)
    }

    // ─────────────────────────── View binding ───────────────────────────

    private fun bindViews() {
        cameraPreview = findViewById(R.id.cameraPreview)
        statusText = findViewById(R.id.statusText)
        statusDetail = findViewById(R.id.statusDetail)
        connectionInfo = findViewById(R.id.connectionInfo)
        manualContainer = findViewById(R.id.manualContainer)
        inputObsHost = findViewById(R.id.inputObsHost)
        inputObsPort = findViewById(R.id.inputObsPort)
        inputLatency = findViewById(R.id.inputLatency)
        lensSelectorRow = findViewById(R.id.lensSelectorRow)
        liveBadge = findViewById(R.id.liveBadge)
        liveDot = findViewById(R.id.liveDot)
        streamInfoChip = findViewById(R.id.streamInfoChip)
        zoomLabel = findViewById(R.id.zoomLabel)
        btnKeepScreenOn = findViewById(R.id.btnKeepScreenOn)
        btnTorch = findViewById(R.id.btnTorch)
        btnFlipCamera = findViewById(R.id.btnFlipCamera)
        btnManualToggle = findViewById(R.id.btnManualToggle)
        btnManualConnect = findViewById(R.id.btnManualConnect)
        btnStop = findViewById(R.id.btnStop)
    }

    private fun setupButtons() {
        btnKeepScreenOn.setOnClickListener { toggleKeepScreenOn() }
        btnTorch.setOnClickListener { toggleTorch() }
        btnFlipCamera.setOnClickListener { flipCamera() }
        btnManualToggle.setOnClickListener {
            manualContainer.visibility =
                if (manualContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        btnManualConnect.setOnClickListener { startStream(connectionTargetFromManualFields()) }
        btnStop.setOnClickListener { stopPhoneServer() }
    }

    private fun setupGestureDetector() {
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newZoom = camera.scaleZoom(detector.scaleFactor)
                showZoomLabel(newZoom)
                return true
            }
        })

        // Also handle pinch on the preview surface itself
        cameraPreview.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            true
        }
    }

    // ─────────────────────────── Lens switching ───────────────────────────

    private fun initializeLenses() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        availableLenses = camera.availableLenses()
        if (currentLens !in availableLenses) {
            currentLens = availableLenses.firstOrNull { it.isBackFacing } ?: availableLenses.first()
        }
        buildLensButtons()
    }

    private fun buildLensButtons() {
        lensSelectorRow.removeAllViews()
        for (lens in availableLenses) {
            val btn = TextView(this).apply {
                text = lens.shortLabel
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                val size = resources.getDimensionPixelSize(R.dimen.os_lens_btn_size)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = resources.getDimensionPixelSize(R.dimen.os_spacing_sm)
                }
                setBackgroundResource(R.drawable.bg_lens_selector)
                isSelected = (lens == currentLens)
                setTextColor(
                    if (isSelected) getColor(R.color.os_black)
                    else getColor(R.color.os_text_secondary)
                )
                setOnClickListener { selectLens(lens) }
            }
            lensSelectorRow.addView(btn)
        }
    }

    private fun selectLens(lens: CameraLens) {
        if (lens == currentLens) return
        // Turn off torch when switching cameras
        if (torchOn) {
            torchOn = false
            btnTorch.setBackgroundResource(R.drawable.bg_btn_ghost)
            btnTorch.setTextColor(getColor(R.color.os_text_secondary))
        }
        currentLens = lens
        camera.switchLens(lens)
        // If streaming, re-attach encode surface
        if (activeTargetName != null) {
            runCatching {
                camera.startStreaming(encoder.inputSurface())
            }
        }
        buildLensButtons()
    }

    private fun flipCamera() {
        val target = if (currentLens.isFrontFacing) {
            availableLenses.firstOrNull { it.isBackFacing } ?: return
        } else {
            availableLenses.firstOrNull { it.isFrontFacing } ?: return
        }
        selectLens(target)
    }

    // ─────────────────────────── Keep screen on ───────────────────────────

    private fun toggleKeepScreenOn() {
        keepScreenOn = !keepScreenOn
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            btnKeepScreenOn.text = "STAY ✓"
            btnKeepScreenOn.setBackgroundResource(R.drawable.bg_btn_accent)
            btnKeepScreenOn.setTextColor(getColor(R.color.os_black))
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            btnKeepScreenOn.text = "STAY"
            btnKeepScreenOn.setTextColor(getColor(R.color.os_text_secondary))
            btnKeepScreenOn.setBackgroundResource(R.drawable.bg_btn_ghost)
        }
    }

    // ─────────────────────────── Torch ───────────────────────────

    private fun toggleTorch() {
        // Only works on back-facing cameras
        if (currentLens.isFrontFacing) return
        torchOn = !torchOn
        camera.setTorch(torchOn)
        if (torchOn) {
            btnTorch.setBackgroundResource(R.drawable.bg_btn_accent)
            btnTorch.setTextColor(getColor(R.color.os_black))
        } else {
            btnTorch.setBackgroundResource(R.drawable.bg_btn_ghost)
            btnTorch.setTextColor(getColor(R.color.os_text_secondary))
        }
    }

    // ─────────────────────────── Zoom ───────────────────────────

    private fun showZoomLabel(zoom: Float) {
        zoomLabel.text = String.format("%.1f×", zoom)
        zoomLabel.visibility = View.VISIBLE

        // Auto-hide after 1.5s
        zoomHideRunnable?.let { mainHandler.removeCallbacks(it) }
        val hideRunnable = Runnable { zoomLabel.visibility = View.GONE }
        zoomHideRunnable = hideRunnable
        mainHandler.postDelayed(hideRunnable, 1_500)
    }

    // ─────────────────────────── Connection ───────────────────────────

    private fun renderConnectionInfo() {
        connectionInfo.text = getString(
            R.string.info_listener,
            ConnectionTarget.DEFAULT_PORT,
            streamConfig.width,
            streamConfig.height,
            streamConfig.fps,
            streamConfig.bitrateMbps,
        )
    }

    private fun handlePairingIntent(intent: Intent?) {
        val uri: Uri = intent?.data ?: return
        val target = ConnectionTarget.fromPairingUri(uri) ?: return
        startStream(target)
    }

    private fun startStream(target: ConnectionTarget) {
        stopStream(updateStatus = false)
        statusText.text = "Connecting…"
        statusDetail.text = "${currentLens.displayName} → ${target.name}"
        runCatching {
            streamClient.connect(
                url = target.toSrtCallerUrl(),
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
            showLiveState(target.name)
        }.onFailure { error ->
            stopStream()
            statusText.text = "Connection failed"
            statusDetail.text = error.message ?: "Unknown error"
        }
    }

    private fun startPhoneServerIfAllowed() {
        if (phoneServerRunning) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (!cameraPreview.holder.surface.isValid) return

        phoneServerRunning = true
        phoneConnected = false
        activeTargetName = null
        statusText.text = getString(R.string.status_ready)
        statusDetail.text = getString(R.string.status_waiting, ConnectionTarget.DEFAULT_PORT)
        btnStop.visibility = View.GONE

        listenerThread = Thread({
            while (phoneServerRunning) {
                val listenUrl = "srt://0.0.0.0:${ConnectionTarget.DEFAULT_PORT}?mode=listener&latency=${streamConfig.latencyMs}"
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
                        showLiveState("OBS")
                        mainHandler.removeCallbacks(statsTicker)
                        mainHandler.post(statsTicker)
                    }
                    encoder.start()
                    audioEncoder.start()
                    camera.startStreaming(encoder.inputSurface())
                    while (phoneServerRunning && phoneConnected) {
                        Thread.sleep(250)
                    }
                }.onFailure { error ->
                    runOnUiThread {
                        statusText.text = "Listener error"
                        statusDetail.text = error.message ?: "Unknown"
                    }
                    Thread.sleep(750)
                }
                stopActiveEncoding(updateStatus = false)
                phoneConnected = false
                activeTargetName = null
                if (phoneServerRunning) {
                    runOnUiThread {
                        hideLiveState()
                        statusText.text = getString(R.string.status_ready)
                        statusDetail.text = getString(R.string.status_waiting, ConnectionTarget.DEFAULT_PORT)
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
        hideLiveState()
        btnStop.visibility = View.GONE
    }

    private fun stopStream(updateStatus: Boolean = true) {
        activeTargetName = null
        mainHandler.removeCallbacks(statsTicker)
        phoneConnected = false
        streamClient.disconnect()
        stopActiveEncoding(updateStatus)
        hideLiveState()
    }

    private fun stopActiveEncoding(updateStatus: Boolean = true) {
        camera.stopStreaming()
        encoder.stop()
        audioEncoder.stop()
        if (updateStatus) {
            statusText.text = getString(R.string.status_stopped)
            statusDetail.text = "Camera preview remains active"
        }
    }

    // ─────────────────────────── Live state UI ───────────────────────────

    private fun showLiveState(targetName: String) {
        liveBadge.visibility = View.VISIBLE
        btnStop.visibility = View.VISIBLE
        startLiveDotAnimation()
        statusText.text = getString(R.string.status_streaming, targetName)
        statusDetail.text = "${streamConfig.width}×${streamConfig.height}@${streamConfig.fps} · ${streamConfig.bitrateMbps} Mbps"
    }

    private fun hideLiveState() {
        liveBadge.visibility = View.GONE
        streamInfoChip.visibility = View.GONE
        stopLiveDotAnimation()
    }

    private fun startLiveDotAnimation() {
        stopLiveDotAnimation()
        liveDotAnimator = ObjectAnimator.ofFloat(liveDot, "alpha", 1f, 0.3f).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopLiveDotAnimation() {
        liveDotAnimator?.cancel()
        liveDotAnimator = null
    }

    private fun renderStreamStats(forceFailure: Boolean = false) {
        val targetName = activeTargetName ?: return
        val stats = streamClient.stats
        val megabits = stats.bytesSent * 8.0 / 1_000_000.0

        if (forceFailure || stats.sendFailures > 0) {
            statusText.text = "Send issue"
            statusText.setTextColor(getColor(R.color.os_warning))
        } else if (stats.accessUnitsSent == 0L) {
            statusText.text = "Waiting for frames…"
            statusText.setTextColor(getColor(R.color.os_text_primary))
        } else {
            statusText.text = getString(R.string.status_streaming, targetName)
            statusText.setTextColor(getColor(R.color.os_text_primary))
        }

        streamInfoChip.visibility = View.VISIBLE
        streamInfoChip.text = String.format(
            "%d f · %d kf · %.1f Mb",
            stats.accessUnitsSent,
            stats.keyframesSent,
            megabits,
        )
        statusDetail.text = String.format(
            "%.1fs · %d errors · %s",
            stats.secondsSent,
            stats.sendFailures,
            currentLens.displayName,
        )
    }

    // ─────────────────────────── Utilities ───────────────────────────

    private fun startPreviewIfAllowed() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            cameraPreview.holder.surface.isValid
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
        val host = inputObsHost.text.toString().ifBlank { ConnectionTarget.DEFAULT_HOST }.trim()
        val port = inputObsPort.text.toString().toIntOrNull() ?: ConnectionTarget.DEFAULT_PORT
        val latencyMs = inputLatency.text.toString().toIntOrNull() ?: ConnectionTarget.DEFAULT_LATENCY_MS
        return ConnectionTarget(
            name = ConnectionTarget.DEFAULT_NAME,
            host = host,
            port = port.coerceIn(1, 65535),
            latencyMs = latencyMs.coerceIn(80, 200),
        )
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
    }
}
