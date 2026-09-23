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
    fun onLensRequested(lens: CameraLens)
    fun onTorchChanged(enabled: Boolean)
    fun onReservationChanged()
    fun onSessionStateChanged()
    fun onLiveStateChanged(targetName: String?)
    fun onStatusChanged(title: String, detail: String)
    fun onDisconnectVisibilityChanged()
    fun onIdentify(label: String, subtitle: String)
    fun onSessionError(message: String)
    fun onMediaTransportFailure(sessionGeneration: Long)
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
    @Volatile var currentPort = initialPort
    @Volatile var activeStreamBitrate = streamConfig.bitrate
    @Volatile var observer: PhoneSessionObserver? = null
    @Volatile var listenerThread: Thread? = null
    val callerLifecycleLock = Any()
    val reservationLock = Any()
    @Volatile var releaseReservationRunnable: Runnable? = null
    @Volatile var reservationGeneration = 0L

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
        onSwitchLens = { lens -> observer?.onLensRequested(lens) ?: switchLens(lens) },
        onToggleTorch = { enabled ->
            sessionWorker.submit {
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

    @Volatile private var componentsStarted = false

    fun startComponents() {
        synchronized(this) {
            if (componentsStarted) return
            componentsStarted = true
        }
        sessionWorker.submit {
            phoneAdvertiser.start()
            obsDiscoveryClient.start()
            controlServer.start()
        }
    }

    fun stopComponents() {
        synchronized(this) {
            if (!componentsStarted) return
            componentsStarted = false
        }
        sessionWorker.submit {
            obsDiscoveryClient.stop()
            phoneAdvertiser.stop()
            controlServer.stop()
        }
    }

    fun changePort(port: Int) {
        currentPort = port.coerceIn(1024, 65535)
        val previous = phoneAdvertiser
        val replacement = createPhoneAdvertiser(currentPort)
        phoneAdvertiser = replacement
        sessionWorker.submit {
            previous.stop()
            replacement.start()
        }
    }

    fun clearReservation() {
        reservationState.clear()
        selectedObsHost = null
        synchronized(reservationLock) { reservationGeneration += 1 }
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
        streamClient.disconnect()
        sessionWorker.submit {
            camera.stopStreaming()
            encoder.stop()
            audioEncoder.stop()
            camera.stop()
        }
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
                                    sessionWorker.submit {
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
                        sessionWorker.submit {
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
        streamClient.disconnect()
        observer?.onLiveStateChanged(null)
        observer?.onDisconnectVisibilityChanged()
        sessionWorker.submit {
            val thread = listenerThread
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
                sessionWorker.submit { previous.stop() }
            }
        }
    }

    fun switchLens(lens: CameraLens) {
        currentLens = lens
        sessionWorker.submit { camera.switchLens(lens) }
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
        return true
    }

    private fun release(sourceInstanceId: String): Boolean {
        val confirmed = reservationState.confirmedSourceInstanceId
        val pending = reservationState.pendingSourceInstanceId
        if (confirmed == sourceInstanceId || (confirmed == null && pending == sourceInstanceId)) {
            phoneSessionState.disconnect()
            reservationState.clear()
            selectedObsHost = null
            synchronized(reservationLock) { reservationGeneration += 1 }
            return true
        }
        return confirmed == null && pending == null
    }

    companion object {
        const val LISTENER_POLL_MS = 50L
        private const val LISTENER_RETRY_MS = 1_000L
        private const val LISTENER_STOP_TIMEOUT_MS = 1_000L
        private const val RECONNECT_RESERVATION_MS = 30_000L
    }
}
