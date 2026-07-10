package dev.openstream.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.view.Surface
import dev.openstream.app.MainActivity
import dev.openstream.app.R

/**
 * Lifetime owner for an explicitly armed unattended camera session.
 *
 * Android still requires the user to open and arm the app after process death, reboot or
 * force-stop. This service deliberately uses START_NOT_STICKY and never cold-starts the camera.
 */
class OpenStreamCameraService : Service() {
    private val binder = LocalBinder()
    private lateinit var surfaceTexture: SurfaceTexture
    private lateinit var headlessSurface: Surface
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var armed = false
    @Volatile private var sessionOwner: SessionOwner? = null

    override fun onCreate() {
        super.onCreate()
        surfaceTexture = SurfaceTexture(false).apply { setDefaultBufferSize(HEADLESS_WIDTH, HEADLESS_HEIGHT) }
        headlessSurface = Surface(surfaceTexture)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ARM -> armInternal()
            ACTION_STOP -> {
                sessionOwner?.onRemoteStop()
                disarmInternal(stopService = true)
            }
            ACTION_DISARM -> disarmInternal(stopService = true)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        // An armed session is intentionally allowed to continue when the UI task is dismissed.
        if (!armed) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        sessionOwner = null
        disarmInternal(stopService = false)
        headlessSurface.release()
        surfaceTexture.release()
        super.onDestroy()
    }

    inner class LocalBinder : Binder() {
        fun service(): OpenStreamCameraService = this@OpenStreamCameraService
    }

    fun attachSession(owner: SessionOwner) {
        sessionOwner = owner
        owner.onHeadlessSurfaceAvailable(headlessSurface)
    }

    fun detachSession(owner: SessionOwner) {
        if (sessionOwner === owner) sessionOwner = null
    }

    fun arm() = armInternal()

    fun disarm() = disarmInternal(stopService = true)

    fun isArmed(): Boolean = armed

    fun previewSurface(): Surface = headlessSurface

    private fun armInternal() {
        if (armed) {
            startForeground(NOTIFICATION_ID, buildNotification())
            return
        }
        armed = true
        acquireLocks()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun disarmInternal(stopService: Boolean) {
        armed = false
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (stopService) stopSelf()
    }

    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock?.isHeld != true) {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG)
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.camera_service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.camera_service_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val showIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val showPending = PendingIntent.getActivity(
            this,
            0,
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, OpenStreamCameraService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.camera_service_title))
            .setContentText(getString(R.string.camera_service_text))
            .setContentIntent(showPending)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, getString(R.string.camera_service_stop), stopPending).build())
            .build()
    }

    interface SessionOwner {
        fun onHeadlessSurfaceAvailable(surface: Surface)
        fun onRemoteStop()
    }

    companion object {
        const val ACTION_ARM = "dev.openstream.app.action.ARM_CAMERA"
        const val ACTION_DISARM = "dev.openstream.app.action.DISARM_CAMERA"
        const val ACTION_STOP = "dev.openstream.app.action.STOP_CAMERA"
        const val NOTIFICATION_CHANNEL_ID = "openstream_camera_session"
        const val NOTIFICATION_ID = 2001
        const val LOCAL_BINDER = "OpenStreamCameraService.LocalBinder"
        private const val WAKE_LOCK_TAG = "OpenStream:CameraSession"
        private const val WIFI_LOCK_TAG = "OpenStreamCameraSession"
        private const val HEADLESS_WIDTH = 1_920
        private const val HEADLESS_HEIGHT = 1_080

        fun armIntent(context: Context): Intent = Intent(context, OpenStreamCameraService::class.java).setAction(ACTION_ARM)
    }
}
