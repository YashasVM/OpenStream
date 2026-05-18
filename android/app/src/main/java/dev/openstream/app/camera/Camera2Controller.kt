package dev.openstream.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraCharacteristics
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

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

    @SuppressLint("MissingPermission")
    fun startPreview() {
        if (!thread.isAlive) {
            thread.start()
        }
        handler = Handler(thread.looper)
        if (camera != null) {
            createSession()
            return
        }
        val cameraId = selectCameraId(lensProvider())
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                createSession()
            }

            override fun onDisconnected(device: CameraDevice) = stop()
            override fun onError(device: CameraDevice, error: Int) = stop()
        }, handler)
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
        session?.close()
        camera?.close()
        session = null
        camera = null
        streamingSurface = null
    }

    fun setManualExposure(iso: Int, exposureTimeNs: Long) {
        // Wired in the request builder once remote controls are added.
        require(iso > 0)
        require(exposureTimeNs > 0)
    }

    private fun createSession() {
        val device = camera ?: return
        val preview = previewSurfaceProvider()
        val encoded = streamingSurface
        val surfaces = if (encoded != null) listOf(preview, encoded) else listOf(preview)
        session?.close()
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
                    }.build()
                    captureSession.setRepeatingRequest(request, null, handler)
                }

                override fun onConfigureFailed(captureSession: CameraCaptureSession) = stop()
            },
            handler,
        )
    }

    private fun selectCameraId(lens: CameraLens): String {
        val facing = when (lens) {
            CameraLens.Back -> CameraCharacteristics.LENS_FACING_BACK
            CameraLens.Front -> CameraCharacteristics.LENS_FACING_FRONT
        }
        return cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == facing
        } ?: cameraManager.cameraIdList.first()
    }
}
