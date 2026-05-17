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
    private val encodedSurfaceProvider: () -> Surface,
    private val lensProvider: () -> CameraLens = { CameraLens.Back },
) {
    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("OpenStreamCamera")
    private lateinit var handler: Handler
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (!thread.isAlive) {
            thread.start()
        }
        handler = Handler(thread.looper)
        val cameraId = selectCameraId(lensProvider())
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                createSession(device)
            }

            override fun onDisconnected(device: CameraDevice) = stop()
            override fun onError(device: CameraDevice, error: Int) = stop()
        }, handler)
    }

    fun stop() {
        session?.close()
        camera?.close()
        session = null
        camera = null
    }

    fun setManualExposure(iso: Int, exposureTimeNs: Long) {
        // Wired in the request builder once remote controls are added.
        require(iso > 0)
        require(exposureTimeNs > 0)
    }

    private fun createSession(device: CameraDevice) {
        val preview = previewSurfaceProvider()
        val encoded = encodedSurfaceProvider()
        device.createCaptureSession(
            listOf(preview, encoded),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(captureSession: CameraCaptureSession) {
                    session = captureSession
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(preview)
                        addTarget(encoded)
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
