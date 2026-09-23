package dev.openstream.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
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
import dev.openstream.app.camera.CameraLens
import dev.openstream.app.discovery.DiscoveredObsDevice
import dev.openstream.app.stream.ConnectionTarget
import dev.openstream.app.stream.StreamConfig

class MainActivity : Activity(), PhoneSessionObserver {

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

    // Core state and resources belong to PhoneSessionService. This Activity only binds views,
    // attaches its preview surface, and renders observed session events.
    private lateinit var sessionRuntime: PhoneSessionRuntime
    private val streamConfig get() =
        if (::sessionRuntime.isInitialized) sessionRuntime.streamConfig else StreamConfig.Default1080p30
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reservationState get() = sessionRuntime.reservationState
    private val phoneSessionState get() = sessionRuntime.phoneSessionState
    private var activeTargetName: String? = null
    private var phoneConnected = false
    private var selectedObsHost: String? = null
    @Volatile private var activityStarted = false
    private var keepScreenOn = false
    private var displayOff = false
    private var originalBrightness = -1f
    private var torchOn = false
    private var currentLens = CameraLens.Back
    private var availableLenses: List<CameraLens> = listOf(CameraLens.Back)
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var zoomHideRunnable: Runnable? = null
    private var liveDotAnimator: ObjectAnimator? = null
    private val currentPort: Int get() = sessionRuntime.currentPort
    private var identifyHideRunnable: Runnable? = null
    private var pendingConnectAfterSettings = false
    private var pendingPairingIntent: Intent? = null
    private var currentDevices: List<DiscoveredObsDevice> = emptyList()
    private val activeStreamBitrate: Int get() = sessionRuntime.activeStreamBitrate
    private var lastObsSlotRenderKeys: List<String>? = null
    private var serviceBound = false
    private var serviceBinder: PhoneSessionService.LocalBinder? = null
    private var pendingPreviewSurface: android.view.Surface? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val ownerBinder = service as? PhoneSessionService.LocalBinder ?: return
            serviceBinder = ownerBinder
            ownerBinder.activityResumed()
            sessionRuntime = ownerBinder.runtime()
            activeTargetName = sessionRuntime.activeTargetName
            phoneConnected = sessionRuntime.phoneConnected
            selectedObsHost = sessionRuntime.selectedObsHost
            torchOn = sessionRuntime.torchEnabled
            currentLens = sessionRuntime.currentLens
            availableLenses = sessionRuntime.availableLenses
            currentDevices = sessionRuntime.currentDevices
            sessionRuntime.observer = this@MainActivity
            pendingPreviewSurface?.let(sessionRuntime::bindPreviewSurface)
            if (!sessionRuntime.isNativeSrtAvailable) {
                statusDetail.text = "SRT unavailable: ${sessionRuntime.nativeSrtError ?: "missing native lib"}"
            }
            if (activityStarted) {
                sessionRuntime.startComponents()
                onLiveStateChanged(sessionRuntime.activeTargetName)
            }
            pendingPairingIntent?.let(::handlePairingIntent)
            pendingPairingIntent = null
            renderDisconnectVisibility()
            onSessionStateChanged()
            if (pendingConnectAfterSettings && activityStarted) {
                pendingConnectAfterSettings = false
                startStream(connectionTargetFromSettings())
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBinder = null
            if (::sessionRuntime.isInitialized) sessionRuntime.observer = null
        }
    }

    private val reservedBy: String?
        get() = reservationState.confirmedSourceInstanceId

    private val reservedSlotLabel: String?
        get() = reservationState.confirmedReservation?.slotLabel?.takeIf { it.isNotBlank() }

    private val advertisedReservationId: String?
        get() = reservationState.advertisedSourceInstanceId

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
        setContentView(R.layout.activity_main)
        bindViews()
        setupGestureDetector()
        requestRuntimePermissions()
        bindPreviewSurface()
        setupButtons()
        setupNavBarInsets()
        pendingPairingIntent = intent
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        startAndBindSessionService()
    }

    override fun onResume() {
        super.onResume()
        serviceBinder?.activityResumed()
        // Reload listening port from settings if it changed
        val settingsPrefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
        val savedPort = settingsPrefs.getInt(SettingsActivity.KEY_LISTENING_PORT, ConnectionTarget.DEFAULT_PORT)
        if (::sessionRuntime.isInitialized && savedPort != currentPort && savedPort in 1024..65535) {
            changePort(savedPort)
        }
        if (::sessionRuntime.isInitialized && pendingConnectAfterSettings) {
            pendingConnectAfterSettings = false
            startStream(connectionTargetFromSettings())
        }
    }

    override fun onStop() {
        activityStarted = false
        stopLiveDotAnimation()
        mainHandler.removeCallbacks(statsTicker)
        serviceBinder?.activityHidden()
        if (::sessionRuntime.isInitialized) sessionRuntime.observer = null
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        identifyHideRunnable?.let(mainHandler::removeCallbacks)
        identifyHideRunnable = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 100) return
        val cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val audioGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!cameraGranted) {
            statusText.text = "Camera permission required"
            statusDetail.text = "Grant camera access to stream; audio continues muted"
            return
        }
        if (!audioGranted) {
            statusDetail.text = "Microphone denied; streaming video without audio"
        }
        startAndBindSessionService()
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
        if (::sessionRuntime.isInitialized) handlePairingIntent(intent) else pendingPairingIntent = intent
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
            if (!::sessionRuntime.isInitialized) return@setOnClickListener
            when (actionForSessionStatus(phoneSessionState.snapshot.status)) {
                PhoneSessionAction.Start -> startPhoneSession()
                PhoneSessionAction.Stop -> stopPhoneSession()
                PhoneSessionAction.Disconnect -> disconnectPhoneSession()
            }
        }

        // Tap the screen-off overlay to re-enable display
        screenOffOverlay.setOnClickListener { toggleDisplayOff() }
    }

    private fun handleMediaTransportFailure(sessionGeneration: Long) {
        if (::sessionRuntime.isInitialized) sessionRuntime.handleMediaTransportFailure(sessionGeneration)
    }

    private fun setupGestureDetector() {
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (::sessionRuntime.isInitialized) sessionRuntime.scaleZoom(detector.scaleFactor)
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
        if (::sessionRuntime.isInitialized) sessionRuntime.switchLens(lens)
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
        if (!::sessionRuntime.isInitialized) return
        // Only works on back-facing cameras
        if (currentLens.isFrontFacing) return
        torchOn = !torchOn
        val enabled = torchOn
        sessionRuntime.setTorch(enabled)
        setTorchUi(torchOn)
    }

    private fun setTorchUi(enabled: Boolean) {
        if (enabled) {
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
        // Diff by sourceInstanceId + visible state instead of rebuilding views on
        // every 1s discovery beacon. Beacons re-fire continuously while OBS is
        // alive; without this, each beacon paid removeAllViews() + N TextView
        // inflations on the UI thread even when nothing changed.
        val renderKeys = devices.map { device ->
            "${device.sourceInstanceId}|${device.displayLabel}|${device.busy}|${device.bitrateMbps}|${advertisedReservationId == device.sourceInstanceId}|$phoneConnected"
        }
        if (renderKeys == lastObsSlotRenderKeys) return
        lastObsSlotRenderKeys = renderKeys
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
            val isReservedForThisPhone = advertisedReservationId == device.sourceInstanceId
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
        if (device.busy && advertisedReservationId != device.sourceInstanceId) return
        selectedObsHost = device.host

        // UI-initiated selection is pending-only: it is advertised so the
        // matching OBS instance can POST /reserve, but it is not busy and
        // cannot authorize camera controls until /reserve confirms it. The
        // OBS host binds its peer IP on its next POST /reserve with the same
        // sourceInstanceId (the server allows adoption while unbound), so no
        // separate mirror call is needed and no network work happens here.
        // If connected to someone else and user explicitly taps a new slot, disconnect the old stream
        if (phoneConnected && reservedBy != device.sourceInstanceId) {
            stopStream(updateStatus = false)
        }

        if (selectForSource(device.sourceInstanceId, device.displayLabel, device.bitrateMbps)) {
            statusText.text = "Selected ${device.displayLabel}"
            statusDetail.text = "Waiting for OBS acknowledgement"
            renderDisconnectVisibility()
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
            selectedObsHost = uri.getQueryParameter("host")?.trim()?.ifEmpty { null }
            val slotLabel = uri.getQueryParameter("slotLabel")?.trim().orEmpty()
            val bitrateMbps = uri.getQueryParameter("bitrateMbps")?.toIntOrNull()
                ?.coerceIn(StreamConfig.MIN_BITRATE_MBPS, StreamConfig.MAX_BITRATE_MBPS)
            if (selectForSource(sourceInstanceId, slotLabel, bitrateMbps)) {
                statusText.text = "Selected ${slotLabel.ifBlank { "OBS computer" }}"
                statusDetail.text = "Waiting for OBS acknowledgement"
                renderDisconnectVisibility()
            }
            return
        }
        val target = ConnectionTarget.fromPairingUri(uri) ?: return
        startStream(target)
    }

    private fun startStream(target: ConnectionTarget) {
        if (::sessionRuntime.isInitialized) sessionRuntime.startStream(target)
    }

    private fun stopStream(updateStatus: Boolean = true) {
        if (::sessionRuntime.isInitialized) sessionRuntime.stopStream(updateStatus)
    }

    // ─────────────────────────── Live state UI ───────────────────────────

    private fun selectForSource(
        sourceInstanceId: String,
        slotLabel: String = "",
        bitrateMbps: Int? = null,
    ): Boolean {
        if (!::sessionRuntime.isInitialized) return false
        return sessionRuntime.selectForSource(
            sourceInstanceId = sourceInstanceId,
            slotLabel = slotLabel,
            bitrateMbps = bitrateMbps,
            obsHost = selectedObsHost,
        )
    }

    private fun renderDisconnectVisibility() {
        mainHandler.post {
            if (!::btnStop.isInitialized) return@post
            val sessionStatus = phoneSessionState.snapshot.status
            val shouldShow = sessionStatus == PhoneSessionStatus.Stopped ||
                sessionStatus == PhoneSessionStatus.Connecting ||
                sessionStatus == PhoneSessionStatus.Live ||
                reservationState.hasReservationToDisconnect || phoneConnected || activeTargetName != null
            btnStop.text = when (sessionStatus) {
                PhoneSessionStatus.Stopped -> PhoneSessionAction.Start.name
                PhoneSessionStatus.Live,
                PhoneSessionStatus.Connecting,
                PhoneSessionStatus.Reconnecting,
                -> PhoneSessionAction.Stop.name
                else -> PhoneSessionAction.Disconnect.name
            }
            btnStop.visibility = if (shouldShow) View.VISIBLE else View.GONE
        }
    }

    private fun stopPhoneSession() {
        requestSessionStop()
        statusText.text = getString(R.string.status_stopped)
        statusDetail.text = "Camera is stopped. Tap Start to make it available again."
        renderDisconnectVisibility()
    }

    private fun startPhoneSession() {
        requestSessionStart()
        statusText.text = getString(R.string.status_ready)
        statusDetail.text = getString(R.string.status_waiting)
        renderDisconnectVisibility()
    }

    private fun disconnectPhoneSession() {
        if (!::sessionRuntime.isInitialized) return
        sessionRuntime.disconnectReservation()
        statusText.text = "Disconnected"
        statusDetail.text = "Available for an OBS connection"
        renderDisconnectVisibility()
    }

    private fun showIdentifyOverlay(label: String, subtitle: String) {
        val text = if (subtitle.isBlank()) label else "$label\n$subtitle"
        identifyHideRunnable?.let(mainHandler::removeCallbacks)
        identifyOverlay.text = text
        identifyOverlay.visibility = View.VISIBLE
        identifyOverlay.bringToFront()
        val hide = Runnable { identifyOverlay.visibility = View.GONE }
        identifyHideRunnable = hide
        mainHandler.postDelayed(hide, IDENTIFY_OVERLAY_MS)
    }

    private fun showLiveState(targetName: String) {
        liveBadge.visibility = View.VISIBLE
        renderDisconnectVisibility()
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
        val stats = sessionRuntime.streamStats
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

    private fun bindPreviewSurface() {
        cameraPreview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                pendingPreviewSurface = holder.surface
                if (!::sessionRuntime.isInitialized) return
                sessionRuntime.bindPreviewSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                adjustPreviewAspectRatio()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                pendingPreviewSurface = null
                if (!::sessionRuntime.isInitialized) return
                sessionRuntime.bindPreviewSurface(null)
            }
        })
        cameraPreview.holder.surface.takeIf { it.isValid }?.let { surface ->
            pendingPreviewSurface = surface
        }
    }

    private fun startAndBindSessionService() {
        val intent = Intent(this, PhoneSessionService::class.java)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        }
        if (!serviceBound) serviceBound = bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun requestSessionStop() {
        startService(Intent(this, PhoneSessionService::class.java).setAction(PhoneSessionService.ACTION_STOP))
    }

    private fun requestSessionStart() {
        val intent = Intent(this, PhoneSessionService::class.java).setAction(PhoneSessionService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    override fun onDevicesChanged(devices: List<DiscoveredObsDevice>) {
        runOnUiThread {
            currentDevices = devices
            if (activityStarted) renderObsSlots(devices)
        }
    }

    override fun onLensListChanged(lenses: List<CameraLens>, selectedLens: CameraLens) {
        runOnUiThread {
            availableLenses = lenses
            currentLens = selectedLens
            if (activityStarted) buildLensButtons()
        }
    }

    override fun onTorchChanged(enabled: Boolean) {
        runOnUiThread {
            torchOn = enabled
            setTorchUi(enabled)
        }
    }

    override fun onZoomChanged(ratio: Float) {
        runOnUiThread { if (activityStarted) showZoomLabel(ratio) }
    }

    override fun onReservationChanged() {
        runOnUiThread {
            if (!activityStarted) return@runOnUiThread
            statusText.text = "Paired to ${reservedSlotLabel ?: "OBS computer"}"
            statusDetail.text = "OBS acknowledged; waiting to go live"
            renderDisconnectVisibility()
            renderObsSlots(currentDevices)
        }
    }

    override fun onSessionStateChanged() {
        runOnUiThread {
            if (!activityStarted) return@runOnUiThread
            when (phoneSessionState.snapshot.status) {
                PhoneSessionStatus.Available -> {
                    statusText.text = getString(R.string.status_ready)
                    statusDetail.text = getString(R.string.status_waiting)
                }
                PhoneSessionStatus.Stopped -> {
                    statusText.text = getString(R.string.status_stopped)
                    statusDetail.text = "Camera is stopped. Tap Start to make it available again."
                }
                else -> Unit
            }
            renderDisconnectVisibility()
        }
    }

    override fun onIdentify(label: String, subtitle: String) {
        runOnUiThread { showIdentifyOverlay(label, subtitle) }
    }

    override fun onSessionError(message: String) {
        runOnUiThread { if (activityStarted) statusDetail.text = message }
    }

    override fun onMediaTransportFailure(sessionGeneration: Long) {
        handleMediaTransportFailure(sessionGeneration)
    }

    override fun onLiveStateChanged(targetName: String?) {
        runOnUiThread {
            activeTargetName = targetName
            phoneConnected = targetName != null
            if (!activityStarted) return@runOnUiThread
            if (targetName == null) {
                hideLiveState()
                mainHandler.removeCallbacks(statsTicker)
            } else {
                showLiveState(targetName)
                mainHandler.removeCallbacks(statsTicker)
                mainHandler.post(statsTicker)
            }
        }
    }

    override fun onStatusChanged(title: String, detail: String) {
        runOnUiThread {
            if (!activityStarted) return@runOnUiThread
            statusText.text = title
            statusDetail.text = detail
        }
    }

    override fun onDisconnectVisibilityChanged() {
        runOnUiThread { if (activityStarted) renderDisconnectVisibility() }
    }

    private fun requestRuntimePermissions() {
        val required = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = required.filter {
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
        if (!::sessionRuntime.isInitialized) return
        val clamped = newPort.coerceIn(1024, 65535)
        if (clamped == currentPort) return
        sessionRuntime.changePort(clamped)
    }

    // ─────────────────────────── Preview aspect ratio fix ───────────────────────────

    private fun adjustPreviewAspectRatio() {
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
        private const val SETTINGS_REQUEST_CODE = 200
    }
}
