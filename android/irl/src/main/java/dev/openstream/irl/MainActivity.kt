package dev.openstream.irl

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import dev.openstream.irl.camera.Camera2Controller
import dev.openstream.irl.camera.CameraLens
import java.io.File

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var camera: Camera2Controller
    private lateinit var queue: DiskQueue
    private lateinit var capture: Capture
    private lateinit var uploader: RelayUploader
    private lateinit var state: TextView
    private lateinit var queueText: TextView
    private lateinit var pairingInput: EditText
    private lateinit var start: Button
    private lateinit var bitrateSlider: SeekBar
    private var pairing: Pairing? = null
    private var lens = CameraLens.Back
    private var foreground = false
    private var surfaceReady = false
    private var starting = false
    private var lifecycleGeneration = 0L
    private var recording = false
    private var stopping = false
    private var pendingPermissionStart = false
    private var errorVisible = false
    private var destroyed = false
    private val refresh = object : Runnable {
        override fun run() {
            if (destroyed) return
            runCatching { queueText.text = queue.pendingDescription() }
            ui.postDelayed(this, 1000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = Color.rgb(14, 17, 22)
        window.navigationBarColor = Color.rgb(14, 17, 22)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(30), dp(20), dp(24))
            setBackgroundColor(Color.rgb(14, 17, 22))
            fitsSystemWindows = true
        }
        setContentView(ScrollView(this).apply { addView(page) })
        fun text(value: String, size: Float = 15f) = TextView(this).apply {
            this.text = value; textSize = size; setTextColor(Color.rgb(222, 229, 238))
            setPadding(0, dp(7), 0, dp(7)); page.addView(this)
        }
        text("OpenStream-IRL", 28f)
        text("Camera → phone queue → HTTPS relay → OBS")
        val preview = SurfaceView(this).apply {
            contentDescription = "Camera preview"
            holder.setFixedSize(1280, 720)
        }
        page.addView(preview, LinearLayout.LayoutParams(-1, dp(210)))
        camera = Camera2Controller(this, { preview.holder.surface }, { lens })
        state = text("Pair with your relay to begin.")
        queueText = text("0 queued")
        text("Keep this screen open while filming. Leaving the app stops capture; saved segments keep uploading while the process runs.", 13f)
        pairingInput = EditText(this).apply {
            hint = "Paste openstreamirl://pair link"
            setHintTextColor(Color.GRAY); setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            maxLines = 3; contentDescription = "Private relay pairing link"
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            isSaveEnabled = false
        }
        page.addView(pairingInput)
        button(page, "Save pairing") { savePairing(pairingInput.text.toString()) }
        val bitrateLabel = text("Video bitrate · 4 Mbps")
        bitrateSlider = SeekBar(this).apply {
            min = 1; max = 12; progress = 4
            contentDescription = "Video bitrate in Mbps"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    bitrateLabel.text = "Video bitrate · $progress Mbps"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        page.addView(bitrateSlider)
        text("H.264 720p30 + AAC · keyframes every second", 13f)
        start = button(page, "Start capture") { if (recording || starting) stopCapture() else requestStart() }
        val controls = LinearLayout(this).also { page.addView(it) }
        button(controls, "Front / back") {
            if (!starting && !stopping) {
                lens = if (lens.isBackFacing) CameraLens.Front else CameraLens.Back
                camera.switchLens(lens)
            }
        }
        button(controls, "Torch") { camera.setTorch(!camera.torchEnabled) }
        text("Zoom")
        page.addView(SeekBar(this).apply {
            max = 100; contentDescription = "Camera zoom"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val range = camera.zoomRange
                        camera.setZoom(range.start + (range.endInclusive - range.start) * progress / 100f)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
        button(page, "Retry pending uploads") { errorVisible = false; uploader.retry(); show("Retrying saved segments…") }
        queue = DiskQueue(File(filesDir, "segments"))
        capture = Capture(this, camera, queue, { message -> onUi { show(message) } }, { message -> onUi {
            showError(message); stopCapture()
        } })
        uploader = RelayUploader(queue, { message -> onUi { if (!errorVisible) show(message) } }, { message -> onUi {
            showError(message); stopCapture()
        } })
        val preferences = getSharedPreferences("pairing", MODE_PRIVATE)
        pairing = runCatching {
            Pairing(preferences.getString("relay", "")!!, preferences.getString("session", "")!!, preferences.getString("token", "")!!)
        }.getOrNull()
        pairing?.let { show("Paired. Ready to capture.") }
        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; maybePreview() }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; stopCapture() }
        })
        acceptIntent(intent)
        ui.post(refresh)
    }
    private fun button(parent: LinearLayout, title: String, action: () -> Unit): Button = Button(this).apply {
        text = title; isAllCaps = false; contentDescription = title
        setTextColor(Color.WHITE); backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(39, 53, 70))
        parent.addView(this); setOnClickListener { action() }
    }
    private fun savePairing(text: String) {
        if (recording || starting || stopping || queue.run() != null) {
            showError("Finish the current run and pending uploads before changing pairing."); return
        }
        try {
            val next = Pairing.parse(text)
            getSharedPreferences("pairing", MODE_PRIVATE).edit().putString("relay", next.relay)
                .putString("session", next.session).putString("token", next.token).apply()
            pairing = next; pairingInput.text.clear(); errorVisible = false
            show("Pairing saved privately. Ready to capture.")
        } catch (_: Exception) { showError("Invalid pairing link. Use an HTTPS relay origin, UUID session, and 256-bit publish token.") }
    }
    private fun acceptIntent(value: Intent?) {
        val link = value?.dataString ?: return
        value.data = null
        savePairing(link)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptIntent(intent) }
    private fun granted() = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        .all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private fun requestStart() {
        if (stopping) return
        if (pairing == null) { showError("Save a pairing link first."); return }
        if (!surfaceReady || !foreground) { showError("Wait for the camera preview."); return }
        if (!granted()) {
            pendingPermissionStart = true
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        startCapture()
    }
    private fun startCapture() {
        val paired = pairing ?: return
        if (starting || recording || stopping || !foreground) return
        if (queue.run() != null) { showError("Wait for pending uploads to finish before starting a new run."); return }
        errorVisible = false; starting = true; start.text = "Cancel start"; bitrateSlider.isEnabled = false
        show("Opening relay run…")
        val generation = ++lifecycleGeneration
        uploader.start(paired, { onUi {
            if (generation != lifecycleGeneration || !foreground || !starting) { uploader.finishCancelledStart(); return@onUi }
            capture.start(bitrateSlider.progress * 1_000_000) { onUi {
                if (generation != lifecycleGeneration || !foreground || !starting) { stopCapture(); return@onUi }
                starting = false; recording = true; start.text = "Stop capture"
                show("Capturing. Saved segments upload in order.")
            } }
        } }, { message -> onUi { if (generation == lifecycleGeneration) { starting = false; resetButton(); showError(message) } } })
    }
    private fun stopCapture() {
        if (!recording && !starting) return
        pendingPermissionStart = false
        lifecycleGeneration++
        starting = false; recording = false; stopping = true
        start.isEnabled = false; start.text = "Stopping…"
        capture.stop { onUi { stopping = false; resetButton(); if (!errorVisible) show("Capture stopped. Draining saved segments…") } }
    }
    private fun resetButton() { start.text = "Start capture"; start.isEnabled = true; bitrateSlider.isEnabled = true }
    private fun show(message: String) { state.text = message }
    private fun showError(message: String) { errorVisible = true; state.text = message }
    private fun onUi(action: () -> Unit) { ui.post { if (!destroyed) action() } }
    private fun maybePreview() { if (foreground && surfaceReady && granted()) camera.startPreview() }
    override fun onResume() {
        super.onResume(); foreground = true; maybePreview()
        if (pendingPermissionStart && granted()) { pendingPermissionStart = false; requestStart() }
    }
    override fun onPause() {
        foreground = false; stopCapture()
        capture.stop { camera.stop() }
        super.onPause()
    }
    override fun onDestroy() {
        destroyed = true; ui.removeCallbacksAndMessages(null)
        capture.close(); uploader.close()
        super.onDestroy()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            val startAfterGrant = pendingPermissionStart
            if (granted()) {
                maybePreview()
                if (startAfterGrant && foreground) { pendingPermissionStart = false; requestStart() }
            } else { pendingPermissionStart = false; showError("Camera and microphone permissions are required. Tap Start to try again, or grant them in Android settings.") }
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
