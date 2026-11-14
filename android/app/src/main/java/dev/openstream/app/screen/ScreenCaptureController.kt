package dev.openstream.app.screen

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.view.Surface

/** One consent result starts exactly one virtual display. Call stop before releasing the encoder. */
class ScreenCaptureController(context: Context) {
    private val context = context.applicationContext
    private var connection: ServiceConnection? = null
    private var binder: ScreenCaptureService.LocalBinder? = null

    fun consentIntent(): Intent = context.getSystemService(MediaProjectionManager::class.java)
        .createScreenCaptureIntent()

    fun start(resultCode: Int, data: Intent, surface: Surface, width: Int, height: Int,
              densityDpi: Int, microphone: Boolean, onStopped: (String) -> Unit) {
        require(resultCode == Activity.RESULT_OK) { "Screen sharing consent is required" }
        check(connection == null) { "Screen sharing is already starting or active" }
        val intent = Intent(context, ScreenCaptureService::class.java).putExtra("microphone", microphone)
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (connection !== this) return
                val local = service as ScreenCaptureService.LocalBinder
                binder = local
                local.capture(resultCode, data, surface, width, height, densityDpi, microphone) { reason ->
                    stop()
                    onStopped(reason)
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (connection !== this) return
                stop()
                onStopped("Screen sharing service disconnected")
            }
            override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
            override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
        }
        connection = serviceConnection
        try {
            context.startForegroundService(intent)
            check(context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                "Cannot bind screen sharing service"
            }
        } catch (error: Exception) {
            stop()
            throw error
        }
    }

    fun stop() {
        val activeConnection = connection
        connection = null
        binder?.stop()
        binder = null
        if (activeConnection != null) runCatching { context.unbindService(activeConnection) }
        context.stopService(Intent(context, ScreenCaptureService::class.java))
    }
}
