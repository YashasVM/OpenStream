package dev.openstream.app

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.openstream.app.camera.Camera2Controller
import dev.openstream.app.camera.CameraLens
import dev.openstream.app.control.CameraControlServer
import dev.openstream.app.discovery.DiscoveredObsDevice
import dev.openstream.app.discovery.ObsDiscoveryClient
import dev.openstream.app.discovery.PhoneDiscoveryAdvertiser
import dev.openstream.app.encoder.MediaCodecAudioEncoder
import dev.openstream.app.encoder.MediaCodecVideoEncoder
import dev.openstream.app.stream.ListenerAcceptCoordinator
import dev.openstream.app.stream.ConnectionTarget
import dev.openstream.app.stream.SrtAcceptResult
import dev.openstream.app.stream.StreamConfig
import dev.openstream.app.stream.SrtStreamClient

/** Activity-facing events; the runtime keeps working while no Activity is attached. */
internal interface PhoneSessionObserver {
    fun onDevicesChanged(devices: List<DiscoveredObsDevice>)
    fun onLensListChanged(lenses: List<CameraLens>, selectedLens: CameraLens)
    fun onTorchChanged(enabled: Boolean)
    fun onReservationChanged()
    fun onSessionStateChanged()
    fun onLiveStateChanged(targetName: String?)
    fun onStatusChanged(title: String, detail: String)
    fun onDisconnectVisibilityChanged()
    fun onIdentify(label: String, subtitle: String)
    fun onSessionError(message: String)
    fun onMediaTransportFailure(sessionGeneration: Long)
    fun onZoomChanged(ratio: Float)
}

/**
 * Process session graph created by [PhoneSessionService]. Activities bind a preview and observe
 * events; they do not create or dispose the camera, codecs, SRT, discovery, or control objects.
 */
