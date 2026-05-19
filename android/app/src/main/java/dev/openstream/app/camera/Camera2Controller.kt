package dev.openstream.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

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
    private val context: Context,
    private val previewSurfaceProvider: () -> Surface,
    private val lensProvider: () -> CameraLens = { CameraLens.Back },
) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("OpenStreamCamera")
    private lateinit var handler: Handler
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var streamingSurface: Surface? = null
    private var activeCameraId: String? = null
    private var activeLens: CameraLens? = null

    // Zoom state
    private var currentZoomRatio = 1.0f
    private var maxZoomRatio = 1.0f
    private var minZoomRatio = 1.0f
    private var sensorRect: Rect? = null
    private var supportsZoomRatioKey = false

    // Torch state
    private var torchEnabled = false

    /** Zoom value as a fraction [minZoom, maxZoom]. */
    val zoomRatio: Float get() = currentZoomRatio
    val zoomRange: ClosedFloatingPointRange<Float> get() = minZoomRatio..maxZoomRatio

    companion object {
        private const val TAG = "OpenStreamCamera"
    }

    /**
     * Query available lenses on this device.
     * Returns only CameraLens values that have a matching physical camera.
     */
    fun availableLenses(): List<CameraLens> {
        val result = mutableListOf<CameraLens>()
        val cameraIds = cameraManager.cameraIdList

        // Collect all back-facing cameras with their focal lengths
        data class CamInfo(val id: String, val focalLength: Float, val facing: Int)
        val cameras = cameraIds.mapNotNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: return@mapNotNull null
            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val focal = focalLengths?.firstOrNull() ?: 0f
            CamInfo(id, focal, facing)
        }

        val backCams = cameras.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
            .sortedBy { it.focalLength }
        val frontCams = cameras.filter { it.facing == CameraCharacteristics.LENS_FACING_FRONT }

        if (backCams.size >= 3) {
            // Device has ultrawide, wide, telephoto
            result.add(CameraLens.BackUltrawide)
            result.add(CameraLens.Back)
            result.add(CameraLens.BackTelephoto)
        } else if (backCams.size == 2) {
            // Check if the shorter focal is ultrawide or the longer is telephoto
            val ratio = if (backCams[0].focalLength > 0) backCams[1].focalLength / backCams[0].focalLength else 1f
            if (ratio > 1.5f) {
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
        ensureThread()
        val desiredLens = lensProvider()
        val desiredId = selectCameraId(desiredLens)

        if (camera != null && activeCameraId == desiredId) {
            // Already have the right camera open, just rebuild session
            createSession()
            return
        }

        // Need to open a different camera
        closeCamera()
        activeLens = desiredLens
        activeCameraId = desiredId

        cameraManager.openCamera(desiredId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                loadZoomCapabilities(desiredId)
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

    /**
     * Switch to a different lens. This closes the current camera and opens a new one.
     * If an encoding surface is active, the new camera will resume streaming.
     */
    fun switchLens(lens: CameraLens) {
        val newId = selectCameraId(lens)
        if (newId == activeCameraId) return

        activeLens = lens
        activeCameraId = newId
        currentZoomRatio = 1.0f
        torchEnabled = false

        closeCamera()
        startPreview()
    }

    fun startStreaming(encodedSurface: Surface) {
        streamingSurface = encodedSurface
        if (camera == null) {
            startPreview()
        } else {
            createSession()
        }
    }

    fun stopStreaming() {
        streamingSurface = null
        if (camera != null) {
            createSession()
        }
    }

    fun stop() {
        closeCamera()
        streamingSurface = null
    }

    /**
     * Set the zoom ratio. Clamped to the device's supported range.
     * Returns the actual zoom ratio applied.
     */
    fun setZoom(ratio: Float): Float {
        currentZoomRatio = ratio.coerceIn(minZoomRatio, maxZoomRatio)
        updateZoomInSession()
        return currentZoomRatio
    }

    /**
     * Scale zoom by a delta factor (for pinch-to-zoom).
     * Returns the new zoom ratio.
     */
    fun scaleZoom(scaleFactor: Float): Float {
        return setZoom(currentZoomRatio * scaleFactor)
    }

    fun setManualExposure(iso: Int, exposureTimeNs: Long) {
        // Wired in the request builder once remote controls are added.
        require(iso > 0)
        require(exposureTimeNs > 0)
    }

    /**
     * Enable or disable the camera torch (flashlight).
     * Only works on back-facing cameras with flash hardware.
     */
    fun setTorch(enabled: Boolean) {
        torchEnabled = enabled
        rebuildRepeatingRequest()
    }

    // ---- Internal ----

    private fun ensureThread() {
        if (!thread.isAlive) {
            thread.start()
        }
        handler = Handler(thread.looper)
    }

    private fun closeCamera() {
        session?.close()
        camera?.close()
        session = null
        camera = null
    }

    private fun loadZoomCapabilities(cameraId: String) {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
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
        val preview = previewSurfaceProvider()
        val encoded = streamingSurface
        val surfaces = if (encoded != null) listOf(preview, encoded) else listOf(preview)
        session?.close()
        @Suppress("DEPRECATION")
        device.createCaptureSession(
            surfaces,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(captureSession: CameraCaptureSession) {
                    session = captureSession
                    val template = if (encoded != null) {
                        CameraDevice.TEMPLATE_RECORD
                    } else {
                        CameraDevice.TEMPLATE_PREVIEW
                    }
                    val request = device.createCaptureRequest(template).apply {
                        addTarget(preview)
                        if (encoded != null) {
                            addTarget(encoded)
                        }
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        applyZoom(this)
                        applyTorch(this)
                    }.build()
                    captureSession.setRepeatingRequest(request, null, handler)
                }

                override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                    Log.e(TAG, "Capture session configuration failed")
                    closeCamera()
                }
            },
            handler,
        )
    }

    private fun applyZoom(builder: CaptureRequest.Builder) {
        if (currentZoomRatio <= 1.0f && !supportsZoomRatioKey) return

        if (supportsZoomRatioKey && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoomRatio)
        } else {
            // Fallback: crop region
            val sensor = sensorRect ?: return
            val cropWidth = (sensor.width() / currentZoomRatio).toInt()
            val cropHeight = (sensor.height() / currentZoomRatio).toInt()
            val left = (sensor.width() - cropWidth) / 2
            val top = (sensor.height() - cropHeight) / 2
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + cropWidth, top + cropHeight))
        }
    }

    private fun applyTorch(builder: CaptureRequest.Builder) {
        if (torchEnabled) {
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
        }
    }

    /**
     * Rebuild the repeating request with current zoom + torch state.
     */
    private fun rebuildRepeatingRequest() {
        val device = camera ?: return
        val currentSession = session ?: return
        val preview = previewSurfaceProvider()
        val encoded = streamingSurface
        val template = if (encoded != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW

        runCatching {
            val request = device.createCaptureRequest(template).apply {
                addTarget(preview)
                if (encoded != null) addTarget(encoded)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                applyZoom(this)
                applyTorch(this)
            }.build()
            currentSession.setRepeatingRequest(request, null, handler)
        }.onFailure { e ->
            Log.w(TAG, "Failed to rebuild repeating request", e)
        }
    }

    private fun updateZoomInSession() {
        rebuildRepeatingRequest()
    }

    private fun selectCameraId(lens: CameraLens): String {
        val cameraIds = cameraManager.cameraIdList
        val candidates = cameraIds.filter { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == lens.facing
        }

        if (candidates.isEmpty()) return cameraIds.first()
        if (candidates.size == 1 || lens.isFrontFacing) return candidates.first()

        // Multiple back cameras — pick by focal length
        data class CamCandidate(val id: String, val focalLength: Float)
        val sorted = candidates.map { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
            CamCandidate(id, focal)
        }.sortedBy { it.focalLength }

        return when (lens.focalHint) {
            CameraLens.FocalHint.Ultrawide -> sorted.first().id
            CameraLens.FocalHint.Telephoto -> sorted.last().id
            CameraLens.FocalHint.Normal -> {
                // Pick the middle one (main camera is typically the middle focal length)
                if (sorted.size >= 3) sorted[1].id
                else if (sorted.size == 2) sorted[1].id  // longer focal = main on 2-cam setups
                else sorted.first().id
            }
        }
    }
}
