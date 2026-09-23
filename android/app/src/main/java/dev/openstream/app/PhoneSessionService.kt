package dev.openstream.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log

/** Owns the camera/media/network graph for longer than any one Activity instance. */
class PhoneSessionService : Service() {
    inner class LocalBinder : Binder() {
        internal fun runtime(): PhoneSessionRuntime = sessionRuntime
        internal fun lifecycleState(): SessionOwnerState = ownerState
        internal fun stop() = stopSession()
        internal fun start() = startSession()
        internal fun activityHidden() {
            ownerState = transitionSessionOwner(ownerState, SessionOwnerEvent.ActivityHidden)
        }
        internal fun activityResumed() {
            ownerState = if (hasCameraPermission()) {
                transitionSessionOwner(ownerState, SessionOwnerEvent.ActivityVisible)
            } else {
                if (foregroundStarted) stopSessionForPermissionLoss()
                else transitionSessionOwner(ownerState, SessionOwnerEvent.CameraPermissionRevoked)
                ownerState
            }
        }
    }

    private lateinit var sessionRuntime: PhoneSessionRuntime
    @Volatile private var foregroundStarted = false
    @Volatile private var ownerState: SessionOwnerState =
        transitionSessionOwner(SessionOwnerState.Available, SessionOwnerEvent.ProcessRecreated)
    private val binder = LocalBinder()
    private val preferences by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        val savedPort = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
            .getInt(SettingsActivity.KEY_LISTENING_PORT, DEFAULT_PORT)
            .takeIf { it in 1024..65535 } ?: DEFAULT_PORT
        sessionRuntime = PhoneSessionRuntime(applicationContext, savedPort)
        if (preferences.getBoolean(KEY_STOPPED, false)) {
            sessionRuntime.phoneSessionState.stop()
            ownerState = transitionSessionOwner(ownerState, SessionOwnerEvent.Stop)
        }
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        if (::sessionRuntime.isInitialized) {
            if (sessionRuntime.phoneSessionState.snapshot.status != PhoneSessionStatus.Stopped) {
                sessionRuntime.phoneSessionState.stop()
                sessionRuntime.clearReservation()
                sessionRuntime.stopSessionResources { sessionRuntime.sessionWorker.close() }
            } else {
                sessionRuntime.sessionWorker.close()
            }
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession()
            ACTION_START -> startSession()
            else -> if (hasCameraPermission() && !preferences.getBoolean(KEY_STOPPED, false)) {
                ownerState = transitionSessionOwner(ownerState, SessionOwnerEvent.Start)
                ownerState = transitionSessionOwner(ownerState, SessionOwnerEvent.ActivityVisible)
                startForegroundSession()
                sessionRuntime.startComponents()
            } else if (!hasCameraPermission()) {
                sessionRuntime.observer?.onSessionError("Camera permission is required to start a session")
            }
        }
        // Process death discards the runtime and its SRT socket. Android must not auto-restart a
        // camera session with an unconfirmed reservation; the next foreground Activity rebuilds it.
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        ownerState = transitionSessionOwner(ownerState, SessionOwnerEvent.TaskRemoved)
        if (!preferences.getBoolean(KEY_STOPPED, false) && hasCameraPermission()) {
            startForegroundSession()
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun startForegroundSession() {
        if (!hasCameraPermission()) {
            stopSessionForPermissionLoss()
            return
        }
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    ) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    } else {
                        0
                    }
                startForeground(NOTIFICATION_ID, notification, serviceType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
        } catch (error: SecurityException) {
            Log.e(TAG, "Could not keep the camera session in the foreground", error)
            stopSessionForPermissionLoss()
        }
    }

    private fun stopSession() {
        ownerState = transitionSessionOwner(ownerState, SessionOwnerEvent.Stop)
        preferences.edit().putBoolean(KEY_STOPPED, true).apply()
        foregroundStarted = false
        sessionRuntime.phoneSessionState.stop()
        sessionRuntime.sessionWorkGeneration += 1
        sessionRuntime.clearReservation()
        sessionRuntime.observer?.onReservationChanged()
        sessionRuntime.observer?.onSessionStateChanged()
        sessionRuntime.stopSessionResources {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelf()
        }
    }

    private fun stopSessionForPermissionLoss() {
        ownerState = transitionSessionOwner(ownerState, SessionOwnerEvent.CameraPermissionRevoked)
        preferences.edit().putBoolean(KEY_STOPPED, true).apply()
        sessionRuntime.phoneSessionState.stop()
        sessionRuntime.sessionWorkGeneration += 1
        sessionRuntime.clearReservation()
        sessionRuntime.observer?.onSessionError("Camera permission was revoked; the session stopped")
        sessionRuntime.observer?.onSessionStateChanged()
        sessionRuntime.stopSessionResources {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelf()
        }
    }

    private fun startSession() {
        if (!hasCameraPermission()) {
            ownerState = transitionSessionOwner(ownerState, SessionOwnerEvent.CameraPermissionRevoked)
            sessionRuntime.phoneSessionState.stop()
            sessionRuntime.observer?.onSessionError("Camera permission is required to start a session")
            sessionRuntime.observer?.onSessionStateChanged()
            if (foregroundStarted) stopSessionForPermissionLoss() else stopSelf()
            return
        }
        ownerState = transitionSessionOwner(ownerState, SessionOwnerEvent.Start)
        ownerState = transitionSessionOwner(ownerState, SessionOwnerEvent.ActivityVisible)
        preferences.edit().putBoolean(KEY_STOPPED, false).apply()
        sessionRuntime.phoneSessionState.start()
        startForegroundSession()
        if (foregroundStarted) {
            sessionRuntime.startComponents()
            sessionRuntime.observer?.onSessionStateChanged()
        }
    }

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAction = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, PhoneSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("OpenStream camera is available")
            .setContentText("OpenStream keeps its camera session available in the background")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopAction)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Camera session", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val ACTION_START = "dev.openstream.app.session.START"
        const val ACTION_STOP = "dev.openstream.app.session.STOP"
        const val PREFS_NAME = "phone-session-owner"
        const val KEY_STOPPED = "stopped-by-user"
        private const val TAG = "shinSessionOwner"
        private const val CHANNEL_ID = "camera-session"
        private const val NOTIFICATION_ID = 42
        private const val REQUEST_OPEN = 1
        private const val REQUEST_STOP = 2
        private const val DEFAULT_PORT = 9000
    }
}
