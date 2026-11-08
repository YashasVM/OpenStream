package dev.openstream.irl.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import java.util.concurrent.atomic.AtomicLong

internal data class CameraCropRegion(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Build a centered crop in active-array coordinates (which need not start at 0,0). */
internal fun centeredCropRegion(
    sensorLeft: Int,
    sensorTop: Int,
    sensorRight: Int,
    sensorBottom: Int,
    zoomRatio: Float,
): CameraCropRegion {
    val safeZoom = zoomRatio.coerceAtLeast(1.0f)
    val sensorWidth = sensorRight - sensorLeft
    val sensorHeight = sensorBottom - sensorTop
    val cropWidth = (sensorWidth / safeZoom).toInt().coerceAtLeast(1)
    val cropHeight = (sensorHeight / safeZoom).toInt().coerceAtLeast(1)
    val left = sensorLeft + (sensorWidth - cropWidth) / 2
    val top = sensorTop + (sensorHeight - cropHeight) / 2
    return CameraCropRegion(left, top, left + cropWidth, top + cropHeight)
}

/**
 * Camera2Controller manages the lifecycle of a Camera2 device and its
 * capture sessions. It handles:
 *
 * - Opening / closing camera devices when the selected lens changes.
 * - Creating preview-only or preview+encode sessions.
 * - Pinch-to-zoom via crop region or CONTROL_ZOOM_RATIO.
 * - Enumerating available physical lenses.
 */
class Camera2Controller(
    context: Context,
    private val previewSurfaceProvider: () -> Surface,
    private val lensProvider: () -> CameraLens = { CameraLens.Back },
    private val targetFps: Int = 30,
) {
    private data class CamInfo(
        val id: String,
        val chars: CameraCharacteristics,
        val focalLength: Float,
        val facing: Int,
    )

    private val appContext = context.applicationContext
    private val cameraManager: CameraManager? =
        appContext.getSystemService(CameraManager::class.java)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var streamingSurface: Surface? = null
    private var activeCameraId: String? = null
    private var activeLens: CameraLens? = null
    private val cameraGeneration = AtomicLong()
    private val sessionGeneration = AtomicLong()
    private val lifecycleLock = Any()
    private var lifecycleGeneration = 0L
    private var desiredRunning = false
    private var recoveryCallback: CameraManager.AvailabilityCallback? = null

    // Zoom state
    private var currentZoomRatio = 1.0f
    private var maxZoomRatio = 1.0f
    private var minZoomRatio = 1.0f
    private var sensorRect: Rect? = null
    private var supportsZoomRatioKey = false
    private var captureFpsRange: Range<Int>? = null

    // Torch state
    private var torchOnInternal = false
    private var flashAvailable = false
    val torchAvailable: Boolean get() = synchronized(lifecycleLock) { flashAvailable }
    val torchEnabled: Boolean get() = synchronized(lifecycleLock) { torchOnInternal }

    /** Zoom value as a fraction [minZoom, maxZoom]. */
    val zoomRatio: Float get() = synchronized(lifecycleLock) { currentZoomRatio }
    val zoomRange: ClosedFloatingPointRange<Float>
        get() = synchronized(lifecycleLock) { minZoomRatio..maxZoomRatio }

    companion object {
        private const val TAG = "shinCamera"
        private const val CAMERA_THREAD_JOIN_TIMEOUT_MS = 500L
    }

    /** Discover optical presets from logical multi-camera physical lens metadata. */
    fun availableLenses(): List<CameraLens> {
        val manager = cameraManager ?: run {
            Log.w(TAG, "CameraManager unavailable; falling back to single Back lens")
            return listOf(CameraLens.Back)
        }
        val cameraIds: Array<String> = try {
            manager.cameraIdList
        } catch (error: CameraAccessException) {
            Log.w(TAG, "Could not enumerate cameras", error)
            return listOf(CameraLens.Back)
        } catch (error: SecurityException) {
            Log.w(TAG, "Camera permission missing while enumerating cameras", error)
            return listOf(CameraLens.Back)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Could not enumerate cameras", error)
            return listOf(CameraLens.Back)
        }
        if (cameraIds.isEmpty()) {
            Log.w(TAG, "Device reports no cameras; falling back to a single Back lens entry")
            return listOf(CameraLens.Back)
        }

        val cameras = cameraIds.mapNotNull { id ->
            try {
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: return@mapNotNull null
                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val focal = focalLengths?.firstOrNull() ?: 0f
                CamInfo(id, chars, focal, facing)
            } catch (error: CameraAccessException) {
                Log.w(TAG, "Skipping camera $id during enumeration", error)
                null
            } catch (error: SecurityException) {
                Log.w(TAG, "Skipping camera $id during enumeration", error)
                null
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Skipping camera $id during enumeration", error)
                null
            }
        }

        val backCameras = cameras.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
        val logicalBack = backCameras
            .filter { Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && it.chars.physicalCameraIds.isNotEmpty() }
            .maxByOrNull { it.chars.physicalCameraIds.size }
        val result = mutableListOf<CameraLens>()
        if (logicalBack != null) {
            result += logicalOpticalPresets(manager, logicalBack.id, logicalBack.chars)
        }

        if (result.none { it.isBackFacing }) {
            result += fallbackBackLenses(backCameras)
        }
        cameras.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_FRONT }?.let { front ->
            result += CameraLens(
                displayName = "Front camera",
                shortLabel = "Front",
                facing = CameraCharacteristics.LENS_FACING_FRONT,
                cameraId = front.id,
            )
        }
        return result.ifEmpty { listOf(CameraLens.Back) }
    }

    @Suppress("NewApi")
    private fun logicalOpticalPresets(
        manager: CameraManager,
        logicalCameraId: String,
        logicalChars: CameraCharacteristics,
    ): List<CameraLens> {
        val defaultFocal = logicalChars
            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull() ?: return emptyList()
        val defaultSensorWidth = logicalChars
            .get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?.width ?: return emptyList()
        val zoomRange = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            logicalChars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        } else {
            null
        }
        val minZoom = zoomRange?.lower ?: 1.0f
        val maxZoom = zoomRange?.upper
            ?: logicalChars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            ?: 1.0f
        val physical = logicalChars.physicalCameraIds.mapNotNull { physicalId ->
            try {
                val chars = manager.getCameraCharacteristics(physicalId)
                if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
                    return@mapNotNull null
                }
                val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                    ?: return@mapNotNull null
                val sensorWidth = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width
                    ?: return@mapNotNull null
                PhysicalLensInfo(physicalId, focal, sensorWidth)
            } catch (error: Exception) {
                Log.w(TAG, "Could not read physical lens $physicalId on logical camera $logicalCameraId", error)
                null
            }
        }
        val presets = buildOpticalLensPresets(
            logicalCameraId = logicalCameraId,
            defaultFocalLengthMm = defaultFocal,
            defaultSensorWidthMm = defaultSensorWidth,
            minZoomRatio = minZoom,
            maxZoomRatio = maxZoom,
            physicalLenses = physical,
        )
        Log.i(TAG, "Logical back camera $logicalCameraId exposed optical presets ${presets.map { it.shortLabel }}")
        return presets
    }

    private fun fallbackBackLenses(cameras: List<CamInfo>): List<CameraLens> {
        val back = cameras.sortedBy { it.focalLength }
        if (back.isEmpty()) return emptyList()
        if (back.size == 1) {
            return listOf(CameraLens("Back camera", "1×", back[0].facing, cameraId = back[0].id))
        }
        val baseline = back.first()
        val sensorWidth = baseline.chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: 0f
        if (sensorWidth <= 0f || baseline.focalLength <= 0f) {
            return listOf(CameraLens("Back camera", "1×", baseline.facing, cameraId = baseline.id))
        }
        val baseFovScale = baseline.focalLength / sensorWidth
        return back.map { camera ->
            val cameraWidth = camera.chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: sensorWidth
            val ratio = if (cameraWidth > 0f && baseFovScale > 0f) {
                (camera.focalLength / cameraWidth) / baseFovScale
            } else {
                1f
            }
            val rounded = String.format(java.util.Locale.ROOT, "%.1f", ratio)
                .trimEnd('0').trimEnd('.')
            val label = "${rounded}×"
            CameraLens("$label camera", label, camera.facing, cameraId = camera.id)
        }.distinctBy { it.shortLabel }
    }

    @SuppressLint("MissingPermission")
    fun startPreview() {
        synchronized(lifecycleLock) {
            if (!desiredRunning) {
                lifecycleGeneration += 1
            }
            desiredRunning = true
            startPreviewLocked()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPreviewLocked() {
        ensureThread()
        cancelCameraRecoveryLocked()
        val desiredLens = lensProvider()
        val desiredId = runCatching { selectCameraId(desiredLens) }.getOrElse { error ->
            Log.w(TAG, "No camera available for lens $desiredLens; preview deferred", error)
            return
        }

        if (camera != null && activeCameraId == desiredId) {
            if (activeLens != desiredLens) {
                activeLens = desiredLens
                setZoom(desiredLens.zoomRatio ?: 1.0f)
            }
            createSession()
            return
        }

        closeCamera()
        val generation = cameraGeneration.get()
        val expectedLifecycleGeneration = lifecycleGeneration
        activeLens = desiredLens
        activeCameraId = desiredId

        val managerForOpen = cameraManager ?: run {
            Log.w(TAG, "CameraManager unavailable; cannot open $desiredId")
            closeCamera()
            return
        }
        try {
            managerForOpen.openCamera(desiredId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    synchronized(lifecycleLock) {
                        if (!desiredRunning ||
                            lifecycleGeneration != expectedLifecycleGeneration ||
                            cameraGeneration.get() != generation ||
                            activeCameraId != desiredId
                        ) {
                            Log.i(TAG, "Ignoring stale camera open for $desiredId")
                            device.close()
                            return
                        }
                        cancelCameraRecoveryLocked()
                        camera = device
                        loadZoomCapabilities(desiredId)
                        currentZoomRatio = (activeLens?.zoomRatio ?: 1.0f)
                            .coerceIn(minZoomRatio, maxZoomRatio)
                        createSession()
                    }
                }

                override fun onDisconnected(device: CameraDevice) {
                    synchronized(lifecycleLock) {
                        if (!desiredRunning ||
                            lifecycleGeneration != expectedLifecycleGeneration ||
                            cameraGeneration.get() != generation ||
                            activeCameraId != desiredId
                        ) {
                            device.close()
                            return
                        }
                        Log.w(TAG, "Camera disconnected")
                        device.close()
                        closeCamera()
                        watchForCameraAvailability(desiredId)
                    }
                }

                override fun onError(device: CameraDevice, error: Int) {
                    synchronized(lifecycleLock) {
                        if (!desiredRunning ||
                            lifecycleGeneration != expectedLifecycleGeneration ||
                            cameraGeneration.get() != generation ||
                            activeCameraId != desiredId
                        ) {
                            device.close()
                            return
                        }
                        Log.e(TAG, "Camera error: $error")
                        device.close()
                        closeCamera()
                        if (error != CameraDevice.StateCallback.ERROR_CAMERA_DISABLED) {
                            watchForCameraAvailability(desiredId)
                        }
                    }
                }
            }, handler)
        } catch (security: SecurityException) {
            Log.e(TAG, "Camera permission was revoked while opening $desiredId", security)
            closeCamera()
        } catch (error: Exception) {
            Log.e(TAG, "Could not open camera $desiredId", error)
            closeCamera()
            watchForCameraAvailability(desiredId)
        }
    }

    fun switchLens(lens: CameraLens) {
        synchronized(lifecycleLock) {
            val newId = runCatching { selectCameraId(lens) }.getOrElse { error ->
                Log.w(TAG, "Cannot switch to lens $lens: no camera available", error)
                return
            }
            if (newId == activeCameraId && camera != null) {
                activeLens = lens
                setZoom(lens.zoomRatio ?: 1.0f)
                return
            }

            val sameCamera = newId == activeCameraId
            activeLens = lens
            activeCameraId = newId
            currentZoomRatio = lens.zoomRatio ?: 1.0f
            if (!sameCamera) {
                torchOnInternal = false
                flashAvailable = false
            }

            closeCamera()
            startPreview()
        }
    }

    fun startStreaming(encodedSurface: Surface) {
        synchronized(lifecycleLock) {
            streamingSurface = encodedSurface
            if (camera == null) {
                startPreview()
            } else {
                createSession()
            }
        }
    }

    fun stopStreaming() {
        synchronized(lifecycleLock) {
            streamingSurface = null
            if (camera != null) {
                createSession()
            }
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            desiredRunning = false
            lifecycleGeneration += 1
            cancelCameraRecoveryLocked()
            closeCamera()
            streamingSurface = null
            quitCameraThreadLocked()
        }
    }

    fun setZoom(ratio: Float): Float {
        return synchronized(lifecycleLock) {
            currentZoomRatio = ratio.coerceIn(minZoomRatio, maxZoomRatio)
            updateZoomInSession()
            currentZoomRatio
        }
    }

    fun scaleZoom(scaleFactor: Float): Float {
        return synchronized(lifecycleLock) {
            setZoom(currentZoomRatio * scaleFactor)
        }
    }

    fun setTorch(enabled: Boolean): Boolean {
        return synchronized(lifecycleLock) {
            if (enabled && !flashAvailable) return@synchronized false
            torchOnInternal = enabled
            rebuildRepeatingRequest()
            true
        }
    }

    private fun ensureThread() {
        if (thread?.isAlive == true && handler != null) return
        // A quit HandlerThread cannot be restarted; drop it and create a fresh one.
        thread?.let { dead -> runCatching { dead.quitSafely() } }
        thread = null
        handler = null
        val fresh = HandlerThread("shinCamera").apply { start() }
        thread = fresh
        handler = Handler(fresh.looper)
    }

    private fun quitCameraThreadLocked() {
        val old = thread ?: return
        thread = null
        handler = null
        old.quitSafely()
        if (Thread.currentThread() != old) {
            runCatching { old.join(CAMERA_THREAD_JOIN_TIMEOUT_MS) }
            if (old.isAlive) {
                Log.w(TAG, "Camera thread did not exit within timeout")
                old.quit()
            }
        }
    }

    private fun closeCamera() {
        cameraGeneration.incrementAndGet()
        sessionGeneration.incrementAndGet()
        session?.close()
        camera?.close()
        session = null
        camera = null
    }

    private fun watchForCameraAvailability(cameraId: String) {
        synchronized(lifecycleLock) {
            if (!desiredRunning) return
            cancelCameraRecoveryLocked()
            val cameraRecoveryGeneration = cameraGeneration.get()
            val expectedLifecycleGeneration = lifecycleGeneration
            val callback = object : CameraManager.AvailabilityCallback() {
                override fun onCameraAvailable(availableCameraId: String) {
                    synchronized(lifecycleLock) {
                        if (availableCameraId != cameraId ||
                            !desiredRunning ||
                            lifecycleGeneration != expectedLifecycleGeneration ||
                            cameraGeneration.get() != cameraRecoveryGeneration ||
                            activeCameraId != cameraId
                        ) {
                            return
                        }
                        val previewReady = runCatching { previewSurfaceProvider().isValid }.getOrDefault(false)
                        if (!previewReady) return
                        cancelCameraRecoveryLocked()
                        startPreviewLocked()
                    }
                }
            }
            recoveryCallback = callback
            val managerForWatch = cameraManager
            if (managerForWatch == null) {
                if (recoveryCallback === callback) recoveryCallback = null
                Log.w(TAG, "Could not watch camera $cameraId availability: no CameraManager")
                return
            }
            runCatching {
                managerForWatch.registerAvailabilityCallback(callback, handler)
            }.onFailure { error ->
                if (recoveryCallback === callback) recoveryCallback = null
                Log.w(TAG, "Could not watch camera $cameraId availability", error)
            }
        }
    }

    private fun cancelCameraRecoveryLocked() {
        val callback = recoveryCallback ?: return
        recoveryCallback = null
        val managerForCancel = cameraManager ?: return
        runCatching { managerForCancel.unregisterAvailabilityCallback(callback) }
    }

    private fun loadZoomCapabilities(cameraId: String) {
        val managerForCaps = cameraManager ?: run {
            Log.w(TAG, "Could not read characteristics for camera $cameraId: no CameraManager")
            return
        }
        val chars = try {
            managerForCaps.getCameraCharacteristics(cameraId)
        } catch (error: CameraAccessException) {
            Log.w(TAG, "Could not read characteristics for camera $cameraId", error)
            return
        } catch (error: SecurityException) {
            Log.w(TAG, "Could not read characteristics for camera $cameraId", error)
            return
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Could not read characteristics for camera $cameraId", error)
            return
        }
        flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        if (!flashAvailable) torchOnInternal = false
        val availableFpsRanges = chars
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            .orEmpty()
        captureFpsRange = availableFpsRanges
            .filter { range -> targetFps in range.lower..range.upper }
            .minWithOrNull(
                compareBy<Range<Int>>(
                    { if (it.lower == targetFps && it.upper == targetFps) 0 else 1 },
                    { it.upper - it.lower },
                    { -it.lower },
                ),
            )
            ?: availableFpsRanges
                .filter { range -> range.upper <= targetFps }
                .maxWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
        captureFpsRange?.let { range ->
            Log.i(TAG, "Camera $cameraId using AE FPS range ${range.lower}-${range.upper} for ${targetFps}fps target")
        } ?: Log.w(TAG, "Camera $cameraId exposes no AE FPS range suitable for ${targetFps}fps target")
        sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (range != null) {
                supportsZoomRatioKey = true
                minZoomRatio = range.lower
                maxZoomRatio = range.upper
                return
            }
        }

        supportsZoomRatioKey = false
        val maxDigitalZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
        minZoomRatio = 1.0f
        maxZoomRatio = maxDigitalZoom
    }

    private fun createSession() {
        val device = camera ?: return
        val preview = runCatching { previewSurfaceProvider() }.getOrNull()
        if (preview == null || !preview.isValid) {
            Log.w(TAG, "Deferring camera session until preview surface is valid")
            return
        }
        val encoded = streamingSurface
        if (encoded != null && !encoded.isValid) {
            Log.w(TAG, "Deferring camera session until encoder surface is valid")
            return
        }
        val generation = sessionGeneration.incrementAndGet()
        val surfaces = if (encoded != null) listOf(preview, encoded) else listOf(preview)
        session?.close()
        session = null
        try {
            @Suppress("DEPRECATION")
            device.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(captureSession: CameraCaptureSession) {
                        synchronized(lifecycleLock) {
                            if (!desiredRunning || sessionGeneration.get() != generation || camera !== device) {
                                captureSession.close()
                                return
                            }
                            session = captureSession
                            // This controller feeds a video stream even while it is
                            // preview-only. Some multi-camera HALs do not implement
                            // TEMPLATE_PREVIEW for every physical camera (Samsung's
                            // telephoto camera returns -38), but do support the
                            // record template. Keeping one template also avoids a
                            // capture behavior change when a stream starts/stops.
                            val template = CameraDevice.TEMPLATE_RECORD
                            runCatching {
                                val request = device.createCaptureRequest(template).apply {
                                    addTarget(preview)
                                    if (encoded != null) {
                                        addTarget(encoded)
                                    }
                                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                    applyFrameRate(this)
                                    applyZoom(this)
                                    applyTorch(this)
                                }.build()
                                captureSession.setRepeatingRequest(request, null, handler)
                            }.onFailure { error ->
                                if (sessionGeneration.get() == generation && camera === device) {
                                    recoverFromSessionFailure(
                                        device,
                                        generation,
                                        "Could not start camera repeating request",
                                        error,
                                    )
                                } else {
                                    captureSession.close()
                                }
                            }
                        }
                    }

                    override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                        synchronized(lifecycleLock) {
                            if (!desiredRunning || sessionGeneration.get() != generation || camera !== device) {
                                captureSession.close()
                                return
                            }
                            captureSession.close()
                            recoverFromSessionFailure(
                                device,
                                generation,
                                "Capture session configuration failed",
                            )
                        }
                    }
                },
                handler,
            )
        } catch (error: IllegalStateException) {
            recoverFromSessionFailure(device, generation, "Could not create camera capture session", error)
        } catch (error: CameraAccessException) {
            recoverFromSessionFailure(device, generation, "Could not create camera capture session", error)
        } catch (error: IllegalArgumentException) {
            recoverFromSessionFailure(device, generation, "Could not create camera capture session", error)
        }
    }

    private fun recoverFromSessionFailure(
        device: CameraDevice,
        generation: Long,
        message: String,
        error: Throwable? = null,
    ) {
        synchronized(lifecycleLock) {
            if (!desiredRunning || sessionGeneration.get() != generation || camera !== device) return
            val cameraId = activeCameraId ?: return
            if (error != null) {
                Log.e(TAG, message, error)
            } else {
                Log.e(TAG, message)
            }
            closeCamera()
            watchForCameraAvailability(cameraId)
        }
    }

    private fun applyZoom(builder: CaptureRequest.Builder) {
        if (currentZoomRatio <= 1.0f && !supportsZoomRatioKey) return

        if (supportsZoomRatioKey && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoomRatio)
        } else {
            val sensor = sensorRect ?: return
            val crop = centeredCropRegion(
                sensor.left,
                sensor.top,
                sensor.right,
                sensor.bottom,
                currentZoomRatio,
            )
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(crop.left, crop.top, crop.right, crop.bottom))
        }
    }

    private fun applyFrameRate(builder: CaptureRequest.Builder) {
        captureFpsRange?.let { range ->
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
        }
    }

    private fun applyTorch(builder: CaptureRequest.Builder) {
        if (!flashAvailable) return
        builder.set(
            CaptureRequest.FLASH_MODE,
            if (torchOnInternal) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF,
        )
    }

    private fun rebuildRepeatingRequest() {
        val device = camera ?: return
        val currentSession = session ?: return
        val generation = sessionGeneration.get()
        val preview = runCatching { previewSurfaceProvider() }.getOrNull()
        if (preview == null || !preview.isValid) {
            Log.w(TAG, "Deferring camera request rebuild until preview surface is valid")
            return
        }
        val encoded = streamingSurface
        if (encoded != null && !encoded.isValid) {
            Log.w(TAG, "Deferring camera request rebuild until encoder surface is valid")
            return
        }
        val template = CameraDevice.TEMPLATE_RECORD

        runCatching {
            val request = device.createCaptureRequest(template).apply {
                addTarget(preview)
                if (encoded != null) addTarget(encoded)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                applyFrameRate(this)
                applyZoom(this)
                applyTorch(this)
            }.build()
            currentSession.setRepeatingRequest(request, null, handler)
        }.onFailure { error ->
            val current = sessionGeneration.get() == generation &&
                camera === device &&
                session === currentSession
            if (current && (error is CameraAccessException || error is IllegalStateException)) {
                recoverFromSessionFailure(
                    device,
                    generation,
                    "Could not rebuild camera repeating request",
                    error,
                )
            } else {
                Log.w(TAG, "Failed to rebuild repeating request", error)
            }
        }
    }

    private fun updateZoomInSession() {
        rebuildRepeatingRequest()
    }

    private fun selectCameraId(lens: CameraLens): String {
        lens.cameraId?.let { return it }
        val manager = cameraManager ?: run {
            return activeCameraId
                ?: throw IllegalStateException("No cameras available on this device")
        }
        val cameraIds: Array<String> = try {
            manager.cameraIdList
        } catch (error: CameraAccessException) {
            Log.w(TAG, "Could not enumerate cameras for lens $lens", error)
            return activeCameraId
                ?: throw IllegalStateException("No cameras available on this device", error)
        } catch (error: SecurityException) {
            Log.w(TAG, "Camera permission missing while selecting lens $lens", error)
            return activeCameraId
                ?: throw IllegalStateException("No cameras available on this device", error)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Could not enumerate cameras for lens $lens", error)
            return activeCameraId
                ?: throw IllegalStateException("No cameras available on this device", error)
        }
        if (cameraIds.isEmpty()) {
            return activeCameraId
                ?: throw IllegalStateException("No cameras available on this device")
        }
        val candidate = cameraIds.firstOrNull { id ->
            try {
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == lens.facing
            } catch (error: CameraAccessException) {
                Log.w(TAG, "Skipping camera $id while selecting lens $lens", error)
                false
            } catch (error: SecurityException) {
                Log.w(TAG, "Skipping camera $id while selecting lens $lens", error)
                false
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Skipping camera $id while selecting lens $lens", error)
                false
            }
        }

        if (candidate != null) return candidate
        Log.w(TAG, "No camera matches lens $lens; using ${cameraIds.first()}")
        return activeCameraId ?: cameraIds.first()
    }
}