internal class PhoneSessionRuntime(
    context: Context,
    initialPort: Int,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    val streamConfig = StreamConfig.Default1080p30
    val sessionWorker = SessionWorker { error ->
        Log.e("shin", "Session lifecycle operation failed", error)
    }
    val listenerAcceptCoordinator = ListenerAcceptCoordinator(sessionWorker, LISTENER_POLL_MS)
    val reservationState = ReservationState()
    val phoneSessionState = PhoneSessionState()
    @Volatile var currentLens: CameraLens = CameraLens.Back
    @Volatile var torchEnabled = false
    @Volatile var availableLenses: List<CameraLens> = listOf(CameraLens.Back)
    @Volatile var currentDevices: List<DiscoveredObsDevice> = emptyList()
    @Volatile var activeTargetName: String? = null
    @Volatile var phoneServerRunning = false
    @Volatile var phoneConnected = false
    @Volatile var selectedObsHost: String? = null
    @Volatile var callerConnecting = false
    @Volatile var callerGeneration = 0L
    @Volatile var pendingListenerStart = false
    @Volatile var listenerGeneration = 0L
    @Volatile var sessionWorkGeneration = 0L
    @Volatile private var previewBindingGeneration = 0L
    @Volatile var currentPort = initialPort
    @Volatile var activeStreamBitrate = streamConfig.bitrate
    @Volatile var observer: PhoneSessionObserver? = null
    @Volatile var listenerThread: Thread? = null
    val callerLifecycleLock = Any()
    val reservationLock = Any()
    @Volatile var releaseReservationRunnable: Runnable? = null
    @Volatile var reservationGeneration = 0L
    @Volatile private var previewSurfaceAvailable = false
    @Volatile private var requestedPreviewSurface: android.view.Surface? = null
    private var previewBindingRetry: Runnable? = null
    @Volatile private var stopResourcesPending = false

    val streamClient = SrtStreamClient()
    val camera = Camera2Controller(
        context = appContext,
        lensProvider = { currentLens },
        targetFps = streamConfig.fps,
    )
    @Volatile var encoder: MediaCodecVideoEncoder = createVideoEncoder(activeStreamBitrate)
        private set
    val audioEncoder = MediaCodecAudioEncoder(
        context = appContext,
        sampleRate = streamConfig.audioSampleRate,
        channelCount = streamConfig.audioChannelCount,
        bitrate = streamConfig.audioBitrate,
        onEncodedAccessUnit = { accessUnit ->
            val result = streamClient.sendAudioAccessUnit(accessUnit)
            if (result.recoveryRequired && streamClient.isCurrentSessionGeneration(result.sessionGeneration)) {
                observer?.onMediaTransportFailure(result.sessionGeneration)
            }
        },
    )
    @Volatile var phoneAdvertiser = createPhoneAdvertiser(currentPort)
        private set
    val obsDiscoveryClient = ObsDiscoveryClient(
        context = appContext,
        onDevicesChanged = { devices ->
            currentDevices = devices
            observer?.onDevicesChanged(devices)
        },
    )
    val controlServer = CameraControlServer(
        cameraProvider = { camera },
        lensListProvider = { availableLenses },
        currentLensProvider = { currentLens },
        onSwitchLens = ::switchLens,
        onToggleTorch = { enabled ->
            sessionWorker.submit {
                torchEnabled = enabled
                camera.setTorch(enabled)
                observer?.onTorchChanged(enabled)
            }
        },
        reservationProvider = { reservationState.confirmedSourceInstanceId },
        onReserve = { sourceInstanceId, slotLabel, bitrateMbps ->
            val accepted = reserve(sourceInstanceId, slotLabel, bitrateMbps)
            if (accepted) observer?.onReservationChanged()
            accepted
        },
        onRelease = { sourceInstanceId ->
            val released = release(sourceInstanceId)
            if (released) observer?.onReservationChanged()
            released
        },
        onIdentify = { label, subtitle -> observer?.onIdentify(label, subtitle) },
        onError = { message -> observer?.onSessionError(message) },
    )

    val reservedBy: String? get() = reservationState.confirmedSourceInstanceId
    val advertisedReservationId: String? get() = reservationState.advertisedSourceInstanceId
    val reservedSlotLabel: String?
        get() = reservationState.confirmedReservation?.slotLabel?.takeIf { it.isNotBlank() }
    val streamStats get() = streamClient.stats
    val isNativeSrtAvailable get() = streamClient.isNativeAvailable
    val nativeSrtError get() = streamClient.nativeLoadError?.message

    @Volatile private var componentsStarted = false

    fun startComponents() {
        if (phoneSessionState.snapshot.status == PhoneSessionStatus.Stopped) return
        synchronized(this) {
            if (componentsStarted) return
            componentsStarted = true
        }
        sessionWorker.submit {
            phoneAdvertiser.start()
            obsDiscoveryClient.start()
            controlServer.start()
        }
        initializeLenses()
        startPreview()
        startPhoneServerIfAllowed()
    }

    fun stopComponents() {
        synchronized(this) {
            if (!componentsStarted) return
            componentsStarted = false
        }
        submitCriticalTeardown {
            obsDiscoveryClient.stop()
            phoneAdvertiser.stop()
            controlServer.stop()
        }
    }

    fun changePort(port: Int) {
        val wasListenerMode = phoneServerRunning
        if (wasListenerMode) stopPhoneServer(clearReservation = false)
        currentPort = port.coerceIn(1024, 65535)
        val previous = phoneAdvertiser
        val replacement = createPhoneAdvertiser(currentPort)
        phoneAdvertiser = replacement
        sessionWorker.submit {
            previous.stop()
            replacement.start()
        }
        if (wasListenerMode) startPhoneServerIfAllowed()
    }

    fun selectForSource(
        sourceInstanceId: String,
        slotLabel: String,
        bitrateMbps: Int?,
        obsHost: String?,
    ): Boolean {
        if (phoneSessionState.snapshot.status == PhoneSessionStatus.Stopped) return false
        val selection = ReservationSelection(sourceInstanceId, slotLabel, bitrateMbps, obsHost)
        if (!reservationState.beginSelection(selection)) return false
        phoneSessionState.select(sourceInstanceId)
        selectedObsHost = obsHost
        replaceEncoderForBitrate(bitrateMbps)
        synchronized(reservationLock) { reservationGeneration += 1 }
        if (phoneConnected) cancelReservationRelease() else schedulePendingRelease(sourceInstanceId)
        return true
    }

    fun clearReservation() {
        cancelReservationRelease()
        reservationState.clear()
        selectedObsHost = null
        synchronized(reservationLock) { reservationGeneration += 1 }
        observer?.onDisconnectVisibilityChanged()
    }

    fun disconnectReservation() {
        phoneSessionState.disconnect()
        clearReservation()
        observer?.onReservationChanged()
        observer?.onSessionStateChanged()
    }

    fun stopLiveMedia() {
        callerGeneration += 1
        callerConnecting = false
        listenerGeneration += 1
        phoneServerRunning = false
        phoneConnected = false
        pendingListenerStart = false
        listenerThread?.interrupt()
        activeTargetName = null
        submitCriticalTeardown {
            streamClient.disconnect()
            camera.stopStreaming()
            encoder.stop()
            audioEncoder.stop()
            camera.stop()
        }
    }

    /** Stops every owned component as one critical worker task before the service exits. */
    fun stopSessionResources(onComplete: () -> Unit) {
        synchronized(this) {
            if (stopResourcesPending) return
            stopResourcesPending = true
        }
        callerGeneration += 1
        callerConnecting = false
        listenerGeneration += 1
        phoneServerRunning = false
        phoneConnected = false
        pendingListenerStart = false
        listenerThread?.interrupt()
        activeTargetName = null
        synchronized(this) { componentsStarted = false }
        val teardown = {
            try {
                streamClient.disconnect()
                val thread = listenerThread
                if (thread != null && thread !== Thread.currentThread()) {
                    runCatching { thread.join(LISTENER_STOP_TIMEOUT_MS) }
                }
                synchronized(callerLifecycleLock) {
                    camera.stopStreaming()
                    encoder.stop()
                    audioEncoder.stop()
                    camera.stop()
                }
                obsDiscoveryClient.stop()
                phoneAdvertiser.stop()
                controlServer.stop()
            } finally {
                synchronized(this) { stopResourcesPending = false }
                mainHandler.post(onComplete)
            }
        }
        val retry = object : Runnable {
            override fun run() {
                if (sessionWorker.submitCritical(teardown)) return
                if (sessionWorker.isShutdown) {
                    synchronized(this@PhoneSessionRuntime) { stopResourcesPending = false }
                    Log.e("shin", "Session worker closed before Stop teardown could be queued")
                    return
                }
                synchronized(this@PhoneSessionRuntime) {
                    if (!stopResourcesPending) return
                }
                mainHandler.postDelayed(this, TEARDOWN_RETRY_MS)
            }
        }
        retry.run()
    }

    fun bindPreviewSurface(surface: android.view.Surface?) {
        val requestedSurface = surface?.takeIf(android.view.Surface::isValid)
        val generation = ++previewBindingGeneration
        requestedPreviewSurface = requestedSurface
        previewSurfaceAvailable = requestedSurface != null
        if (enqueuePreviewBinding(generation, requestedSurface)) {
            synchronized(this) {
                previewBindingRetry?.let(mainHandler::removeCallbacks)
                previewBindingRetry = null
            }
        } else {
            schedulePreviewBindingRetry()
        }
    }

    private fun enqueuePreviewBinding(generation: Long, surface: android.view.Surface?): Boolean =
        sessionWorker.submitIfCurrent(generation, { previewBindingGeneration }) {
            if (generation != previewBindingGeneration) return@submitIfCurrent
            camera.bindPreviewSurface(surface)
            if (generation != previewBindingGeneration) return@submitIfCurrent
            if (surface != null) {
                initializeLenses()
                if (phoneSessionState.snapshot.status != PhoneSessionStatus.Stopped &&
                    appContext.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                ) camera.startPreview()
                startPhoneServerIfAllowed()
            } else if (!phoneConnected && activeTargetName == null && !callerConnecting) {
                camera.stop()
            }
        }

    private fun schedulePreviewBindingRetry() {
        synchronized(this) {
            if (previewBindingRetry != null) return
            val retry = object : Runnable {
                override fun run() {
                    synchronized(this@PhoneSessionRuntime) { previewBindingRetry = null }
                    if (sessionWorker.isShutdown) {
                        Log.e("shin", "Session worker closed before preview surface could be rebound")
                        observer?.onSessionError("Camera preview could not be updated")
                        return
                    }
                    val generation = previewBindingGeneration
                    if (enqueuePreviewBinding(generation, requestedPreviewSurface)) return
                    schedulePreviewBindingRetry()
                }
            }
            previewBindingRetry = retry
            mainHandler.postDelayed(retry, TEARDOWN_RETRY_MS)
        }
    }

    fun startPreview() {
        if (phoneSessionState.snapshot.status == PhoneSessionStatus.Stopped || !previewSurfaceAvailable ||
            appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) return
        val generation = sessionWorkGeneration
        sessionWorker.submitIfCurrent(generation, { sessionWorkGeneration }) {
            if (previewSurfaceAvailable && phoneSessionState.snapshot.status != PhoneSessionStatus.Stopped &&
                appContext.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            ) camera.startPreview()
        }
    }

    fun scaleZoom(factor: Float) {
        sessionWorker.submit {
            val ratio = camera.scaleZoom(factor)
            observer?.onZoomChanged(ratio)
        }
    }

    fun startStream(target: ConnectionTarget) {
        if (phoneSessionState.snapshot.status == PhoneSessionStatus.Stopped) return
        stopStream(updateStatus = false)
        stopPhoneServer(clearReservation = true)
        replaceEncoderForBitrate(target.bitrateMbps)
        observer?.onStatusChanged("Connecting…", "${currentLens.displayName} → ${target.name}")
        val sessionGeneration = phoneSessionState.beginConnection()
        val generation = callerGeneration + 1
        callerGeneration = generation
        callerConnecting = true
        observer?.onDisconnectVisibilityChanged()
        val accepted = sessionWorker.submit {
            try {
                streamClient.connect(
                    url = target.toSrtCallerUrl(),
                    codecMime = encoder.codecName,
                    width = streamConfig.width,
                    height = streamConfig.height,
                    fps = streamConfig.fps,
                )
                check(callerGeneration == generation) { "SRT caller connection was cancelled" }
                check(phoneSessionState.snapshot.generation == sessionGeneration) { "Phone session was superseded" }
                synchronized(callerLifecycleLock) {
                    encoder.start()
                    startAudioIfAllowed()
                    camera.startStreaming(encoder.inputSurface())
                }
                mainHandler.post {
                    if (callerGeneration != generation || phoneSessionState.snapshot.generation != sessionGeneration) return@post
                    phoneSessionState.connected(sessionGeneration)
                    activeTargetName = target.name
                    observer?.onLiveStateChanged(target.name)
                    observer?.onSessionStateChanged()
                }
            } catch (error: Throwable) {
                synchronized(callerLifecycleLock) {
                    if (callerGeneration == generation) {
                        streamClient.disconnect()
                        camera.stopStreaming()
                        encoder.stop()
                        audioEncoder.stop()
                    }
                }
                mainHandler.post {
                    if (callerGeneration != generation) return@post
                    phoneSessionState.failed(sessionGeneration)
                    startPreview()
                    startPhoneServerIfAllowed()
                    observer?.onStatusChanged("Connection failed", error.message ?: "Unknown error")
                }
            } finally {
                if (callerGeneration == generation) callerConnecting = false
            }
        }
        if (!accepted) {
            callerConnecting = false
            phoneSessionState.failed(sessionGeneration)
            observer?.onStatusChanged("Session busy", "Wait for the current camera transition to finish")
            observer?.onSessionStateChanged()
        }
    }

    fun stopStream(updateStatus: Boolean = true) {
        callerGeneration += 1
        callerConnecting = false
        activeTargetName = null
        phoneConnected = false
        observer?.onLiveStateChanged(null)
        submitCriticalTeardown {
            streamClient.disconnect()
            synchronized(callerLifecycleLock) {
                camera.stopStreaming()
                encoder.stop()
                audioEncoder.stop()
            }
            if (updateStatus) observer?.onStatusChanged("Stopped", "Camera preview remains active")
        }
    }

    fun handleMediaTransportFailure(sessionGeneration: Long) {
        if (!streamClient.isCurrentSessionGeneration(sessionGeneration)) return
        if (phoneServerRunning) {
            if (activeTargetName != null) observer?.onStatusChanged("Connection lost", "Waiting for OBS to reconnect")
            return
        }
        if (activeTargetName == null && !callerConnecting) return
        phoneConnected = false
        phoneSessionState.connectionLost(phoneSessionState.snapshot.generation)
        stopStream(updateStatus = false)
        startPreview()
        sessionWorker.submit { mainHandler.post { startPhoneServerIfAllowed() } }
        observer?.onStatusChanged("Connection lost", "Waiting for OBS")
        observer?.onSessionStateChanged()
    }

    fun startPhoneServerIfAllowed() {
        if (phoneSessionState.snapshot.status == PhoneSessionStatus.Stopped || phoneServerRunning) return
        if (listenerThread?.isAlive == true) {
            pendingListenerStart = true
            Log.w("shin", "Previous SRT listener is still stopping; not starting another")
            return
        }
        if (appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

        pendingListenerStart = false
        val generation = listenerGeneration + 1
        listenerGeneration = generation
        phoneServerRunning = true
        phoneConnected = false
        activeTargetName = null
        observer?.onStatusChanged("Ready", "Waiting for OBS")
        observer?.onLiveStateChanged(null)
        observer?.onDisconnectVisibilityChanged()

        val thread = Thread({
            try {
                while (isListenerActive(generation)) {
                    val listenUrl = "srt://0.0.0.0:$currentPort?mode=listener&latency=${streamConfig.latencyMs}"
                    val attemptGeneration = phoneSessionState.snapshot.generation
                    var connectedSessionGeneration: Long? = null
                    val listenResult = runCatching {
                        val acceptResult = listenerAcceptCoordinator.awaitAccepted(
                            expectedGeneration = generation,
                            currentGeneration = { listenerGeneration },
                            startListening = {
                                streamClient.startListening(
                                    url = listenUrl,
                                    codecMime = encoder.codecName,
                                    width = streamConfig.width,
                                    height = streamConfig.height,
                                    fps = streamConfig.fps,
                                )
                            },
                            acceptPending = streamClient::acceptPending,
                        )
                        if (!isListenerActive(generation)) return@runCatching
                        check(acceptResult == SrtAcceptResult.Connected) {
                            "SRT listener accept failed: $acceptResult"
                        }
                        phoneConnected = true
                        val sessionGeneration = phoneSessionState.beginConnection()
                        connectedSessionGeneration = sessionGeneration
                        cancelReservationRelease()
                        val liveTargetName = reservedSlotLabel ?: "OBS"
                        activeTargetName = liveTargetName
                        observer?.onLiveStateChanged(liveTargetName)
                        sessionWorker.submitIfCurrent(generation, { listenerGeneration }) {
                            runCatching {
                                encoder.start()
                                startAudioIfAllowed()
                                camera.startStreaming(encoder.inputSurface())
                                check(phoneSessionState.connected(sessionGeneration)) {
                                    "Phone session was superseded"
                                }
                            }.onFailure { error ->
                                if (isListenerActive(generation)) {
                                    phoneConnected = false
                                    phoneSessionState.failed(sessionGeneration)
                                    observer?.onStatusChanged("Listener error", error.message ?: "Unknown error")
                                    submitCriticalTeardown {
                                        camera.stopStreaming()
                                        encoder.stop()
                                        audioEncoder.stop()
                                    }
                                }
                            }
                        }
                        while (isListenerActive(generation) && phoneConnected) Thread.sleep(LISTENER_POLL_MS)
                    }

                    if (listenResult.isFailure && isListenerActive(generation)) {
                        val error = listenResult.exceptionOrNull()
                        phoneSessionState.failed(connectedSessionGeneration ?: attemptGeneration)
                        observer?.onStatusChanged("Listener error", error?.message ?: "Unknown")
                        try {
                            Thread.sleep(LISTENER_RETRY_MS)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }

                    if (isListenerActive(generation)) {
                        submitCriticalTeardown {
                            camera.stopStreaming()
                            encoder.stop()
                            audioEncoder.stop()
                        }
                    }
                    phoneConnected = false
                    activeTargetName = null
                    if (isListenerActive(generation)) {
                        connectedSessionGeneration?.let(phoneSessionState::connectionLost)
                        scheduleReservationRelease()
                        observer?.onLiveStateChanged(null)
                        observer?.onStatusChanged("Ready", reservedSlotLabel?.let { "Holding $it for reconnect" } ?: "Waiting for OBS")
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
        }, "shinPhoneSrtListener").apply { isDaemon = true }
        listenerThread = thread
        thread.start()
    }

    fun stopPhoneServer(clearReservation: Boolean, stopCamera: Boolean = false) {
        callerGeneration += 1
        callerConnecting = false
        pendingListenerStart = false
        listenerGeneration += 1
        phoneServerRunning = false
        phoneConnected = false
        if (clearReservation) clearReservation()
        activeTargetName = null
        listenerThread?.interrupt()
        observer?.onLiveStateChanged(null)
        observer?.onDisconnectVisibilityChanged()
        submitCriticalTeardown {
            val thread = listenerThread
            streamClient.disconnect()
            if (thread != null && thread !== Thread.currentThread()) runCatching { thread.join(LISTENER_STOP_TIMEOUT_MS) }
            synchronized(callerLifecycleLock) {
                camera.stopStreaming()
                encoder.stop()
                audioEncoder.stop()
                if (stopCamera) camera.stop()
            }
            if (pendingListenerStart) startPhoneServerIfAllowed()
        }
    }

    private fun startAudioIfAllowed() {
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.i("shin", "Microphone permission not granted; streaming video without audio")
            return
        }
        runCatching { audioEncoder.start() }
            .onFailure { error -> Log.w("shin", "Audio encoder start failed; continuing video-only", error) }
    }

    private fun isListenerActive(generation: Long): Boolean =
        phoneServerRunning && listenerGeneration == generation

    private fun scheduleReservationRelease() {
        val sourceInstanceId = reservationState.confirmedSourceInstanceId ?: return
        val generation = reservationGeneration
        val sessionGeneration = phoneSessionState.snapshot.generation
        cancelReservationRelease()
        releaseReservationRunnable = Runnable {
            synchronized(reservationLock) {
                if (!phoneConnected && reservationState.confirmedSourceInstanceId == sourceInstanceId &&
                    reservationGeneration == generation
                ) {
                    reservationState.release(sourceInstanceId)
                    phoneSessionState.timeout(sessionGeneration)
                    observer?.onReservationChanged()
                    if (reservationState.confirmedReservation == null && reservationState.pendingSelection == null) {
                        selectedObsHost = null
                        observer?.onStatusChanged("Disconnected", "Choose an OBS slot to reconnect")
                    }
                }
            }
        }
        mainHandler.postDelayed(releaseReservationRunnable!!, RECONNECT_RESERVATION_MS)
    }

    private fun cancelReservationRelease() {
        releaseReservationRunnable?.let(mainHandler::removeCallbacks)
        releaseReservationRunnable = null
    }

    /** Retries one bounded critical submission until the worker frees its reserved slot. */
    private fun submitCriticalTeardown(work: () -> Unit) {
        val retry = object : Runnable {
            override fun run() {
                if (sessionWorker.submitCritical(work)) return
                if (sessionWorker.isShutdown) {
                    Log.e("shin", "Session worker closed before critical teardown could be queued")
                    observer?.onSessionError("Could not stop the camera session cleanly")
                    return
                }
                mainHandler.postDelayed(this, TEARDOWN_RETRY_MS)
            }
        }
        retry.run()
    }

    private fun schedulePendingRelease(sourceInstanceId: String) {
        val generation = reservationGeneration
        val sessionGeneration = phoneSessionState.snapshot.generation
        cancelReservationRelease()
        releaseReservationRunnable = Runnable {
            synchronized(reservationLock) {
                if (!phoneConnected && reservationGeneration == generation &&
                    reservationState.confirmedSourceInstanceId == null
                ) {
                    reservationState.rollbackPending(sourceInstanceId)
                    phoneSessionState.timeout(sessionGeneration)
                    observer?.onReservationChanged()
                    observer?.onSessionStateChanged()
                    if (reservationState.pendingSelection == null) {
                        selectedObsHost = null
                        observer?.onStatusChanged("Connection timed out", "Choose an OBS slot to try again")
                    }
                }
            }
        }
        mainHandler.postDelayed(releaseReservationRunnable!!, RECONNECT_RESERVATION_MS)
    }

    fun createVideoEncoder(bitrate: Int): MediaCodecVideoEncoder = MediaCodecVideoEncoder(
        preference = streamConfig.codecPreference,
        width = streamConfig.width,
        height = streamConfig.height,
        fps = streamConfig.fps,
        bitrate = bitrate,
        keyframeIntervalSeconds = streamConfig.keyframeIntervalSeconds,
        onEncodedAccessUnit = { accessUnit ->
            val result = streamClient.sendVideoAccessUnit(accessUnit)
            if (result.recoveryRequired && streamClient.isCurrentSessionGeneration(result.sessionGeneration)) {
                observer?.onMediaTransportFailure(result.sessionGeneration)
            }
        },
    )

    fun replaceEncoderForBitrate(bitrateMbps: Int?) {
        val nextBitrate = (bitrateMbps ?: streamConfig.bitrateMbps)
            .coerceIn(StreamConfig.MIN_BITRATE_MBPS, StreamConfig.MAX_BITRATE_MBPS) * 1_000_000
        synchronized(callerLifecycleLock) {
            if (activeStreamBitrate == nextBitrate) return
            activeStreamBitrate = nextBitrate
            if (activeTargetName == null) {
                val previous = encoder
                encoder = createVideoEncoder(nextBitrate)
                submitCriticalTeardown { previous.stop() }
            }
        }
    }

    fun switchLens(lens: CameraLens) {
        if (lens == currentLens) return
        val wasStreaming = activeTargetName != null
        currentLens = lens
        torchEnabled = false
        observer?.onTorchChanged(false)
        val generation = ++sessionWorkGeneration
        observer?.onLensListChanged(availableLenses, lens)
        sessionWorker.submitIfCurrent(generation, { sessionWorkGeneration }) {
            runCatching {
                if (wasStreaming) {
                    camera.stopStreaming()
                    encoder.stop()
                }
                camera.switchLens(lens)
            }.onFailure { error ->
                observer?.onSessionError(error.message ?: "Lens switch failed")
            }
        }
        if (wasStreaming) {
            mainHandler.postDelayed({
                sessionWorker.submitIfCurrent(generation, { sessionWorkGeneration }) {
                    if (activeTargetName != null) runCatching {
                        synchronized(callerLifecycleLock) {
                            encoder.start()
                            camera.startStreaming(encoder.inputSurface())
                        }
                    }.onFailure { error -> observer?.onSessionError(error.message ?: "Encoder restart failed") }
                }
            }, LENS_RESTART_DELAY_MS)
        }
    }

    fun setTorch(enabled: Boolean) {
        if (currentLens.isFrontFacing) return
        torchEnabled = enabled
        sessionWorker.submit {
            camera.setTorch(enabled)
            observer?.onTorchChanged(enabled)
        }
    }

    fun initializeLenses() {
        if (appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val generation = sessionWorkGeneration
        sessionWorker.submitIfCurrent(generation, { sessionWorkGeneration }) {
            val lenses = camera.availableLenses()
            if (sessionWorkGeneration != generation) return@submitIfCurrent
            availableLenses = lenses
            if (currentLens !in lenses) currentLens = lenses.firstOrNull { it.isBackFacing } ?: lenses.first()
            observer?.onLensListChanged(lenses, currentLens)
        }
    }

    private fun createPhoneAdvertiser(port: Int) = PhoneDiscoveryAdvertiser(
        context = appContext,
        config = streamConfig,
        port = port,
        busyProvider = { reservationState.isBusy(phoneConnected) },
        reservedByProvider = { reservationState.advertisedSourceInstanceId },
        selectedObsHostProvider = { selectedObsHost },
    )

    private fun reserve(sourceInstanceId: String, slotLabel: String, bitrateMbps: Int?): Boolean {
        if (phoneSessionState.snapshot.status == PhoneSessionStatus.Stopped) return false
        if (phoneConnected && reservedBy != null && reservedBy != sourceInstanceId) return false
        val effectiveSlot = slotLabel.ifBlank { reservedSlotLabel.orEmpty() }
        if (!reservationState.confirm(sourceInstanceId, effectiveSlot, bitrateMbps)) return false
        if (!phoneSessionState.reserve(sourceInstanceId)) return false
        replaceEncoderForBitrate(bitrateMbps)
        synchronized(reservationLock) { reservationGeneration += 1 }
        if (phoneConnected) cancelReservationRelease() else scheduleReservationRelease()
        return true
    }

    private fun release(sourceInstanceId: String): Boolean {
        val confirmed = reservationState.confirmedSourceInstanceId
        val pending = reservationState.pendingSourceInstanceId
        if (confirmed == sourceInstanceId || (confirmed == null && pending == sourceInstanceId)) {
            phoneSessionState.disconnect()
            clearReservation()
            return true
        }
        return confirmed == null && pending == null
    }

    companion object {
        const val LISTENER_POLL_MS = 250L
        private const val TEARDOWN_RETRY_MS = 50L
        private const val LENS_RESTART_DELAY_MS = 500L
        private const val LISTENER_RETRY_MS = 750L
        private const val LISTENER_STOP_TIMEOUT_MS = 2_000L
        private const val RECONNECT_RESERVATION_MS = 45_000L
    }
}
