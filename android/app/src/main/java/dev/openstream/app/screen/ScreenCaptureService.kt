package dev.openstream.app.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import dev.openstream.app.MainActivity

/** Keeps consented capture alive while another app is visible. Tokens are never persisted. */
class ScreenCaptureService : Service() {
    inner class LocalBinder : Binder() {
        fun capture(resultCode: Int, data: Intent, surface: Surface, width: Int, height: Int,
                    densityDpi: Int, microphone: Boolean, onStopped: (String) -> Unit) {
            check(projection == null) { "Screen capture is already active" }
            require(width > 0 && height > 0 && densityDpi > 0)
            stopped = onStopped
            try {
                promote(microphone)
                val manager = getSystemService(MediaProjectionManager::class.java)
                val capture = checkNotNull(manager.getMediaProjection(resultCode, data))
                projection = capture
                capture.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { finishCapture("Screen sharing permission ended") }
                }, Handler(Looper.getMainLooper()))
                display = capture.createVirtualDisplay(
                    "OpenStream screen", width, height, densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null,
                )
            } catch (error: Exception) {
                finishCapture("Screen sharing failed: ${error.message}")
            }
        }
        fun stop() { finishCapture(null) }
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var stopped: ((String) -> Unit)? = null

    override fun onBind(intent: Intent): IBinder = LocalBinder()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) finishCapture("Screen sharing stopped")
        else promote(intent?.getBooleanExtra("microphone", false) == true)
        return START_NOT_STICKY
    }

    private fun promote(microphone: Boolean) {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Screen sharing", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(this, 1, Intent(this, ScreenCaptureService::class.java)
            .setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("OpenStream is sharing your screen")
            .setContentText(if (microphone) "Screen and microphone are live" else "Screen is live, audio is off")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop sharing", stop).build()).build()
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
            if (microphone && android.os.Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        startForeground(101, notification, types)
    }

    private fun finishCapture(reason: String?) {
        val callback = stopped
        stopped = null
        display?.release()
        display = null
        val capture = projection
        projection = null
        capture?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (reason != null) callback?.invoke(reason)
    }

    override fun onDestroy() {
        finishCapture("Screen sharing service ended")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "openstream_screen"
        private const val ACTION_STOP = "dev.openstream.app.STOP_SCREEN_CAPTURE"
    }
}
