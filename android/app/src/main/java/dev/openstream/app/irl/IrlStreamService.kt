package dev.openstream.app.irl

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.drawable.Icon
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.media.MediaCodecInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import dev.openstream.app.camera.Camera2Controller
import dev.openstream.app.camera.CameraLens
import dev.openstream.app.encoder.CodecPreference
import dev.openstream.app.encoder.EncodedAccessUnit
import dev.openstream.app.encoder.MediaCodecAudioEncoder
import dev.openstream.app.encoder.MediaCodecVideoEncoder
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Tracks the latest IRL run started by the service (main thread only). A
 * session end only releases foreground when it belongs to that run, so an end
 * from a replaced run, delivered late, cannot drop a newer stream's protection.
 */
internal class LiveRunTracker {
    private var current = IrlSession.NO_RUN

    fun begin(run: Long) {
        current = run
    }

    /** True when [run] is the current run, which is then over. */
    fun end(run: Long): Boolean {
        if (run == IrlSession.NO_RUN || run != current) return false
        current = IrlSession.NO_RUN
        return true
    }
}

/**
 * Foreground service owning IRL capture, encoding and publishing. It is both
 * started and bound: the activity binds for control, and the started state keeps
 * a live session running after the activity unbinds (screen off, background).
 */
class IrlStreamService : Service() {
    inner class LocalBinder : Binder() {
        val api: IrlServiceApi get() = this@IrlStreamService.api
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hudListeners = mutableListOf<(LiveHud) -> Unit>()
    @Volatile private var hud = LiveHud()
    private lateinit var executor: ScheduledThreadPoolExecutor
    private lateinit var session: IrlSession
    private lateinit var networkMonitor: NetworkMonitor
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLocks: List<WifiManager.WifiLock> = emptyList()
    private var bound = false
    private var foreground = false
    private var endpointLabel = ""
    private var notifiedText: String? = null
    // Main thread: only the latest run's end may drop foreground.
    private val runs = LiveRunTracker()

    // Camera state; Camera2Controller is internally synchronized, these fields use cameraLock.
    private val cameraLock = Any()
    private var camera: Camera2Controller? = null
    private var cameraFps = DEFAULT_FPS
    private var cameraRunning = false
    @Volatile private var lens: CameraLens = CameraLens.Back
    @Volatile private var previewSurface: Surface? = null
    @Volatile private var streamingSurface: Surface? = null
    // Separate lock: the sink is created from Camera2Controller callbacks that hold its own lock.
    private val sinkLock = Any()
    private var sink: PreviewSink? = null
    @Volatile private var muted = false

    // Enumeration queries every camera's characteristics; the UI asks often, from the main thread.
    private val lenses: List<CameraLens> by lazy {
        Camera2Controller(this, previewSurfaceProvider = { error("Enumeration only") }).availableLenses()
    }

    private val api = object : IrlServiceApi {
        override fun goLive(settings: LiveSettings) = this@IrlStreamService.goLive(settings)
        override fun stopLive() = session.stop()
        override fun attachPreview(surface: Surface?) = this@IrlStreamService.attachPreview(surface)
        override fun availableLenses(): List<CameraLens> = lenses
        override fun switchLens(lens: CameraLens) = this@IrlStreamService.switchLens(lens)
        override fun setMuted(muted: Boolean) {
            this@IrlStreamService.muted = muted
            session.setMuted(muted)
        }
        override fun addHudListener(listener: (LiveHud) -> Unit) {
            hudListeners += listener
            listener(hud)
        }
        override fun removeHudListener(listener: (LiveHud) -> Unit) {
            hudListeners -= listener
        }
        override fun currentHud(): LiveHud = hud
    }

    override fun onCreate() {
        super.onCreate()
        executor = ScheduledThreadPoolExecutor(1) { runnable -> Thread(runnable, "shinIrlSession") }.apply {
            executeExistingDelayedTasksAfterShutdownPolicy = false
        }
        session = IrlSession(
            transportFactory = LiveTransports::forSettings,
            media = Pipeline(),
            scheduler = ExecutorSessionScheduler(executor),
            clock = SystemClock::elapsedRealtime,
            mediaClock = { MediaClockSample(System.nanoTime() / 1000, SystemClock.elapsedRealtimeNanos() / 1000) },
            onHud = ::onSessionHud,
            onRunEnded = ::onSessionRunEnded,
        )
        session.setLens(lens)
        networkMonitor = NetworkMonitor(this) { id, label -> session.onNetworkChanged(id, label) }
        networkMonitor.start()
        val power = getSystemService(PowerManager::class.java)
        if (power != null) {
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                session.setThermalWarning(status >= PowerManager.THERMAL_STATUS_SEVERE)
            }
            thermalListener = listener
            runCatching { power.addThermalStatusListener(mainExecutor, listener) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            session.stop()
            if (!bound && !foreground) stopSelf()
        }
        // Never auto-restart: camera/microphone FGS cannot start from the background.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        bound = true
        return binder
    }

    override fun onRebind(intent: Intent?) {
        bound = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        bound = false
        hudListeners.clear()
        if (!foreground) {
            attachPreview(null)
            stopSelf()
        }
        return true
    }

    override fun onDestroy() {
        session.stop()
        executor.shutdown()
        networkMonitor.stop()
        thermalListener?.let { listener ->
            runCatching { getSystemService(PowerManager::class.java)?.removeThermalStatusListener(listener) }
        }
        thermalListener = null
        hudListeners.clear()
        leaveForeground()
        // Camera teardown joins camera threads; keep it off the main thread.
        Thread({
            runCatching { executor.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            synchronized(cameraLock) {
                camera?.stop()
                camera = null
                cameraRunning = false
            }
            synchronized(sinkLock) {
                sink?.release()
                sink = null
            }
        }, "shinIrlTeardown").start()
        super.onDestroy()
    }

    private fun goLive(settings: LiveSettings) {
        endpointLabel = runCatching { PublishRedaction.endpoint(settings.publishUrl()) }.getOrDefault("")
        // Started state keeps the session alive after the activity unbinds.
        runCatching { startService(intent(this)) }
        val error = enterForeground()
        if (error != null) {
            onSessionHud(hud.copy(status = LiveStatus.Error(error)))
            return
        }
        runs.begin(session.start(settings))
    }

    /** HUDs only update the UI and notification; foreground follows run ends. */
    private fun onSessionHud(next: LiveHud) {
        hud = next
        mainHandler.post {
            hudListeners.toList().forEach { it(next) }
            if (foreground && next.status !is LiveStatus.Idle && next.status !is LiveStatus.Error) {
                updateNotification(next.status)
            }
        }
    }

    /** Posted after the run's final HUD; an end from a replaced run is ignored. */
    private fun onSessionRunEnded(run: Long) {
        mainHandler.post {
            if (!runs.end(run)) return@post
            leaveForeground()
            if (!bound) stopSelf()
        }
    }

    // region Foreground, notification and locks

    /** Returns a user-facing error, or null once foreground. */
    private fun enterForeground(): String? {
        if (foreground) return null
        ensureChannel()
        val notification = buildNotification(statusText(LiveStatus.Connecting))
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (hasPermission(Manifest.permission.RECORD_AUDIO)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(NOTIFICATION_ID, notification, types)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Could not enter foreground", error)
            return if (!hasPermission(Manifest.permission.CAMERA)) {
                "Camera permission is required to go live."
            } else {
                "Android blocked the live service. Open OpenStream and try again."
            }
        }
        foreground = true
        notifiedText = null
        acquireLocks()
        return null
    }

    private fun leaveForeground() {
        if (!foreground) return
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        releaseLocks()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "IRL Live", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while OpenStream is streaming live"
                setShowBadge(false)
            },
        )
    }

