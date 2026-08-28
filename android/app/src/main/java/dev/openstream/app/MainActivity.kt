package dev.openstream.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.openstream.app.camera.Camera2Controller
import dev.openstream.app.camera.CameraLens
import dev.openstream.app.control.CameraControlServer
import dev.openstream.app.discovery.DiscoveredObsDevice
import dev.openstream.app.discovery.ObsDiscoveryClient
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
    private lateinit var previewContainer: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var obsSlotList: LinearLayout
    private lateinit var lensSelectorRow: LinearLayout
    private lateinit var liveBadge: View
    private lateinit var liveDot: View
    private lateinit var streamInfoChip: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var btnKeepScreenOn: TextView
    private lateinit var btnScreenOff: TextView
    private lateinit var btnTorch: TextView
    private lateinit var btnFlipCamera: TextView
    private lateinit var btnSettings: TextView
    private lateinit var btnStop: TextView
    private lateinit var screenOffOverlay: View
    private lateinit var identifyOverlay: TextView
    private lateinit var bottomControls: LinearLayout

    // ── Core components ──
    private lateinit var camera: Camera2Controller
    private lateinit var encoder: MediaCodecVideoEncoder
    private lateinit var audioEncoder: MediaCodecAudioEncoder
    private lateinit var streamClient: SrtStreamClient
    private lateinit var telemetry: TelemetrySampler
    private lateinit var phoneAdvertiser: PhoneDiscoveryAdvertiser
    private lateinit var obsDiscoveryClient: ObsDiscoveryClient
    private lateinit var controlServer: CameraControlServer

    private val streamConfig = StreamConfig.Default1080p30
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var activeTargetName: String? = null
    @Volatile private var phoneServerRunning = false
    @Volatile private var phoneConnected = false
    @Volatile private var reservedBy: String? = null
    @Volatile private var reservedSlotLabel: String? = null
    @Volatile private var listenerThread: Thread? = null
    @Volatile private var callerConnectThread: Thread? = null
    @Volatile private var callerGeneration = 0L
    @Volatile private var pendingListenerStart = false
    @Volatile private var listenerGeneration = 0L
    @Volatile private var activityStarted = false
    private var keepScreenOn = false
    private var displayOff = false
    private var originalBrightness = -1f
    private var torchOn = false
    private var currentLens: CameraLens = CameraLens.Back
    private var availableLenses: List<CameraLens> = listOf(CameraLens.Back)
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var zoomHideRunnable: Runnable? = null
    private var liveDotAnimator: ObjectAnimator? = null
    private var currentPort: Int = ConnectionTarget.DEFAULT_PORT
    private var releaseReservationRunnable: Runnable? = null
    private var reservationGeneration = 0L
    private var lensRestartRunnable: Runnable? = null
    private var pendingConnectAfterSettings = false
    private var currentDevices: List<DiscoveredObsDevice> = emptyList()
    private var activeStreamBitrate: Int = streamConfig.bitrate
    private val callerLifecycleLock = Any()

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

        currentPort = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
            .getInt(SettingsActivity.KEY_LISTENING_PORT, ConnectionTarget.DEFAULT_PORT)
            .takeIf { it in 1024..65535 }
            ?: ConnectionTarget.DEFAULT_PORT

        streamClient = SrtStreamClient()
        telemetry = TelemetrySampler(this)
        phoneAdvertiser = PhoneDiscoveryAdvertiser(
            context = this,
            config = streamConfig,
            port = currentPort,
            busyProvider = { phoneConnected || reservedBy != null },
            reservedByProvider = { reservedBy },
        )
        obsDiscoveryClient = ObsDiscoveryClient(
            context = this,
            onDevicesChanged = { devices ->
                currentDevices = devices
                renderObsSlots(devices)
            },
        )
        encoder = createVideoEncoder(activeStreamBitrate)
        audioEncoder = MediaCodecAudioEncoder(
            context = this,
            sampleRate = streamConfig.audioSampleRate,
            channelCount = streamConfig.audioChannelCount,
            bitrate = streamConfig.audioBitrate,
            onEncodedAccessUnit = { accessUnit ->
                if (!streamClient.sendAudioAccessUnit(accessUnit)) {
                    handleMediaTransportFailure()
                }
            },
        )
        camera = Camera2Controller(
            context = this,
            previewSurfaceProvider = { cameraPreview.holder.surface },
            lensProvider = { currentLens },
            targetFps = streamConfig.fps,
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
            reservationProvider = { reservedBy },
            onReserve = { sourceInstanceId, slotLabel, bitrateMbps ->
                reserveForSource(sourceInstanceId, slotLabel, bitrateMbps)
            },
            onRelease = { sourceInstanceId -> releaseForSource(sourceInstanceId) },
            onIdentify = { label, subtitle -> runOnUiThread { showIdentifyOverlay(label, subtitle) } },
        )

        cameraPreview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                initializeLenses()
                startPreviewIfAllowed()
                startPhoneServerIfAllowed()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // Fix stretched preview: adjust SurfaceView to maintain camera aspect ratio
                adjustPreviewAspectRatio(width, height)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // Close the camera before encoder teardown tries to rebuild a
                // preview-only session against this now-invalid surface.
                camera.stop()
                stopPhoneServer(clearReservation = false, updateStatus = false)
            }
        })

        setupButtons()
        setupNavBarInsets()
        handlePairingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        phoneAdvertiser.start()
        obsDiscoveryClient.start()
        controlServer.start()
        startPreviewIfAllowed()
        startPhoneServerIfAllowed()
    }

    override fun onResume() {
        super.onResume()
        // Reload listening port from settings if it changed
        val settingsPrefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
        val savedPort = settingsPrefs.getInt(SettingsActivity.KEY_LISTENING_PORT, currentPort)
        if (savedPort != currentPort && savedPort in 1024..65535) {
            changePort(savedPort)
        }
        if (pendingConnectAfterSettings) {
            pendingConnectAfterSettings = false
            startStream(connectionTargetFromSettings())
        }
    }

    override fun onStop() {
        activityStarted = false
        cancelLensRestart()
        camera.stop()
        stopPhoneServer(clearReservation = false, updateStatus = false)
        obsDiscoveryClient.stop()
        phoneAdvertiser.stop()
        controlServer.stop()
        stopLiveDotAnimation()
        super.onStop()
    }

    override fun onDestroy() {
        clearReservation()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 &&
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) {
            initializeLenses()
            startPreviewIfAllowed()
            startPhoneServerIfAllowed()
        }
    }

    @Deprecated("Uses the platform Activity result API to avoid an AndroidX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SETTINGS_REQUEST_CODE && resultCode == RESULT_OK &&
            data?.getBooleanExtra(SettingsActivity.EXTRA_CONNECT_AFTER_SAVE, false) == true
        ) {
            pendingConnectAfterSettings = true
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
        previewContainer = findViewById(R.id.previewContainer)
        statusText = findViewById(R.id.statusText)
        statusDetail = findViewById(R.id.statusDetail)
        obsSlotList = findViewById(R.id.obsSlotList)
        lensSelectorRow = findViewById(R.id.lensSelectorRow)
        liveBadge = findViewById(R.id.liveBadge)
        liveDot = findViewById(R.id.liveDot)
        streamInfoChip = findViewById(R.id.streamInfoChip)
        zoomLabel = findViewById(R.id.zoomLabel)
        btnKeepScreenOn = findViewById(R.id.btnKeepScreenOn)
        btnScreenOff = findViewById(R.id.btnScreenOff)
        btnTorch = findViewById(R.id.btnTorch)
        btnFlipCamera = findViewById(R.id.btnFlipCamera)
        btnSettings = findViewById(R.id.btnSettings)
        btnStop = findViewById(R.id.btnStop)
        screenOffOverlay = findViewById(R.id.screenOffOverlay)
        identifyOverlay = findViewById(R.id.identifyOverlay)
        bottomControls = findViewById(R.id.bottomControls)
    }

    private fun setupButtons() {
        btnKeepScreenOn.setOnClickListener { toggleKeepScreenOn() }
        btnScreenOff.setOnClickListener { toggleDisplayOff() }
        btnTorch.setOnClickListener { toggleTorch() }
        btnFlipCamera.setOnClickListener { flipCamera() }
        btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            @Suppress("DEPRECATION")
            startActivityForResult(intent, SETTINGS_REQUEST_CODE)
        }
        btnStop.setOnClickListener {
            stopPhoneServer(clearReservation = false)
            startPreviewIfAllowed()
            startPhoneServerIfAllowed()
        }

        // Tap the screen-off overlay to re-enable display
        screenOffOverlay.setOnClickListener { toggleDisplayOff() }
    }

    private fun createVideoEncoder(bitrate: Int): MediaCodecVideoEncoder {
        return MediaCodecVideoEncoder(
            preference = streamConfig.codecPreference,
            width = streamConfig.width,
            height = streamConfig.height,
            fps = streamConfig.fps,
            bitrate = bitrate,
            keyframeIntervalSeconds = streamConfig.keyframeIntervalSeconds,
            onEncodedAccessUnit = { accessUnit ->
                val sent = streamClient.sendVideoAccessUnit(accessUnit)
                if (!sent) {
                    handleMediaTransportFailure()
                }
            },
        )
    }

    private fun handleMediaTransportFailure() {
        phoneConnected = false
        mainHandler.post {
            if (phoneServerRunning) {
                if (activeTargetName != null) {
                    statusText.text = "Connection lost"
                    statusText.setTextColor(getColor(R.color.os_warning))
                    statusDetail.text = "Waiting for OBS to reconnect"
                }
                return@post
            }
            if (activeTargetName == null && callerConnectThread == null) return@post
            stopStream(updateStatus = false)
            startPreviewIfAllowed()
            startPhoneServerIfAllowed()
            statusText.text = "Connection lost"
            statusText.setTextColor(getColor(R.color.os_warning))
            statusDetail.text = getString(R.string.status_waiting)
        }
    }

    private fun useStreamBitrate(bitrateMbps: Int?) {
        val nextBitrate = (bitrateMbps ?: streamConfig.bitrateMbps)
            .coerceIn(StreamConfig.MIN_BITRATE_MBPS, StreamConfig.MAX_BITRATE_MBPS) * 1_000_000
        if (activeStreamBitrate == nextBitrate) return
        activeStreamBitrate = nextBitrate
        if (activeTargetName == null) {
            encoder.stop()
            encoder = createVideoEncoder(activeStreamBitrate)
        }
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
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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
        val wasStreaming = activeTargetName != null
        // Stop the encoder before switching cameras to avoid surface conflicts
        if (wasStreaming) {
            camera.stopStreaming()
            encoder.stop()
        }
        currentLens = lens
        camera.switchLens(lens)
        // If we were streaming, re-create the encoder and re-attach after the camera settles
        if (wasStreaming) {
            cancelLensRestart()
            val restart = Runnable {
                lensRestartRunnable = null
                if (!activityStarted || activeTargetName == null) return@Runnable
                runCatching {
                    encoder.start()
                    camera.startStreaming(encoder.inputSurface())
                }.onFailure { e ->
                    Log.e("OpenStream", "Failed to restart encoder after lens switch", e)
                    statusText.text = "Encoder error"
                    statusDetail.text = e.message ?: "Unknown"
                }
            }
            lensRestartRunnable = restart
            mainHandler.postDelayed(restart, LENS_RESTART_DELAY_MS)
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

    private fun cancelLensRestart() {
        lensRestartRunnable?.let(mainHandler::removeCallbacks)
        lensRestartRunnable = null
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

    private fun renderObsSlots(devices: List<DiscoveredObsDevice>) {
        obsSlotList.removeAllViews()
        if (devices.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.section_obs_slots) + "\n" + getString(R.string.status_no_slots)
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(getColor(R.color.os_text_tertiary))
                gravity = Gravity.CENTER
                alpha = 0.8f
            }
            obsSlotList.addView(empty)
            return
        }

        val title = TextView(this).apply {
            text = getString(R.string.section_obs_slots)
            textSize = 10f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(getColor(R.color.os_text_secondary))
            letterSpacing = 0.1f
            setPadding(0, 0, resources.getDimensionPixelSize(R.dimen.os_spacing_md), 0)
        }
        obsSlotList.addView(title)

        devices.forEach { device ->
            val isReservedForThisPhone = reservedBy == device.sourceInstanceId
            val enabled = !device.busy || isReservedForThisPhone
            val card = TextView(this).apply {
                text = "${device.displayLabel} · ${slotAvailabilityLabel(device, isReservedForThisPhone)}"
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setTextColor(
                    if (isReservedForThisPhone && phoneConnected) getColor(R.color.os_black)
                    else if (enabled) getColor(R.color.os_text_primary)
                    else getColor(R.color.os_text_tertiary)
                )
                setBackgroundResource(
                    if (isReservedForThisPhone && phoneConnected) R.drawable.bg_minimal_pill_active
                    else if (isReservedForThisPhone) R.drawable.bg_minimal_pill
                    else R.drawable.bg_minimal_pill
                )
                alpha = if (enabled) 1f else 0.45f
                isEnabled = enabled
                minHeight = resources.getDimensionPixelSize(R.dimen.os_control_btn_size)
                setPadding(
                    resources.getDimensionPixelSize(R.dimen.os_spacing_lg),
                    0,
                    resources.getDimensionPixelSize(R.dimen.os_spacing_lg),
                    0,
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    resources.getDimensionPixelSize(R.dimen.os_control_btn_size),
                ).apply {
                    marginEnd = resources.getDimensionPixelSize(R.dimen.os_spacing_sm)
                }
                if (enabled) {
                    setOnClickListener { reserveForSlot(device) }
                }
            }
            obsSlotList.addView(card)
        }
    }

    private fun reserveForSlot(device: DiscoveredObsDevice) {
        if (device.busy && reservedBy != device.sourceInstanceId) return
        
        // If connected to someone else and user explicitly taps a new slot, disconnect the old stream
        if (phoneConnected && reservedBy != device.sourceInstanceId) {
            stopStream(updateStatus = false)
        }

        if (reserveForSource(device.sourceInstanceId, device.displayLabel, device.bitrateMbps)) {
            statusText.text = "Paired to ${device.displayLabel}"
            statusDetail.text = "Waiting for OBS to go live"
            renderObsSlots(currentDevices)
        }
    }

    private fun slotAvailabilityLabel(
        device: DiscoveredObsDevice,
        reservedForThisPhone: Boolean,
    ): String {
        return when {
            reservedForThisPhone && phoneConnected -> "Live"
            reservedForThisPhone -> "Reserved"
            device.busy -> "Busy"
            else -> "Available"
        }
    }

    private fun handlePairingIntent(intent: Intent?) {
        val uri: Uri = intent?.data ?: return
        val sourceInstanceId = uri.getQueryParameter("sourceInstanceId")?.trim().orEmpty()
        if (sourceInstanceId.isNotBlank()) {
            val slotLabel = uri.getQueryParameter("slotLabel")?.trim().orEmpty()
            val bitrateMbps = uri.getQueryParameter("bitrateMbps")?.toIntOrNull()
                ?.coerceIn(StreamConfig.MIN_BITRATE_MBPS, StreamConfig.MAX_BITRATE_MBPS)
            if (reserveForSource(sourceInstanceId, slotLabel, bitrateMbps)) {
                statusText.text = "Paired to ${slotLabel.ifBlank { "OBS slot" }}"
                statusDetail.text = "Waiting for OBS to go live"
            }
            return
        }
        val target = ConnectionTarget.fromPairingUri(uri) ?: return
        startStream(target)
    }

    private fun startStream(target: ConnectionTarget) {
        stopStream(updateStatus = false)
        // Caller mode and listener mode share one native SRT transport. Fully
        // stop the listener before opening a manual caller connection.
        stopPhoneServer(clearReservation = true, updateStatus = false)
        useStreamBitrate(target.bitrateMbps)
        statusText.text = "Connecting…"
        statusDetail.text = "${currentLens.displayName} → ${target.name}"
        val generation = callerGeneration + 1
        callerGeneration = generation
        val thread = Thread({
            try {
                streamClient.connect(
                    url = target.toSrtCallerUrl(),
                    codecMime = encoder.codecName,
                    width = streamConfig.width,
                    height = streamConfig.height,
                    fps = streamConfig.fps,
                )
                synchronized(callerLifecycleLock) {
                    check(callerGeneration == generation) { "SRT caller connection was cancelled" }
                    encoder.start()
                    startAudioIfAllowed()
                    camera.startStreaming(encoder.inputSurface())
                }
                mainHandler.post {
                    if (callerGeneration != generation) return@post
                    activeTargetName = target.name
                    mainHandler.removeCallbacks(statsTicker)
                    mainHandler.post(statsTicker)
                    showLiveState(target.name)
                }
            } catch (error: Throwable) {
                synchronized(callerLifecycleLock) {
                    if (callerGeneration == generation) {
                        streamClient.disconnect()
                        stopActiveEncoding(updateStatus = false)
                    }
                }
                mainHandler.post {
                    if (callerGeneration != generation) return@post
                    startPreviewIfAllowed()
                    startPhoneServerIfAllowed()
                    statusText.text = "Connection failed"
                    statusDetail.text = error.message ?: "Unknown error"
                }
            } finally {
                if (callerConnectThread === Thread.currentThread()) {
                    callerConnectThread = null
                }
            }
        }, "OpenStreamPhoneSrtCaller").apply {
            isDaemon = true
        }
        callerConnectThread = thread
        thread.start()
    }

    private fun startAudioIfAllowed() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.i("OpenStream", "Microphone permission not granted; streaming video without audio")
            return
        }
        runCatching { audioEncoder.start() }.onFailure { e ->
            Log.w("OpenStream", "Audio encoder start failed; continuing video-only", e)
        }
    }

    private fun startPhoneServerIfAllowed() {
        if (phoneServerRunning) return
        if (listenerThread?.isAlive == true) {
            pendingListenerStart = true
            Log.w("OpenStream", "Previous SRT listener is still stopping; not starting another")
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (!cameraPreview.holder.surface.isValid) return

        pendingListenerStart = false
        val generation = listenerGeneration + 1
        listenerGeneration = generation
        phoneServerRunning = true
        phoneConnected = false
        activeTargetName = null
        statusText.text = getString(R.string.status_ready)
        statusText.setTextColor(getColor(R.color.os_text_primary))
        statusDetail.text = getString(R.string.status_waiting)
        btnStop.visibility = View.GONE

        val thread = Thread({
            try {
                while (isListenerActive(generation)) {
                    val listenUrl = "srt://0.0.0.0:${currentPort}?mode=listener&latency=${streamConfig.latencyMs}"
                    val listenResult = runCatching {
                        streamClient.listen(
                            url = listenUrl,
                            codecMime = encoder.codecName,
                            width = streamConfig.width,
                            height = streamConfig.height,
                            fps = streamConfig.fps,
                        )
                        if (!isListenerActive(generation)) {
                            streamClient.disconnect()
                            return@runCatching
                        }
                        phoneConnected = true
                        cancelReservationRelease()
                        val liveTargetName = reservedSlotLabel ?: "OBS"
                        activeTargetName = liveTargetName
                        runOnUiThread {
                            if (!isListenerActive(generation)) return@runOnUiThread
                            showLiveState(liveTargetName)
                            mainHandler.removeCallbacks(statsTicker)
                            mainHandler.post(statsTicker)
                        }
                        encoder.start()
                        startAudioIfAllowed()
                        camera.startStreaming(encoder.inputSurface())
                        while (isListenerActive(generation) && phoneConnected) {
                            Thread.sleep(LISTENER_POLL_MS)
                        }
                    }

                    if (listenResult.isFailure && isListenerActive(generation)) {
                        val error = listenResult.exceptionOrNull()
                        runOnUiThread {
                            if (!isListenerActive(generation)) return@runOnUiThread
                            statusText.text = "Listener error"
                            statusDetail.text = error?.message ?: "Unknown"
                        }
                        try {
                            Thread.sleep(LISTENER_RETRY_MS)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }

                    stopActiveEncoding(updateStatus = false)
                    phoneConnected = false
                    activeTargetName = null
                    if (isListenerActive(generation)) {
                        scheduleReservationRelease()
                        runOnUiThread {
                            if (!isListenerActive(generation)) return@runOnUiThread
                            hideLiveState()
                            statusText.text = getString(R.string.status_ready)
                            statusDetail.text = reservedSlotLabel?.let { "Holding $it for reconnect" }
                                ?: getString(R.string.status_waiting)
                        }
                    }
                }
            } finally {
                if (listenerThread === Thread.currentThread()) {
                    listenerThread = null
                    mainHandler.post {
                        if (pendingListenerStart) {
                            pendingListenerStart = false
                            startPhoneServerIfAllowed()
                        }
                    }
                }
            }
        }, "OpenStreamPhoneSrtListener").apply {
            isDaemon = true
        }
        listenerThread = thread
        thread.start()
    }

    private fun isListenerActive(generation: Long): Boolean {
        return phoneServerRunning && listenerGeneration == generation
    }

    private fun stopPhoneServer(
        clearReservation: Boolean = true,
        updateStatus: Boolean = true,
    ) {
        callerGeneration += 1
        callerConnectThread?.interrupt()
        pendingListenerStart = false
        listenerGeneration += 1
        phoneServerRunning = false
        phoneConnected = false
        if (clearReservation) clearReservation()
        activeTargetName = null
        mainHandler.removeCallbacks(statsTicker)
        streamClient.disconnect()
        val thread = listenerThread
        thread?.interrupt()
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(LISTENER_STOP_TIMEOUT_MS) }
                .onFailure { Thread.currentThread().interrupt() }
        }
        if (thread?.isAlive == true) {
            Log.w("OpenStream", "SRT listener did not stop within ${LISTENER_STOP_TIMEOUT_MS}ms")
        } else if (listenerThread === thread) {
            listenerThread = null
        }
        synchronized(callerLifecycleLock) {
            stopActiveEncoding(updateStatus)
        }
        hideLiveState()
        btnStop.visibility = View.GONE
    }

    private fun stopStream(updateStatus: Boolean = true) {
        callerGeneration += 1
        callerConnectThread?.interrupt()
        activeTargetName = null
        mainHandler.removeCallbacks(statsTicker)
        phoneConnected = false
        streamClient.disconnect()
        synchronized(callerLifecycleLock) {
            stopActiveEncoding(updateStatus)
        }
        hideLiveState()
    }

    private fun stopActiveEncoding(updateStatus: Boolean = true) {
        cancelLensRestart()
        camera.stopStreaming()
        encoder.stop()
        audioEncoder.stop()
        if (updateStatus) {
            statusText.text = getString(R.string.status_stopped)
            statusDetail.text = "Camera preview remains active"
        }
    }

    // ─────────────────────────── Live state UI ───────────────────────────

    @Synchronized
    private fun reserveForSource(
        sourceInstanceId: String,
        slotLabel: String = "",
        bitrateMbps: Int? = null,
    ): Boolean {
        val currentReservation = reservedBy
        if (phoneConnected && currentReservation != sourceInstanceId) return false
        useStreamBitrate(bitrateMbps)
        reservationGeneration += 1
        reservedBy = sourceInstanceId
        reservedSlotLabel = slotLabel.ifBlank { reservedSlotLabel }
        if (phoneConnected) {
            cancelReservationRelease()
        } else {
            scheduleReservationRelease()
        }
        return true
    }

    @Synchronized
    private fun releaseForSource(sourceInstanceId: String): Boolean {
        if (reservedBy == sourceInstanceId) {
            clearReservation()
            return true
        }
        return reservedBy == null
    }

    @Synchronized
    private fun clearReservation() {
        cancelReservationRelease()
        reservedBy = null
        reservedSlotLabel = null
    }

    @Synchronized
    private fun scheduleReservationRelease() {
        val sourceInstanceId = reservedBy ?: return
        val generation = reservationGeneration
        cancelReservationRelease()
        releaseReservationRunnable = Runnable {
            synchronized(this) {
                if (!phoneConnected &&
                    reservedBy == sourceInstanceId &&
                    reservationGeneration == generation
                ) {
                    reservedBy = null
                    reservedSlotLabel = null
                }
            }
        }
        mainHandler.postDelayed(releaseReservationRunnable!!, RECONNECT_RESERVATION_MS)
    }

    @Synchronized
    private fun cancelReservationRelease() {
        releaseReservationRunnable?.let { mainHandler.removeCallbacks(it) }
        releaseReservationRunnable = null
    }

    private fun showIdentifyOverlay(label: String, subtitle: String) {
        val text = if (subtitle.isBlank()) label else "$label\n$subtitle"
        identifyOverlay.text = text
        identifyOverlay.visibility = View.VISIBLE
        identifyOverlay.bringToFront()
        mainHandler.postDelayed({
            identifyOverlay.visibility = View.GONE
        }, IDENTIFY_OVERLAY_MS)
    }

    private fun showLiveState(targetName: String) {
        liveBadge.visibility = View.VISIBLE
        btnStop.visibility = View.VISIBLE
        startLiveDotAnimation()
        statusText.text = getString(R.string.status_streaming, targetName)
        statusText.setTextColor(getColor(R.color.os_text_primary))
        statusDetail.text = "${streamConfig.width}×${streamConfig.height}@${streamConfig.fps} · ${activeStreamBitrate / 1_000_000} Mbps"
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

    private fun connectionTargetFromSettings(): ConnectionTarget {
        val settingsPrefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
        val host = settingsPrefs.getString(SettingsActivity.KEY_OBS_HOST, ConnectionTarget.DEFAULT_HOST)
            ?.trim().orEmpty().ifBlank { ConnectionTarget.DEFAULT_HOST }
        val port = settingsPrefs.getInt(SettingsActivity.KEY_OBS_PORT, ConnectionTarget.DEFAULT_PORT)
        val latencyMs = settingsPrefs.getInt(SettingsActivity.KEY_LATENCY, ConnectionTarget.DEFAULT_LATENCY_MS)
        return ConnectionTarget(
            name = ConnectionTarget.DEFAULT_NAME,
            host = host,
            port = port.coerceIn(1, 65535),
            latencyMs = latencyMs.coerceIn(80, 200),
        )
    }

    // ─────────────────────────── Display off (screen off while streaming) ───────────────────────────

    private fun toggleDisplayOff() {
        displayOff = !displayOff
        if (displayOff) {
            // Save current brightness and dim to minimum
            originalBrightness = window.attributes.screenBrightness
            val params = window.attributes
            params.screenBrightness = 0.001f // minimum possible brightness
            window.attributes = params
            // Show black overlay to hide all UI (saves power on OLED)
            screenOffOverlay.visibility = View.VISIBLE
            // Ensure screen stays on even when dimmed
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            btnScreenOff.text = "DISPLAY ✓"
            btnScreenOff.setBackgroundResource(R.drawable.bg_btn_accent)
            btnScreenOff.setTextColor(getColor(R.color.os_black))
        } else {
            // Restore original brightness
            val params = window.attributes
            params.screenBrightness = if (originalBrightness >= 0) originalBrightness else -1f
            window.attributes = params
            screenOffOverlay.visibility = View.GONE
            // Restore keep-screen-on to user's toggle state
            if (!keepScreenOn) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            btnScreenOff.text = "DISPLAY"
            btnScreenOff.setBackgroundResource(R.drawable.bg_btn_ghost)
            btnScreenOff.setTextColor(getColor(R.color.os_text_secondary))
        }
    }

    // ─────────────────────────── Port selector ───────────────────────────

    private fun changePort(newPort: Int) {
        val clamped = newPort.coerceIn(1024, 65535)
        if (clamped == currentPort) return
        currentPort = clamped
        // Restart the phone server on the new port
        stopPhoneServer(clearReservation = false)
        // Re-create the advertiser with new port
        phoneAdvertiser.stop()
        phoneAdvertiser = PhoneDiscoveryAdvertiser(
            context = this,
            config = streamConfig,
            port = currentPort,
            busyProvider = { phoneConnected || reservedBy != null },
            reservedByProvider = { reservedBy },
        )
        phoneAdvertiser.start()
        startPhoneServerIfAllowed()
    }

    // ─────────────────────────── Preview aspect ratio fix ───────────────────────────

    private fun adjustPreviewAspectRatio(surfaceWidth: Int, surfaceHeight: Int) {
        // Camera outputs in landscape (e.g. 1920x1080) but phone is portrait
        // The preview surface should match the camera aspect ratio to avoid stretching
        val cameraAspect = streamConfig.width.toFloat() / streamConfig.height.toFloat()
        // In portrait, the preview aspect should be height/width = 16/9
        val targetAspect = cameraAspect // = 16:9

        val containerWidth = previewContainer.width
        val containerHeight = previewContainer.height
        if (containerWidth == 0 || containerHeight == 0) return

        val containerAspect = containerWidth.toFloat() / containerHeight.toFloat()
        // In portrait, we want the preview to fill width and adjust height
        val targetWidth: Int
        val targetHeight: Int
        if (containerAspect > (1f / targetAspect)) {
            // Container is wider than needed — match height, crop width
            targetHeight = containerHeight
            targetWidth = (containerHeight / targetAspect).toInt()
        } else {
            // Container is taller than needed — match width, crop height
            targetWidth = containerWidth
            targetHeight = (containerWidth * targetAspect).toInt()
        }

        val lp = cameraPreview.layoutParams as FrameLayout.LayoutParams
        lp.width = targetWidth
        lp.height = targetHeight
        lp.gravity = Gravity.CENTER
        cameraPreview.layoutParams = lp
    }

    // ─────────────────────────── Nav bar insets (3-button nav fix) ───────────────────────────

    private fun setupNavBarInsets() {
        bottomControls.setOnApplyWindowInsetsListener { view, insets ->
            val navBarHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.systemBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                resources.getDimensionPixelSize(R.dimen.os_spacing_xl) + navBarHeight,
            )
            insets
        }
        // Request insets
        bottomControls.requestApplyInsets()
    }

    companion object {
        private const val RECONNECT_RESERVATION_MS = 45_000L
        private const val IDENTIFY_OVERLAY_MS = 3_000L
        private const val LENS_RESTART_DELAY_MS = 500L
        private const val LISTENER_POLL_MS = 250L
        private const val LISTENER_RETRY_MS = 750L
        private const val LISTENER_STOP_TIMEOUT_MS = 2_000L
        private const val SETTINGS_REQUEST_CODE = 200
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
    }
}
