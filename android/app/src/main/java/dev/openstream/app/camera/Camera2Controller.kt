package dev.openstream.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import java.util.concurrent.atomic.AtomicLong

data class CameraControlCapabilities(
    val exposureMin: Int = 0, val exposureMax: Int = 0, val exposureStep: Float = 0f,
    val aeLock: Boolean = false, val awbLock: Boolean = false,
    val focusLock: Boolean = false, val tapFocus: Boolean = false,
    val exposureTimeMinNs: Long = 0, val exposureTimeMaxNs: Long = 0,
    val isoMin: Int = 0, val isoMax: Int = 0, val focusMaxDiopters: Float = 0f,
    val whiteBalanceModes: List<Int> = emptyList(),
)

data class CameraControlState(
    val exposureCompensation: Int = 0, val aeLocked: Boolean = false,
    val awbLocked: Boolean = false, val focusLocked: Boolean = false,
    val exposureTimeNs: Long? = null, val sensitivityIso: Int? = null,
    val focusDiopters: Float? = null, val whiteBalanceMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO,
)

internal fun previewFocusPoint(x: Float, y: Float, sensorOrientation: Int, displayRotationDegrees: Int, frontFacing: Boolean): Pair<Float, Float>? {
    if (!x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) return null
    val rotation = ((sensorOrientation + (if (frontFacing) displayRotationDegrees else -displayRotationDegrees)) % 360 + 360) % 360
    val px = if (frontFacing) 1f - x else x
    return when (rotation) {
        90 -> y to (1f - px)
        180 -> (1f - px) to (1f - y)
        270 -> (1f - y) to px
        else -> px to y
    }
}

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
    /** Extra reopen attempts after an in-use error (e.g. another screen still releasing the camera). */
    private val inUseRetryDelaysMs: List<Long> = emptyList(),
) {
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
    private var inUseRetries = 0

    // Zoom state
    private var currentZoomRatio = 1.0f
    private var maxZoomRatio = 1.0f
    private var minZoomRatio = 1.0f
    private var sensorRect: Rect? = null
    private var supportsZoomRatioKey = false
    private var captureFpsRange: Range<Int>? = null

    // Torch state
    private var torchEnabled = false
    private var flashAvailable = false

    /** Zoom value as a fraction [minZoom, maxZoom]. */
    val zoomRatio: Float get() = synchronized(lifecycleLock) { currentZoomRatio }
    val zoomRange: ClosedFloatingPointRange<Float>
        get() = synchronized(lifecycleLock) { minZoomRatio..maxZoomRatio }

    private var proCapabilities = CameraControlCapabilities()
    private var proState = CameraControlState()
    private var meteringRegion: MeteringRectangle? = null
    private var afModes = emptyList<Int>()
    private var aeRegionsAvailable = false
    private var sensorOrientation = 0
    private var frontFacing = false
    val controlCapabilities: CameraControlCapabilities get() = synchronized(lifecycleLock) { proCapabilities }
    val controlState: CameraControlState get() = synchronized(lifecycleLock) { proState }

    fun setExposureCompensation(value: Int): Boolean = synchronized(lifecycleLock) {
        if (value !in proCapabilities.exposureMin..proCapabilities.exposureMax) return@synchronized false
        proState = proState.copy(exposureCompensation = value)
        rebuildRepeatingRequest()
        true
    }

    fun setAeLock(enabled: Boolean): Boolean = synchronized(lifecycleLock) {
        if (enabled && (!proCapabilities.aeLock || proState.exposureTimeNs != null)) return@synchronized false
        proState = proState.copy(aeLocked = enabled)
        rebuildRepeatingRequest()
        true
    }

    fun setAwbLock(enabled: Boolean): Boolean = synchronized(lifecycleLock) {
        if (enabled && !proCapabilities.awbLock) return@synchronized false
        proState = proState.copy(awbLocked = enabled)
        rebuildRepeatingRequest()
        true
    }

    fun setFocusLock(enabled: Boolean): Boolean = synchronized(lifecycleLock) {
        if (enabled && (!proCapabilities.focusLock || proState.focusDiopters != null)) return@synchronized false
        proState = proState.copy(focusLocked = enabled)
        if (!enabled) meteringRegion = null
        rebuildRepeatingRequest(if (enabled) CaptureRequest.CONTROL_AF_TRIGGER_START else CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
        true
    }

    fun focusAtPreview(x: Float, y: Float, displayRotationDegrees: Int = 0, previewAspectRatio: Float = 16f / 9f): Boolean = synchronized(lifecycleLock) {
        val (sx, sy) = previewFocusPoint(x, y, sensorOrientation, displayRotationDegrees, frontFacing)
            ?: return@synchronized false
        val sensor = sensorRect ?: return@synchronized false
        if (!previewAspectRatio.isFinite() || previewAspectRatio <= 0f) return@synchronized false
        val sensorAspect = sensor.width().toFloat() / sensor.height()
        // Camera output crops the sensor to its stream aspect before display rotation.
        val visibleWidth = minOf(1f, previewAspectRatio / sensorAspect)
        val visibleHeight = minOf(1f, sensorAspect / previewAspectRatio)
        focusAt(.5f + (sx - .5f) * visibleWidth, .5f + (sy - .5f) * visibleHeight)
    }

    /** Coordinates are normalized to the unrotated, unmirrored visible sensor crop. */
    fun focusAt(x: Float, y: Float): Boolean = synchronized(lifecycleLock) {
        if (!proCapabilities.tapFocus || !x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) return@synchronized false
        val sensor = sensorRect ?: return@synchronized false
        // Zoom-ratio metering coordinates describe the already zoomed active array.
        val crop = centeredCropRegion(sensor.left, sensor.top, sensor.right, sensor.bottom, if (supportsZoomRatioKey) 1f else currentZoomRatio)
        val width = (crop.right - crop.left).coerceAtLeast(1)
        val height = (crop.bottom - crop.top).coerceAtLeast(1)
        val box = (minOf(width, height) / 8).coerceAtLeast(1)
        val left = (crop.left + (x * width).toInt() - box / 2).coerceIn(crop.left, crop.right - box)
        val top = (crop.top + (y * height).toInt() - box / 2).coerceIn(crop.top, crop.bottom - box)
        meteringRegion = MeteringRectangle(left, top, box, box, MeteringRectangle.METERING_WEIGHT_MAX)
        proState = proState.copy(focusLocked = true, focusDiopters = null)
        rebuildRepeatingRequest(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
        rebuildRepeatingRequest(CaptureRequest.CONTROL_AF_TRIGGER_START)
        true
    }

    fun setManualExposure(exposureTimeNs: Long?, sensitivityIso: Int?): Boolean = synchronized(lifecycleLock) {
        val caps = proCapabilities
        if ((exposureTimeNs == null) != (sensitivityIso == null)) return@synchronized false
        if (exposureTimeNs != null && (caps.exposureTimeMinNs == 0L || exposureTimeNs !in caps.exposureTimeMinNs..caps.exposureTimeMaxNs || sensitivityIso == null || sensitivityIso !in caps.isoMin..caps.isoMax)) return@synchronized false
        proState = proState.copy(exposureTimeNs = exposureTimeNs, sensitivityIso = sensitivityIso, aeLocked = false)
        rebuildRepeatingRequest()
        true
    }

    fun setManualFocus(diopters: Float?): Boolean = synchronized(lifecycleLock) {
        if (diopters != null && (!diopters.isFinite() || proCapabilities.focusMaxDiopters <= 0f || diopters !in 0f..proCapabilities.focusMaxDiopters)) return@synchronized false
        proState = proState.copy(focusDiopters = diopters, focusLocked = false)
        meteringRegion = null
        rebuildRepeatingRequest(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
        true
    }

    fun setWhiteBalance(mode: Int): Boolean = synchronized(lifecycleLock) {
        if (mode !in proCapabilities.whiteBalanceModes) return@synchronized false
        proState = proState.copy(whiteBalanceMode = mode, awbLocked = false)
        rebuildRepeatingRequest()
        true
    }

    fun resetControls() = synchronized(lifecycleLock) {
        proState = CameraControlState()
        meteringRegion = null
        rebuildRepeatingRequest(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
    }

    companion object {
        private const val TAG = "shinCamera"
        private const val BACK_DUAL_FOCAL_RATIO_THRESHOLD = 1.5f
        private const val CAMERA_THREAD_JOIN_TIMEOUT_MS = 500L
    }

    /** True when two back-camera focal lengths span a wide-to-tele gap. */
    private fun hasWideFocalSpread(shortFocalLength: Float, longFocalLength: Float): Boolean {
        if (shortFocalLength <= 0) return false
        return longFocalLength / shortFocalLength > BACK_DUAL_FOCAL_RATIO_THRESHOLD
    }

    /**
     * Query available lenses on this device.
     * Returns only CameraLens values that have a matching physical camera.
     */
    fun availableLenses(): List<CameraLens> {
        val result = mutableListOf<CameraLens>()
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

        // Collect all back-facing cameras with their focal lengths
        data class CamInfo(val id: String, val focalLength: Float, val facing: Int)
        val cameras = cameraIds.mapNotNull { id ->
            try {
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: return@mapNotNull null
                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val focal = focalLengths?.firstOrNull() ?: 0f
                CamInfo(id, focal, facing)
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

        val backCams = cameras.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
            .sortedBy { it.focalLength }
        val frontCams = cameras.filter { it.facing == CameraCharacteristics.LENS_FACING_FRONT }

        if (backCams.size >= 3) {
            result.add(CameraLens.BackUltrawide)
            result.add(CameraLens.Back)
            result.add(CameraLens.BackTelephoto)
        } else if (backCams.size == 2) {
            if (hasWideFocalSpread(backCams[0].focalLength, backCams[1].focalLength)) {
                result.add(CameraLens.Back)
                result.add(CameraLens.BackTelephoto)
            } else {
                result.add(CameraLens.BackUltrawide)
                result.add(CameraLens.Back)
            }
        } else if (backCams.isNotEmpty()) {
            result.add(CameraLens.Back)
        }

        if (frontCams.isNotEmpty()) {
            result.add(CameraLens.Front)
        }

        return result.ifEmpty { listOf(CameraLens.Back) }
    }

    @SuppressLint("MissingPermission")
    fun startPreview() {
        synchronized(lifecycleLock) {
            if (!desiredRunning) {
                lifecycleGeneration += 1
                inUseRetries = 0
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
                        inUseRetries = 0
                        camera = device
                        loadZoomCapabilities(desiredId)
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
                        if (error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ||
                            error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE
                        ) {
                            scheduleInUseRetryLocked(desiredId)
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
            if (error is CameraAccessException &&
                (error.reason == CameraAccessException.CAMERA_IN_USE ||
                    error.reason == CameraAccessException.MAX_CAMERAS_IN_USE)
            ) {
                scheduleInUseRetryLocked(desiredId)
            }
        }
    }

    fun switchLens(lens: CameraLens) {
        synchronized(lifecycleLock) {
            val newId = runCatching { selectCameraId(lens) }.getOrElse { error ->
                Log.w(TAG, "Cannot switch to lens $lens: no camera available", error)
                return
            }
            if (newId == activeCameraId) return

            activeLens = lens
            activeCameraId = newId
            currentZoomRatio = 1.0f
            torchEnabled = false

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
            if (!ratio.isFinite()) return@synchronized currentZoomRatio
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

    fun setTorch(enabled: Boolean) {
        synchronized(lifecycleLock) {
            torchEnabled = enabled
            rebuildRepeatingRequest()
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

    /**
     * Availability callbacks are not guaranteed when the holder is this same
     * process, so in-use errors also retry on a short backoff when configured.
     */
    private fun scheduleInUseRetryLocked(cameraId: String) {
        val delayMs = inUseRetryDelaysMs.getOrNull(inUseRetries) ?: return
        val retryHandler = handler ?: return
        inUseRetries += 1
        val expectedLifecycleGeneration = lifecycleGeneration
        val expectedCameraGeneration = cameraGeneration.get()
        retryHandler.postDelayed({
            synchronized(lifecycleLock) {
                if (!desiredRunning ||
                    lifecycleGeneration != expectedLifecycleGeneration ||
                    cameraGeneration.get() != expectedCameraGeneration ||
                    activeCameraId != cameraId ||
                    camera != null
                ) {
                    return@synchronized
                }
                Log.i(TAG, "Retrying camera $cameraId after in-use error")
                cancelCameraRecoveryLocked()
                startPreviewLocked()
            }
        }, delayMs)
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
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        frontFacing = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()
        val manualSensor = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        val time = if (manualSensor) chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) else null
        val iso = if (manualSensor) chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) else null
        val ev = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        aeRegionsAvailable = (chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0
        proCapabilities = CameraControlCapabilities(
            exposureMin = ev?.lower ?: 0, exposureMax = ev?.upper ?: 0,
            exposureStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f,
            aeLock = chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true,
            awbLock = chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true,
            focusLock = CaptureRequest.CONTROL_AF_MODE_AUTO in afModes,
            tapFocus = CaptureRequest.CONTROL_AF_MODE_AUTO in afModes && (chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0,
            exposureTimeMinNs = time?.lower ?: 0,
            exposureTimeMaxNs = minOf(time?.upper ?: 0, 1_000_000_000L / targetFps),
            isoMin = iso?.lower ?: 0, isoMax = iso?.upper ?: 0,
            focusMaxDiopters = if (CaptureRequest.CONTROL_AF_MODE_OFF in afModes) chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f else 0f,
            whiteBalanceModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.filter { it != CaptureRequest.CONTROL_AWB_MODE_OFF }.orEmpty(),
        )
        proState = CameraControlState()
        meteringRegion = null
        flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
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
                            val template = if (encoded != null) {
                                CameraDevice.TEMPLATE_RECORD
                            } else {
                                CameraDevice.TEMPLATE_PREVIEW
                            }
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
                                    applyProControls(this)
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
            val cropWidth = (sensor.width() / currentZoomRatio).toInt()
            val cropHeight = (sensor.height() / currentZoomRatio).toInt()
            val left = (sensor.width() - cropWidth) / 2
            val top = (sensor.height() - cropHeight) / 2
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + cropWidth, top + cropHeight))
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
            if (torchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF,
        )
    }

    private fun applyProControls(builder: CaptureRequest.Builder) {
        val state = proState
        val caps = proCapabilities
        val afMode = when {
            state.focusDiopters != null -> CaptureRequest.CONTROL_AF_MODE_OFF
            state.focusLocked -> CaptureRequest.CONTROL_AF_MODE_AUTO
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            else -> afModes.firstOrNull() ?: CaptureRequest.CONTROL_AF_MODE_OFF
        }
        builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
        state.focusDiopters?.let { builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
        meteringRegion?.let {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it))
            if (aeRegionsAvailable) builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it))
        }
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, state.exposureCompensation)
        if (caps.aeLock) builder.set(CaptureRequest.CONTROL_AE_LOCK, state.aeLocked)
        if (caps.awbLock) builder.set(CaptureRequest.CONTROL_AWB_LOCK, state.awbLocked)
        if (state.whiteBalanceMode in caps.whiteBalanceModes) builder.set(CaptureRequest.CONTROL_AWB_MODE, state.whiteBalanceMode)
        state.exposureTimeNs?.let {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, state.sensitivityIso)
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / targetFps)
        }
    }

    private fun rebuildRepeatingRequest(afTrigger: Int = CaptureRequest.CONTROL_AF_TRIGGER_IDLE) {
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
        val template = if (encoded != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW

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
                applyProControls(this)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            }
            if (afTrigger != CaptureRequest.CONTROL_AF_TRIGGER_IDLE && proCapabilities.focusLock) {
                request.set(CaptureRequest.CONTROL_AF_TRIGGER, afTrigger)
                currentSession.capture(request.build(), null, handler)
                request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            }
            currentSession.setRepeatingRequest(request.build(), null, handler)
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
        val candidates = cameraIds.filter { id ->
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

        if (candidates.isEmpty()) {
            Log.w(TAG, "No camera matches lens $lens; using ${cameraIds.first()}")
            return activeCameraId ?: cameraIds.first()
        }
        if (candidates.size == 1 || lens.isFrontFacing) return candidates.first()

        data class CamCandidate(val id: String, val focalLength: Float)
        val sorted = candidates.mapNotNull { id ->
            try {
                val chars = manager.getCameraCharacteristics(id)
                val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
                CamCandidate(id, focal)
            } catch (error: CameraAccessException) {
                Log.w(TAG, "Skipping camera $id while selecting lens $lens", error)
                null
            } catch (error: SecurityException) {
                Log.w(TAG, "Skipping camera $id while selecting lens $lens", error)
                null
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Skipping camera $id while selecting lens $lens", error)
                null
            }
        }.sortedBy { it.focalLength }
        if (sorted.isEmpty()) {
            return activeCameraId ?: candidates.first()
        }

        return when (lens.focalHint) {
            CameraLens.FocalHint.Ultrawide -> sorted.first().id
            CameraLens.FocalHint.Telephoto -> sorted.last().id
            CameraLens.FocalHint.Normal -> {
                if (sorted.size >= 3) {
                    sorted[1].id
                } else if (sorted.size == 2) {
                    // Mirrored with availableLenses(): a wide focal spread advertises
                    // Back+Tele, so Normal takes the short end; a narrow spread
                    // advertises Ultrawide+Back, so Normal takes the long end.
                    if (hasWideFocalSpread(sorted[0].focalLength, sorted[1].focalLength)) sorted[0].id else sorted[1].id
                } else {
                    sorted.first().id
                }
            }
        }
    }
}