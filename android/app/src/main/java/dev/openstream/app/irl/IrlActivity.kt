package dev.openstream.app.irl

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.openstream.app.R
import dev.openstream.app.camera.CameraLens

/**
 * IRL Live (Beta) screen. A thin view over [IrlServiceApi]: capture, encoding
 * and publishing live in [IrlStreamService], so locking the phone or leaving
 * this screen never ends a stream; only Stop (here or in the notification) does.
 */
class IrlActivity : Activity() {

    private lateinit var previewContainer: FrameLayout
    private lateinit var preview: SurfaceView
    private lateinit var topBar: View
    private lateinit var bottomPanel: View
    private lateinit var statusPill: View
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statsRow: View
    private lateinit var statBitrate: TextView
    private lateinit var statFps: TextView
    private lateinit var statDropped: TextView
    private lateinit var statNetwork: TextView
    private lateinit var thermalBanner: View
    private lateinit var lensRow: LinearLayout
    private lateinit var setupPanel: View
    private lateinit var platformRow: LinearLayout
    private lateinit var serverInput: EditText
    private lateinit var keyGroup: View
    private lateinit var keyInput: EditText
    private lateinit var keyToggle: TextView
    private lateinit var qualityRow: LinearLayout
    private lateinit var qualityDetail: TextView
    private lateinit var formError: TextView
    private lateinit var btnGoLive: TextView
    private lateinit var liveControls: View
    private lateinit var btnMute: TextView
    private lateinit var btnDim: TextView
    private lateinit var btnStop: TextView
    private lateinit var screenOffOverlay: View

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var settingsStore: LiveSettingsStore
    private var platform = LivePlatform.TWITCH
    private var quality = LiveQuality.P720
    private var keyVisible = false

    private var api: IrlServiceApi? = null
    private var bound = false
    private var hud = LiveHud()
    private var shownLenses: List<CameraLens> = emptyList()
    private var shownLens: CameraLens? = null
    private var dimmed = false
    private var originalBrightness = -1f
    private var dialog: AlertDialog? = null

    // The service may publish from any thread; the UI only reads HUD state on main.
    private val hudListener: (LiveHud) -> Unit = { next -> mainHandler.post { if (api != null) render(next) } }