    private fun updateNotification(status: LiveStatus) {
        val text = statusText(status)
        if (text == notifiedText) return
        notifiedText = text
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    private fun statusText(status: LiveStatus): String {
        val state = when (status) {
            is LiveStatus.Idle -> "Stopped"
            is LiveStatus.Connecting -> "Connecting"
            is LiveStatus.Live -> "Live"
            is LiveStatus.Reconnecting -> "Reconnecting in ${status.retryInSeconds}s"
            is LiveStatus.Error -> status.message
        }
        return if (endpointLabel.isEmpty()) state else "$state · $endpointLabel"
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, IrlActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, IrlStreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("OpenStream IRL")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Stop", stop).build())
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    // Released in leaveForeground(), which runs on every stop/error path and onDestroy.
    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        releaseLocks()
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenStream:IrlLive")
            ?.apply { setReferenceCounted(false); acquire() }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val modes = listOf(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WifiManager.WIFI_MODE_FULL_HIGH_PERF)
        wifiLocks = modes.mapNotNull { mode ->
            runCatching {
                wifi?.createWifiLock(mode, "OpenStream:IrlLive:$mode")?.apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
        wifiLocks.forEach { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wifiLocks = emptyList()
    }

    // endregion

    // region Camera

    private fun hasPermission(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun controller(fps: Int = cameraFps): Camera2Controller {
        val existing = camera
        if (existing != null && cameraFps == fps) return existing
        existing?.stop()
        cameraRunning = false
        cameraFps = fps
        return Camera2Controller(
            this,
            previewSurfaceProvider = { previewSurface?.takeIf { it.isValid } ?: previewSink().surface },
            lensProvider = { lens },
            targetFps = fps,
            // MainActivity releases the camera on a background thread after IrlActivity opens.
            inUseRetryDelaysMs = CAMERA_IN_USE_RETRY_MS,
        ).also { camera = it }
    }

    /** Frames need a consumer when no UI preview exists, so the camera never stalls. */
    private fun previewSink(): PreviewSink = synchronized(sinkLock) {
        sink ?: PreviewSink(sinkSize()).also { sink = it }
    }

    private fun attachPreview(surface: Surface?) = synchronized(cameraLock) {
        previewSurface = surface
        val encoderSurface = streamingSurface
        when {
            encoderSurface != null -> controller().startStreaming(encoderSurface) // rebuilds session with new preview
            surface != null -> {
                cameraRunning = true
                controller().startPreview()
            }
            cameraRunning -> {
                cameraRunning = false
                camera?.stop()
            }
        }
    }

    private fun switchLens(next: CameraLens) {
        synchronized(cameraLock) {
            lens = next
            if (cameraRunning) camera?.switchLens(next)
        }
        session.setLens(next)
    }

    private fun sinkSize(): Size {
        val manager = getSystemService(CameraManager::class.java) ?: return FALLBACK_SINK
        val common = runCatching {
            manager.cameraIdList.map { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.PRIVATE)?.toSet().orEmpty()
            }.reduceOrNull { acc, sizes -> acc intersect sizes }.orEmpty()
        }.getOrDefault(emptySet())
        return common.filter { it.width >= 320 && it.height >= 240 }
            .minWithOrNull(compareBy({ if (it.width * 9 == it.height * 16) 0 else 1 }, { it.width * it.height }))
            ?: FALLBACK_SINK
    }

    private class PreviewSink(size: Size) {
        private val thread = HandlerThread("shinIrlPreviewSink").apply { start() }
        private val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.PRIVATE, 2).apply {
            setOnImageAvailableListener({ reader -> runCatching { reader.acquireLatestImage()?.close() } }, Handler(thread.looper))
        }
        val surface: Surface = reader.surface

        fun release() {
            runCatching { reader.close() }
            thread.quitSafely()
        }
    }

    // endregion

    /** Camera + encoders for the session; runs on the session thread. */
    private inner class Pipeline : IrlMediaPipeline {
        private var video: MediaCodecVideoEncoder? = null
        private var audio: MediaCodecAudioEncoder? = null

        override fun start(
            video: LiveVideoParams,
            audio: LiveAudioParams,
            onVideo: (EncodedAccessUnit) -> Unit,
            onAudio: (EncodedAccessUnit) -> Unit,
        ): LiveAudioParams? {
            stop()
            isLive = true
            val encoder = MediaCodecVideoEncoder(
                preference = CodecPreference.ForceAvc,
                width = video.width,
                height = video.height,
                fps = video.fps,
                bitrate = video.bitrate,
                keyframeIntervalSeconds = video.keyframeIntervalSeconds,
                avcProfile = MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
                onEncodedAccessUnit = onVideo,
            )
            encoder.start()
            this.video = encoder
            synchronized(cameraLock) {
                val surface = encoder.inputSurface()
                streamingSurface = surface
                cameraRunning = true
                controller(video.fps).startStreaming(surface)
            }
            return startAudio(audio, onAudio)
        }

        private fun startAudio(requested: LiveAudioParams, onAudio: (EncodedAccessUnit) -> Unit): LiveAudioParams? {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                Log.w(TAG, "Microphone permission missing; streaming without audio")
                return null
            }
            for (channels in listOf(requested.channelCount, 1).distinct()) {
                val params = requested.copy(channelCount = channels)
                val encoder = MediaCodecAudioEncoder(
                    this@IrlStreamService,
                    sampleRate = params.sampleRate,
                    channelCount = params.channelCount,
                    bitrate = params.bitrate,
                    onEncodedAccessUnit = onAudio,
                ).apply { muted = this@IrlStreamService.muted }
                try {
                    encoder.start()
                    audio = encoder
                    return params
                } catch (error: Exception) {
                    Log.w(TAG, "Audio start failed with $channels channel(s)", error)
                }
            }
            return null
        }

        override fun stop() {
            synchronized(cameraLock) {
                if (streamingSurface != null) {
                    streamingSurface = null
                    if (previewSurface?.isValid == true) {
                        camera?.stopStreaming()
                    } else {
                        cameraRunning = false
                        camera?.stop()
                    }
                }
            }
            video?.stop()
            video = null
            audio?.stop()
            audio = null
            isLive = false
        }

        override fun setVideoBitrate(bitrate: Int) {
            video?.setBitrate(bitrate)
        }

        override fun requestKeyframe() {
            video?.requestKeyframe()
        }

        override fun setMuted(muted: Boolean) {
            audio?.muted = muted
        }
    }

    companion object {
        const val ACTION_STOP = "dev.openstream.app.irl.STOP"
        private const val TAG = "shinIrlService"
        private const val CHANNEL_ID = "irl_live"
        private const val NOTIFICATION_ID = 4107
        private const val DEFAULT_FPS = 30
        private const val SHUTDOWN_TIMEOUT_MS = 3_000L
        private val FALLBACK_SINK = Size(640, 480)
        private val CAMERA_IN_USE_RETRY_MS = listOf(100L, 200L, 400L, 800L, 1_600L)

        /**
         * True while an IRL session owns the camera and encoders (from pipeline
         * start until it stops). MainActivity checks it so OBS mode never takes
         * the camera or the process-global native SRT session from a live stream.
         */
        @Volatile
        var isLive: Boolean = false
            private set

        /** Starts (not yet foreground) so the activity can bind; call while visible. */
        fun intent(context: Context): Intent = Intent(context, IrlStreamService::class.java)
    }
}
