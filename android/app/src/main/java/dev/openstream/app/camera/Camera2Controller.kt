package dev.openstream.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Rational
import android.view.Surface
import kotlin.math.max
import kotlin.math.min

/**
 * Camera2 owner for both attended and remote operation.
 *
 * Every repeating request is derived from [CameraStateStore]. This is important: a zoom,
 * torch or focus command can never silently reset exposure, white balance or stabilization.
 */
class Camera2Controller(
    private val context: Context,
    private val previewSurfaceProvider: () -> Surface,
    private val lensProvider: () -> CameraLens = { CameraLens.defaultBack() },
    private val stateStore: CameraStateStore = CameraStateStore(),
) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("OpenStreamCamera")
    private lateinit var handler: Handler
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var streamingSurface: Surface? = null
    private var activeCameraId: String? = null
    private var activeLens: CameraLens? = null
    private var pendingLensZoom: Float? = null
    private var characteristics: CameraCharacteristics? = null
    private var sensorRect: Rect? = null
    private var sensorOrientation: Int = 0
    private var frontFacing: Boolean = false
    private var focusRegion: MeteringRectangle? = null
    private var lastTelemetryPublishNs = 0L
    private var zoomTransitionRunnable: Runnable? = null
    private var transitionZoomOverride: Float? = null
    @Volatile private var fallbackPreviewSurface: Surface? = null
    @Volatile private var preferFallbackPreviewSurface = false

    val zoomRatio: Float get() = stateStore.snapshot().settings.zoomRatio
    val zoomRange: ClosedFloatingPointRange<Float>
        get() = stateStore.capabilities()?.zoomRange?.let { it.min..it.max } ?: 1f..1f

    fun currentState(): CameraState = stateStore.snapshot()
    fun currentCapabilities(): CameraCapabilities? = stateStore.capabilities()
    fun isReadyForZoomTransition(): Boolean = currentCapabilities() != null && camera != null && session != null
    fun addStateListener(listener: (CameraState) -> Unit): AutoCloseable = stateStore.addListener(listener)

    fun setFallbackPreviewSurface(surface: Surface?) {
        fallbackPreviewSurface = surface
    }

    fun useFallbackPreviewSurface(enabled: Boolean) {
        preferFallbackPreviewSurface = enabled
        refreshPreviewSurface()
    }

    /** Rebuild after the Activity preview surface is created or destroyed. */
    fun refreshPreviewSurface() {
        if (camera != null) createSession()
    }

    fun availableLenses(): List<CameraLens> {
        val result = mutableListOf<CameraLens>()
        val back = cameraManager.cameraIdList.filter { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
        val front = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT
        }
        val logical = back.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).physicalCameraIds.isNotEmpty()
        }
        if (logical != null) {
            val caps = capabilitiesFor(logical)
            val physicalCandidates = cameraManager.getCameraCharacteristics(logical).physicalCameraIds
                .mapNotNull(::lensCandidateFor)
            result += CameraLensDiscovery.rearLenses(
                logicalCameraId = logical,
                candidates = physicalCandidates.ifEmpty { listOfNotNull(lensCandidateFor(logical)) },
                supportsLogicalZoomRatio = caps.supportsZoomRatio,
            )
        } else {
            result += CameraLensDiscovery.rearLenses(
                logicalCameraId = null,
                candidates = back.mapNotNull(::lensCandidateFor),
                supportsLogicalZoomRatio = false,
            )
        }
        front?.let { result += CameraLens.selfie(it) }
        return result.ifEmpty { listOf(CameraLens.defaultBack()) }
    }

    @SuppressLint("MissingPermission")
    fun startPreview() {
        ensureThread()
        val desiredLens = activeLens ?: lensProvider()
        val desiredId = selectCameraId(desiredLens)
        if (camera != null && activeCameraId == desiredId) {
            createSession()
            return
        }
        closeCamera()
        activeLens = desiredLens
        activeCameraId = desiredId
        pendingLensZoom = desiredLens.targetZoom
        cameraManager.openCamera(desiredId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                loadCamera(desiredId)
                pendingLensZoom?.let(::setZoom)
                pendingLensZoom = null
                createSession()
            }

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera disconnected")
                closeCamera()
            }

            override fun onError(device: CameraDevice, error: Int) {
                Log.e(TAG, "Camera error: $error")
                closeCamera()
            }
        }, handler)
    }

    fun switchLens(lens: CameraLens) {
        cancelZoomTransition()
        val newId = selectCameraId(lens)
        val previousId = activeCameraId
        activeLens = lens
        if (newId == previousId && camera != null) {
            setZoom(lens.targetZoom)
            return
        }
        activeCameraId = newId
        pendingLensZoom = lens.targetZoom
        focusRegion = null
        closeCamera()
        startPreview()
    }

    fun startStreaming(encodedSurface: Surface) {
        streamingSurface = encodedSurface
        if (camera == null) startPreview() else createSession()
    }

    fun stopStreaming() {
        streamingSurface = null
        if (camera != null) createSession()
    }

    fun stop() {
        cancelZoomTransition()
        closeCamera()
        streamingSurface = null
    }

    fun applySettings(
        patch: CameraSettingsPatch,
        expectedRevision: Long? = null,
        actor: CameraActor = CameraActor.Camera,
    ): CameraControlResult {
        if (patch.zoomRatio != null) cancelZoomTransition()
        val result = stateStore.applySettings(expectedRevision, actor, patch)
        if (result is CameraControlResult.Applied) rebuildRepeatingRequest()
        return result
    }

    fun focusAt(
        normalizedX: Float,
        normalizedY: Float,
        mode: FocusActionMode = FocusActionMode.Auto,
        expectedRevision: Long? = null,
        actor: CameraActor = CameraActor.Camera,
    ): CameraControlResult {
        val result = stateStore.applyFocus(expectedRevision, actor, normalizedX, normalizedY)
        if (result !is CameraControlResult.Applied) return result
        val active = sensorRect ?: return CameraControlResult.Unsupported("focus", "Sensor geometry is unavailable", currentState())
        val mapped = FocusCoordinateMapper.mapToMeteringRegion(
            normalizedX = normalizedX,
            normalizedY = normalizedY,
            activeArray = active.toSensorRect(),
            cropRegion = calculateCrop(currentState().settings.zoomRatio).toSensorRect(),
            rotationDegrees = sensorOrientation,
            mirrored = frontFacing,
        )
        focusRegion = MeteringRectangle(mapped.left, mapped.top, mapped.width, mapped.height, MeteringRectangle.METERING_WEIGHT_MAX)
        triggerFocus(mode)
        return result
    }

    fun setAuthority(
        mode: AuthorityMode,
        expectedRevision: Long? = null,
        actor: CameraActor = CameraActor.Obs,
    ): CameraControlResult = stateStore.setAuthority(expectedRevision, actor, mode)

    fun setTally(program: Boolean, preview: Boolean): CameraState = stateStore.setTally(program, preview)

    fun startZoomTransition(
        targetRatio: Float,
        durationMs: Int,
        expectedRevision: Long,
        actor: CameraActor = CameraActor.Obs,
    ): CameraControlResult {
        if (!isReadyForZoomTransition()) {
            return CameraControlResult.Unsupported("camera", "Camera is not ready", currentState())
        }
        val result = stateStore.startZoomTransition(expectedRevision, actor, targetRatio, durationMs)
        if (result is CameraControlResult.Applied) {
            val transition = result.state.zoomTransition ?: return result
            ensureThread()
            handler.post { runZoomTransition(transition) }
        }
        return result
    }

    fun setZoom(ratio: Float): Float {
        val clamped = stateStore.capabilities()?.zoomRange?.clamp(ratio) ?: ratio.coerceAtLeast(1f)
        applySettings(CameraSettingsPatch(zoomRatio = clamped))
        return currentState().settings.zoomRatio
    }

    fun scaleZoom(scaleFactor: Float): Float = setZoom(zoomRatio * scaleFactor)

    fun setManualExposure(iso: Int, exposureTimeNs: Long) {
        applySettings(
            CameraSettingsPatch(
                exposureMode = ExposureMode.Manual,
                iso = iso,
                shutterNs = exposureTimeNs,
            ),
        )
    }

    fun setTorch(enabled: Boolean) {
        applySettings(CameraSettingsPatch(torch = enabled))
    }

    private fun ensureThread() {
        if (!thread.isAlive) thread.start()
        handler = Handler(thread.looper)
    }

    private fun closeCamera() {
        cancelZoomTransition()
        session?.close()
        camera?.close()
        session = null
        camera = null
    }

    private fun loadCamera(cameraId: String) {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        characteristics = chars
        sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        frontFacing = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        stateStore.setCapabilities(capabilitiesFor(cameraId))
    }

    private fun capabilitiesFor(cameraId: String): CameraCapabilities {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val requestCapabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet().orEmpty()
        val awbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet().orEmpty()
        val minFocusDistance = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val focusModes = buildSet {
            if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes) add(FocusMode.Continuous)
            if (CaptureRequest.CONTROL_AF_MODE_AUTO in afModes || CaptureRequest.CONTROL_AF_MODE_MACRO in afModes) add(FocusMode.Single)
            if (minFocusDistance > 0f) add(FocusMode.Manual)
        }
        val whiteBalanceModes = buildSet {
            if (CaptureRequest.CONTROL_AWB_MODE_AUTO in awbModes) add(WhiteBalanceMode.Auto)
            if (CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT in awbModes) add(WhiteBalanceMode.Daylight)
            if (CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT in awbModes) add(WhiteBalanceMode.Cloudy)
            if (CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT in awbModes) add(WhiteBalanceMode.Incandescent)
            if (CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT in awbModes) add(WhiteBalanceMode.Fluorescent)
            if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in requestCapabilities) add(WhiteBalanceMode.Manual)
        }
        val opticalModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toSet().orEmpty()
        val videoModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toSet().orEmpty()
        val stabilizationModes = buildSet {
            add(StabilizationMode.Off)
            if (CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON in opticalModes) add(StabilizationMode.Optical)
            if (CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON in videoModes) add(StabilizationMode.Video)
        }
        val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { FloatValueRange(it.lower, it.upper) }
        } else null
        val maxDigitalZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val resolvedZoomRange = zoomRange ?: FloatValueRange(1f, maxDigitalZoom.coerceAtLeast(1f))
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { IntValueRange(it.lower, it.upper) }
        val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { LongValueRange(it.lower, it.upper) }
        val compensationRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let {
            if (it.lower == 0 && it.upper == 0) null else IntValueRange(it.lower, it.upper)
        }
        val facing = chars.get(CameraCharacteristics.LENS_FACING)
        val logical = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in requestCapabilities
        val name = when (facing) {
            CameraCharacteristics.LENS_FACING_FRONT -> "Front camera"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "External camera"
            else -> if (logical) "Back camera system" else "Back camera"
        }
        return CameraCapabilities(
            cameraId = cameraId,
            displayName = name,
            lensFacing = when (facing) {
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                else -> "back"
            },
            logicalMultiCamera = logical,
            physicalCameraIds = chars.physicalCameraIds.sorted(),
            manualSensor = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in requestCapabilities,
            manualWhiteBalance = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in requestCapabilities,
            supportsAwbLock = chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true,
            supportsTapFocus = focusModes.any { it == FocusMode.Single || it == FocusMode.Continuous } &&
                (chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0,
            supportsAeRegions = (chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0,
            supportsTorch = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            supportsZoomRatio = zoomRange != null,
            isoRange = isoRange,
            shutterRangeNs = exposureRange,
            exposureCompensationRange = compensationRange,
            focusDistanceRange = if (minFocusDistance > 0f) FloatValueRange(0f, minFocusDistance) else null,
            zoomRange = resolvedZoomRange,
            fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.map { IntValueRange(it.lower, it.upper) }
                ?.distinct()
                ?.sortedWith(compareBy({ it.max }, { it.min }))
                .orEmpty(),
            focusModes = focusModes,
            whiteBalanceModes = whiteBalanceModes.ifEmpty { setOf(WhiteBalanceMode.Auto) },
            stabilizationModes = stabilizationModes,
        )
    }

    private fun createSession() {
        val device = camera ?: return
        val preview = resolvePreviewSurface().getOrElse {
            Log.w(TAG, "Preview surface is not ready", it)
            return
        }
        val encoded = streamingSurface
        val surfaces = if (encoded != null) listOf(preview, encoded) else listOf(preview)
        session?.close()
        @Suppress("DEPRECATION")
        device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(captureSession: CameraCaptureSession) {
                session = captureSession
                rebuildRepeatingRequest()
            }

            override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                Log.e(TAG, "Capture session configuration failed")
                closeCamera()
            }
        }, handler)
    }

    private fun rebuildRepeatingRequest() {
        val device = camera ?: return
        val activeSession = session ?: return
        val preview = resolvePreviewSurface().getOrNull() ?: return
        val encoded = streamingSurface
        val template = if (encoded != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
        runCatching {
            val builder = device.createCaptureRequest(template).apply {
                addTarget(preview)
                if (encoded != null) addTarget(encoded)
            }
            val state = currentState()
            applyCompleteState(builder, state.settings.copy(zoomRatio = transitionZoomOverride ?: state.settings.zoomRatio))
            activeSession.setRepeatingRequest(builder.build(), captureCallback, handler)
        }.onFailure { Log.w(TAG, "Failed to apply repeating camera request", it) }
    }

    private fun applyCompleteState(builder: CaptureRequest.Builder, settings: CameraSettings) {
        val caps = stateStore.capabilities() ?: return
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        applyFrameRate(builder, settings.fps)
        if (settings.exposureMode == ExposureMode.Manual && caps.manualSensor) {
            val frameDuration = settings.fps?.let { 1_000_000_000L / it.coerceAtLeast(1) }
            val shutter = caps.shutterRangeNs?.clamp(settings.shutterNs ?: DEFAULT_SHUTTER_NS) ?: DEFAULT_SHUTTER_NS
            val iso = caps.isoRange?.clamp(settings.iso ?: DEFAULT_ISO) ?: DEFAULT_ISO
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
            frameDuration?.let { builder.set(CaptureRequest.SENSOR_FRAME_DURATION, max(it, shutter)) }
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            caps.exposureCompensationRange?.let {
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, it.clamp(settings.exposureCompensation))
            }
        }
        applyFocus(builder, settings)
        applyWhiteBalance(builder, settings, caps)
        applyZoom(builder, settings.zoomRatio, caps)
        applyTorch(builder, settings.torch && caps.supportsTorch)
        applyStabilization(builder, settings.stabilizationMode, caps)
    }

    private fun applyFrameRate(builder: CaptureRequest.Builder, fps: Int?) {
        if (fps == null) return
        val ranges = characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        val best = ranges.filter { fps in it.lower..it.upper }
            .minWithOrNull(compareBy<Range<Int>>({ it.upper - it.lower }, { kotlin.math.abs(it.upper - fps) }))
        if (best != null) builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, best)
    }

    private fun applyFocus(builder: CaptureRequest.Builder, settings: CameraSettings) {
        val caps = stateStore.capabilities() ?: return
        when (settings.focusMode) {
            FocusMode.Continuous -> builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                if (FocusMode.Continuous in caps.focusModes) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                else CaptureRequest.CONTROL_AF_MODE_OFF,
            )
            FocusMode.Single -> builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                if (FocusMode.Single in caps.focusModes) CaptureRequest.CONTROL_AF_MODE_AUTO
                else CaptureRequest.CONTROL_AF_MODE_OFF,
            )
            FocusMode.Manual -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                settings.focusDistanceDiopters?.let { distance ->
                    caps.focusDistanceRange?.let { builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it.clamp(distance)) }
                }
            }
        }
        focusRegion?.let { region ->
            if (caps.supportsTapFocus) builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            if (caps.supportsAeRegions) builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        }
    }

    private fun applyWhiteBalance(
        builder: CaptureRequest.Builder,
        settings: CameraSettings,
        caps: CameraCapabilities,
    ) {
        val mode = when (settings.whiteBalanceMode) {
            WhiteBalanceMode.Auto -> CaptureRequest.CONTROL_AWB_MODE_AUTO
            WhiteBalanceMode.Daylight -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
            WhiteBalanceMode.Cloudy -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            WhiteBalanceMode.Incandescent -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
            WhiteBalanceMode.Fluorescent -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
            WhiteBalanceMode.Manual -> CaptureRequest.CONTROL_AWB_MODE_OFF
        }
        builder.set(CaptureRequest.CONTROL_AWB_MODE, mode)
        if (settings.whiteBalanceMode == WhiteBalanceMode.Manual && caps.manualWhiteBalance) {
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, IDENTITY_COLOR_TRANSFORM)
            builder.set(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                kelvinToGains(settings.whiteBalanceKelvin ?: DEFAULT_KELVIN, settings.whiteBalanceTint),
            )
        } else if (caps.supportsAwbLock) {
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, settings.whiteBalanceLock)
        }
    }

    private fun applyZoom(builder: CaptureRequest.Builder, ratio: Float, caps: CameraCapabilities) {
        val value = caps.zoomRange.clamp(ratio)
        if (caps.supportsZoomRatio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, value)
        } else {
            builder.set(CaptureRequest.SCALER_CROP_REGION, calculateCrop(value))
        }
    }

    private fun runZoomTransition(transition: ZoomTransition) {
        zoomTransitionRunnable?.let(handler::removeCallbacks)
        val caps = currentCapabilities() ?: return
        val start = currentState().telemetry.actualZoomRatio
            .takeIf { it.isFinite() && caps.zoomRange.contains(it) }
            ?: transitionZoomOverride?.takeIf { caps.zoomRange.contains(it) }
            ?: caps.zoomRange.clamp(currentState().settings.zoomRatio)
        val startedAt = SystemClock.elapsedRealtime()
        lateinit var frame: Runnable
        frame = Runnable {
            if (currentState().zoomTransition != transition || camera == null || session == null) return@Runnable
            val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
            val progress = (elapsed.toFloat() / transition.durationMs).coerceIn(0f, 1f)
            transitionZoomOverride = start + (transition.targetRatio - start) * easeInOutProgress(progress)
            rebuildRepeatingRequest()
            if (progress >= 1f) {
                transitionZoomOverride = null
                stateStore.cancelZoomTransition()
                rebuildRepeatingRequest()
                zoomTransitionRunnable = null
            } else {
                handler.postDelayed(frame, ZOOM_TRANSITION_FRAME_MS)
            }
        }
        zoomTransitionRunnable = frame
        frame.run()
    }

    private fun cancelZoomTransition() {
        if (::handler.isInitialized) zoomTransitionRunnable?.let(handler::removeCallbacks)
        zoomTransitionRunnable = null
        transitionZoomOverride = null
        stateStore.cancelZoomTransition()
    }

    private fun applyTorch(builder: CaptureRequest.Builder, enabled: Boolean) {
        builder.set(
            CaptureRequest.FLASH_MODE,
            if (enabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF,
        )
    }

    private fun applyStabilization(
        builder: CaptureRequest.Builder,
        mode: StabilizationMode,
        caps: CameraCapabilities,
    ) {
        val resolved = if (mode in caps.stabilizationModes) mode else StabilizationMode.Off
        builder.set(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            if (resolved == StabilizationMode.Optical) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF,
        )
        builder.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            if (resolved == StabilizationMode.Video) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        )
    }

    private fun triggerFocus(mode: FocusActionMode) {
        val device = camera ?: return
        val activeSession = session ?: return
        val preview = resolvePreviewSurface().getOrNull() ?: return
        val encoded = streamingSurface
        val template = if (encoded != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
        runCatching {
            val builder = device.createCaptureRequest(template).apply {
                addTarget(preview)
                if (encoded != null) addTarget(encoded)
            }
            val settings = currentState().settings.copy(focusMode = FocusMode.Single)
            applyCompleteState(builder, settings)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            activeSession.capture(builder.build(), captureCallback, handler)
            if (mode == FocusActionMode.Auto) {
                handler.postDelayed({ rebuildRepeatingRequest() }, FOCUS_RETURN_DELAY_MS)
            }
        }.onFailure { Log.w(TAG, "Tap focus request failed", it) }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val now = System.nanoTime()
            if (now - lastTelemetryPublishNs < TELEMETRY_INTERVAL_NS) return
            lastTelemetryPublishNs = now
            val requested = currentState().settings
            val actualZoom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                result.get(CaptureResult.CONTROL_ZOOM_RATIO) ?: requested.zoomRatio
            } else requested.zoomRatio
            stateStore.updateTelemetry(
                CameraTelemetry(
                    actualIso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                    actualShutterNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                    actualFocusDistanceDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                    actualZoomRatio = actualZoom,
                    actualWhiteBalanceKelvin = requested.whiteBalanceKelvin
                        ?.takeIf { requested.whiteBalanceMode == WhiteBalanceMode.Manual },
                    focusStatus = focusStatus(result.get(CaptureResult.CONTROL_AF_STATE)),
                    aeState = aeState(result.get(CaptureResult.CONTROL_AE_STATE)),
                    awbState = awbState(result.get(CaptureResult.CONTROL_AWB_STATE)),
                    frameNumber = result.frameNumber,
                    timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: now,
                ),
            )
        }
    }

    private fun calculateCrop(zoom: Float): Rect {
        val sensor = sensorRect ?: return Rect(0, 0, 1, 1)
        val ratio = zoom.coerceAtLeast(1f)
        val cropWidth = (sensor.width() / ratio).toInt().coerceAtLeast(1)
        val cropHeight = (sensor.height() / ratio).toInt().coerceAtLeast(1)
        val left = sensor.left + (sensor.width() - cropWidth) / 2
        val top = sensor.top + (sensor.height() - cropHeight) / 2
        return Rect(left, top, left + cropWidth, top + cropHeight)
    }

    private fun selectCameraId(lens: CameraLens): String {
        val ids = cameraManager.cameraIdList
        lens.cameraId?.takeIf { it in ids }?.let { return it }
        val candidates = ids.filter { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == lens.facing
        }
        if (candidates.isEmpty()) return ids.first()
        if (lens.isFrontFacing) return candidates.first()
        val logical = candidates.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps
        }
        return logical ?: candidates.minByOrNull { equivalentFocalLength(it) } ?: candidates.first()
    }

    private fun lensCandidateFor(cameraId: String): CameraLensCandidate? = runCatching {
        CameraLensCandidate(cameraId, equivalentFocalLength(cameraId))
    }.getOrNull()

    private fun equivalentFocalLength(cameraId: String): Float {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val focalLength = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull()
            ?: return 24f
        val sensorWidth = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width
        return if (sensorWidth != null && sensorWidth > 0f) focalLength / sensorWidth * 36f else focalLength
    }

    private fun focusStatus(value: Int?): FocusStatus = when (value) {
        CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN,
        CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> FocusStatus.Scanning
        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> FocusStatus.Focused
        CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> FocusStatus.NotFocused
        CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED,
        CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> FocusStatus.Passive
        else -> FocusStatus.Inactive
    }

    private fun aeState(value: Int?): String = when (value) {
        CaptureResult.CONTROL_AE_STATE_SEARCHING -> "searching"
        CaptureResult.CONTROL_AE_STATE_CONVERGED -> "converged"
        CaptureResult.CONTROL_AE_STATE_LOCKED -> "locked"
        CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> "flash_required"
        CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> "precapture"
        else -> "inactive"
    }

    private fun awbState(value: Int?): String = when (value) {
        CaptureResult.CONTROL_AWB_STATE_SEARCHING -> "searching"
        CaptureResult.CONTROL_AWB_STATE_CONVERGED -> "converged"
        CaptureResult.CONTROL_AWB_STATE_LOCKED -> "locked"
        else -> "inactive"
    }

    private fun kelvinToGains(kelvin: Int, tint: Int): RggbChannelVector {
        val temperature = kelvin.coerceIn(2_000, 12_000).toFloat()
        val normalized = ((temperature - 2_000f) / 10_000f).coerceIn(0f, 1f)
        val tintShift = tint.coerceIn(-100, 100) / 500f
        val red = (2.15f - 1.15f * normalized + tintShift).coerceIn(0.5f, 3f)
        val blue = (0.8f + 1.35f * normalized - tintShift).coerceIn(0.5f, 3f)
        val greenEven = (1f - tintShift / 2f).coerceIn(0.75f, 1.25f)
        val greenOdd = (1f + tintShift / 2f).coerceIn(0.75f, 1.25f)
        return RggbChannelVector(red, greenEven, greenOdd, blue)
    }

    private fun Rect.toSensorRect() = SensorRect(left, top, right, bottom)

    private fun resolvePreviewSurface(): Result<Surface> {
        if (preferFallbackPreviewSurface) {
            val fallback = fallbackPreviewSurface
            if (fallback?.isValid == true) return Result.success(fallback)
        }
        val activitySurface = runCatching { previewSurfaceProvider() }.getOrNull()
        if (activitySurface?.isValid == true) return Result.success(activitySurface)
        val fallback = fallbackPreviewSurface
        if (fallback?.isValid == true) return Result.success(fallback)
        return Result.failure(IllegalStateException("No valid camera preview surface"))
    }

    companion object {
        private const val TAG = "OpenStreamCamera"
        private const val ZOOM_TRANSITION_FRAME_MS = 50L

        internal fun easeInOutProgress(progress: Float): Float {
            val value = progress.coerceIn(0f, 1f)
            return value * value * (3f - 2f * value)
        }
        private const val DEFAULT_ISO = 400
        private const val DEFAULT_SHUTTER_NS = 16_666_667L
        private const val DEFAULT_KELVIN = 5_600
        private const val FOCUS_RETURN_DELAY_MS = 1_200L
        private const val TELEMETRY_INTERVAL_NS = 250_000_000L
        private val IDENTITY_COLOR_TRANSFORM = ColorSpaceTransform(
            arrayOf(
                Rational(1, 1), Rational(0, 1), Rational(0, 1),
                Rational(0, 1), Rational(1, 1), Rational(0, 1),
                Rational(0, 1), Rational(0, 1), Rational(1, 1),
            ),
        )
    }
}