    private val statusTicker = object : Runnable {
        override fun run() {
            renderStatus()
            if (hud.status is LiveStatus.Live) mainHandler.postDelayed(this, TICK_MS)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? IrlStreamService.LocalBinder)?.let { runCatching { it.api }.getOrNull() }
            if (service == null) {
                showFormError(getString(R.string.irl_service_unavailable), null)
                return
            }
            api = service
            service.addHudListener(hudListener)
            render(service.currentHud())
            attachPreviewIfReady()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            api = null
            render(LiveHud())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_irl)
        // Translucent system bars stop adjustResize from working; pan keeps the focused field visible.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        bindViews()
        settingsStore = LiveSettingsStore(this)
        loadSettings(runCatching { settingsStore.load() }.getOrDefault(LiveSettings()))
        setupForm()
        setupLiveControls()
        setupInsets()
        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = attachPreviewIfReady()
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // Streaming continues without a preview while the screen is off or the app is hidden.
                api?.attachPreview(null)
            }
        })
        previewContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPreview() }
        render(hud)
    }

    override fun onStart() {
        super.onStart()
        val intent = IrlStreamService.intent(this)
        // Started (not only bound) so a stream outlives this screen's unbind.
        runCatching { startService(intent) }
        bound = bindService(intent, connection, BIND_AUTO_CREATE)
    }

    override fun onPause() {
        // Mask before the recents snapshot and persist whatever the form holds.
        setKeyVisible(false)
        runCatching { settingsStore.save(formSettings()) }
        // MainActivity.onStart reopens its camera before this onStop runs; free ours first when leaving idle.
        if (isFinishing && !IrlUi.isActive(hud.status)) api?.attachPreview(null)
        super.onPause()
    }

    override fun onStop() {
        mainHandler.removeCallbacks(statusTicker)
        dialog?.dismiss()
        dialog = null
        val service = api
        val live = service != null && IrlUi.isActive(runCatching { service.currentHud().status }.getOrDefault(hud.status))
        service?.let {
            it.attachPreview(null)
            it.removeHudListener(hudListener)
        }
        api = null
        if (bound) {
            unbindService(connection)
            bound = false
        }
        // An idle service has nothing to do once this screen is gone; a live one keeps running in the foreground.
        if (!live) runCatching { stopService(IrlStreamService.intent(this)) }
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Uses the platform back API to avoid an AndroidX dependency")
    override fun onBackPressed() {
        leave()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSIONS_REQUEST_CODE) return
        if (!granted(Manifest.permission.CAMERA)) {
            showFormError(getString(R.string.irl_camera_required), null)
            return
        }
        attachPreviewIfReady()
        render(hud)
        requestGoLive()
    }

    // ─────────────────────────── Views ───────────────────────────

    private fun bindViews() {
        previewContainer = findViewById(R.id.irlPreviewContainer)
        preview = findViewById(R.id.irlPreview)
        topBar = findViewById(R.id.irlTopBar)
        bottomPanel = findViewById(R.id.irlBottom)
        statusPill = findViewById(R.id.irlStatusPill)
        statusDot = findViewById(R.id.irlStatusDot)
        statusText = findViewById(R.id.irlStatusText)
        statsRow = findViewById(R.id.irlStatsRow)
        statBitrate = findViewById(R.id.irlStatBitrate)
        statFps = findViewById(R.id.irlStatFps)
        statDropped = findViewById(R.id.irlStatDropped)
        statNetwork = findViewById(R.id.irlStatNetwork)
        thermalBanner = findViewById(R.id.irlThermalBanner)
        lensRow = findViewById(R.id.irlLensRow)
        setupPanel = findViewById(R.id.irlSetupPanel)
        platformRow = findViewById(R.id.irlPlatformRow)
        serverInput = findViewById(R.id.irlServer)
        keyGroup = findViewById(R.id.irlKeyGroup)
        keyInput = findViewById(R.id.irlKey)
        keyToggle = findViewById(R.id.irlKeyToggle)
        qualityRow = findViewById(R.id.irlQualityRow)
        qualityDetail = findViewById(R.id.irlQualityDetail)
        formError = findViewById(R.id.irlFormError)
        btnGoLive = findViewById(R.id.irlGoLive)
        liveControls = findViewById(R.id.irlLiveControls)
        btnMute = findViewById(R.id.irlMute)
        btnDim = findViewById(R.id.irlDim)
        btnStop = findViewById(R.id.irlStop)
        screenOffOverlay = findViewById(R.id.irlScreenOffOverlay)
    }

    private fun loadSettings(settings: LiveSettings) {
        platform = settings.platform
        quality = settings.quality
        serverInput.setText(settings.server.ifBlank { settings.platform.server })
        keyInput.setText(settings.key)
    }

    private fun formSettings(): LiveSettings =
        IrlUi.settings(platform, serverInput.text.toString(), keyInput.text.toString(), quality)

    private fun setupForm() {
        val clearError = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = clearFormError()
        }
        serverInput.addTextChangedListener(clearError)
        keyInput.addTextChangedListener(clearError)
        keyToggle.setOnClickListener { setKeyVisible(!keyVisible) }
        btnGoLive.setOnClickListener { requestGoLive() }
        findViewById<View>(R.id.irlBack).setOnClickListener { leave() }
        renderForm()
    }

    private fun setupLiveControls() {
        btnMute.setOnClickListener { api?.setMuted(!hud.muted) }
        btnDim.setOnClickListener { setDimmed(true) }
        screenOffOverlay.setOnClickListener { setDimmed(false) }
        btnStop.setOnClickListener { confirmStop() }
    }

    private fun selectPlatform(next: LivePlatform) {
        if (next == platform) return
        serverInput.setText(IrlUi.serverForPlatformSwitch(serverInput.text.toString(), next))
        platform = next
        clearFormError()
        renderForm()
    }

    private fun renderForm() {
        platformRow.removeAllViews()
        LivePlatform.entries.forEach { entry ->
            platformRow.addView(pill(entry.label, entry == platform) { selectPlatform(entry) })
        }
        qualityRow.removeAllViews()
        LiveQuality.entries.forEach { entry ->
            qualityRow.addView(pill(entry.label, entry == quality) {
                quality = entry
                renderForm()
            })
        }
        serverInput.hint = IrlUi.serverHint(platform)
        keyGroup.visibility = if (IrlUi.showsKey(platform)) View.VISIBLE else View.GONE
        keyInput.hint = IrlUi.keyHint(platform)
        qualityDetail.text = IrlUi.qualityDetail(quality, platform)
    }

    private fun setKeyVisible(visible: Boolean) {
        if (visible == keyVisible) return
        keyVisible = visible
        val selection = keyInput.selectionEnd
        keyInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            if (visible) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
        // Password input types switch to monospace; keep the field's face stable.
        keyInput.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        if (selection >= 0) keyInput.setSelection(selection.coerceAtMost(keyInput.length()))
        keyToggle.setText(if (visible) R.string.irl_key_hide else R.string.irl_key_show)
    }

    private fun showFormError(message: String, field: IrlUi.Field?) {
        formError.text = message
        formError.visibility = View.VISIBLE
        when (field) {
            IrlUi.Field.SERVER -> serverInput.requestFocus()
            IrlUi.Field.KEY -> keyInput.requestFocus()
            null -> Unit
        }
    }

    private fun clearFormError() {
        formError.visibility = View.GONE
    }

    // ─────────────────────────── Go live / stop ───────────────────────────

    private fun requestGoLive() {
        if (IrlUi.isActive(hud.status)) return
        val settings = formSettings()
        val validation = IrlUi.validate(settings)
        val publishUrl = validation.publishUrl
        if (publishUrl == null) {
            showFormError(validation.message.orEmpty(), validation.field)
            return
        }
        clearFormError()
        runCatching { settingsStore.save(settings) }
        val missing = requiredPermissions().filterNot(::granted)
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), PERMISSIONS_REQUEST_CODE)
            return
        }
        if (api == null) {
            showFormError(getString(R.string.irl_service_unavailable), null)
            return
        }
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(getString(R.string.irl_confirm_go_live_title, settings.platform.label))
            .setMessage(IrlUi.confirmMessage(settings, publishUrl, granted(Manifest.permission.RECORD_AUDIO)))
            .setPositiveButton(R.string.irl_confirm_go_live) { _, _ -> startLive(settings) }
            .setNegativeButton(R.string.irl_cancel, null)
            .show()
    }

    private fun startLive(settings: LiveSettings) {
        val service = api ?: return showFormError(getString(R.string.irl_service_unavailable), null)
        hideKeyboard()
        // Service errors can echo server responses; never surface them raw here (they may hold the key).
        runCatching { service.goLive(settings) }
            .onFailure { showFormError(getString(R.string.irl_service_unavailable), null) }
        render(service.currentHud())
    }

    private fun confirmStop() {
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.irl_confirm_stop_title)
            .setMessage(R.string.irl_confirm_stop_message)
            .setPositiveButton(R.string.irl_confirm_stop) { _, _ -> api?.stopLive() }
            .setNegativeButton(R.string.irl_cancel, null)
            .show()
    }

    /**
     * Idle: return to OBS mode. Live: send the task to the background instead,
     * because MainActivity.onStart reopens the camera and the OBS listener,
     * which would take the camera from the running stream.
     */
    private fun leave() {
        if (dimmed) {
            setDimmed(false)
            return
        }
        if (IrlUi.isActive(hud.status)) {
            Toast.makeText(this, R.string.irl_continues_in_notification, Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        } else {
            finish()
        }
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun granted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    // ─────────────────────────── HUD ───────────────────────────

    private fun render(next: LiveHud) {
        val previous = hud
        hud = next
        val active = IrlUi.isActive(next.status)
        setupPanel.visibility = if (active) View.GONE else View.VISIBLE
        liveControls.visibility = if (active) View.VISIBLE else View.GONE
        statsRow.visibility = if (active) View.VISIBLE else View.GONE
        thermalBanner.visibility = if (next.thermalWarning) View.VISIBLE else View.GONE
        val error = next.status as? LiveStatus.Error
        if (error != null && previous.status != error) showFormError(IrlUi.statusPill(error, 0).text, null)

        statBitrate.text = IrlUi.sentVsTarget(next.sentBitrate, next.currentBitrate.takeIf { it > 0 } ?: next.targetBitrate)
        statFps.text = IrlUi.fps(next.fps)
        statDropped.text = IrlUi.dropped(next.droppedFrames)
        statNetwork.text = next.network
        statNetwork.visibility = if (next.network.isBlank()) View.GONE else View.VISIBLE

        renderMute(next.muted)
        renderLenses(next.lens)
        renderStatus()

        if (active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            if (dimmed) setDimmed(false)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        mainHandler.removeCallbacks(statusTicker)
        if (next.status is LiveStatus.Live) mainHandler.postDelayed(statusTicker, TICK_MS)
    }

    private fun renderStatus() {
        val status = hud.status
        statusPill.visibility = if (status == LiveStatus.Idle) View.GONE else View.VISIBLE
        val pill = IrlUi.statusPill(status, SystemClock.elapsedRealtime())
        statusText.text = pill.text
        val dot = when (pill.tone) {
            IrlUi.Tone.LIVE -> R.color.os_live_red
            IrlUi.Tone.PENDING -> R.color.os_warning
            IrlUi.Tone.ERROR -> R.color.os_destructive
            IrlUi.Tone.IDLE -> R.color.os_text_tertiary
        }
        statusDot.backgroundTintList = ColorStateList.valueOf(getColor(dot))
    }

    private fun renderMute(muted: Boolean) {
        val micAllowed = granted(Manifest.permission.RECORD_AUDIO)
        btnMute.isEnabled = micAllowed
        btnMute.alpha = if (micAllowed) 1f else 0.4f
        btnMute.setText(
            when {
                !micAllowed -> R.string.irl_no_mic
                muted -> R.string.irl_muted
                else -> R.string.irl_mute
            },
        )
        val highlight = micAllowed && muted
        btnMute.setBackgroundResource(if (highlight) R.drawable.bg_minimal_pill_active else R.drawable.bg_minimal_pill)
        btnMute.setTextColor(getColor(if (highlight) R.color.os_black else R.color.os_text_primary))
    }

    private fun renderLenses(selected: CameraLens?) {
        val lenses = runCatching { api?.availableLenses() }.getOrNull().orEmpty()
        if (lenses == shownLenses && selected == shownLens) return
        shownLenses = lenses
        shownLens = selected
        lensRow.removeAllViews()
        lensRow.visibility = if (lenses.size > 1) View.VISIBLE else View.GONE
        lenses.forEach { lens ->
            lensRow.addView(pill(lens.shortLabel, lens == selected) {
                if (lens != hud.lens) api?.switchLens(lens)
            }.apply { contentDescription = lens.displayName })
        }
    }

    private fun attachPreviewIfReady() {
        val service = api ?: return
        val surface = preview.holder.surface
        if (surface != null && surface.isValid && granted(Manifest.permission.CAMERA)) service.attachPreview(surface)
    }

    // ─────────────────────────── Dim (screen off while live) ───────────────────────────

    private fun setDimmed(on: Boolean) {
        if (on == dimmed) return
        dimmed = on
        val params = window.attributes
        if (on) {
            originalBrightness = params.screenBrightness
            params.screenBrightness = 0.001f
        } else {
            params.screenBrightness = if (originalBrightness >= 0) originalBrightness else -1f
        }
        window.attributes = params
        screenOffOverlay.visibility = if (on) View.VISIBLE else View.GONE
    }

    // ─────────────────────────── Layout helpers ───────────────────────────

    private fun pill(label: String, selected: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(16), 0, dp(16), 0)
        minWidth = dp(48)
        setBackgroundResource(R.drawable.bg_lens_selector)
        isSelected = selected
        setTextColor(getColor(if (selected) R.color.os_black else R.color.os_text_primary))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply {
            marginEnd = dp(8)
        }
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun hideKeyboard() {
        currentFocus?.clearFocus()
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    /** Fits a 16:9 portrait box like OBS mode, so the framing matches what is streamed. */
    private fun fitPreview() {
        val width = previewContainer.width
        val height = previewContainer.height
        if (width == 0 || height == 0) return
        val aspect = 16f / 9f
        val (targetWidth, targetHeight) = if (width * aspect > height) {
            (height / aspect).toInt() to height
        } else {
            width to (width * aspect).toInt()
        }
        val lp = preview.layoutParams as FrameLayout.LayoutParams
        if (lp.width == targetWidth && lp.height == targetHeight) return
        lp.width = targetWidth
        lp.height = targetHeight
        lp.gravity = Gravity.CENTER
        preview.post { preview.layoutParams = lp }
    }

    private fun setupInsets() {
        val topBase = topBar.paddingTop
        val bottomBase = bottomPanel.paddingBottom
        window.decorView.setOnApplyWindowInsetsListener { view, insets ->
            val (top, bottom) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                bars.top to bars.bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            }
            topBar.setPadding(topBar.paddingLeft, maxOf(topBase, top + dp(16)), topBar.paddingRight, topBar.paddingBottom)
            bottomPanel.setPadding(bottomPanel.paddingLeft, bottomPanel.paddingTop, bottomPanel.paddingRight, bottomBase + bottom)
            view.onApplyWindowInsets(insets)
        }
        window.decorView.requestApplyInsets()
    }

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 300
        private const val TICK_MS = 1_000L
    }
}
