package dev.openstream.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.openstream.app.network.HighQualityCapabilities
import dev.openstream.app.stream.ConnectionTarget
import dev.openstream.app.stream.VideoQuality

class SettingsActivity : Activity() {

    private lateinit var inputObsHost: EditText
    private lateinit var inputObsPort: EditText
    private lateinit var inputLatency: EditText
    private lateinit var inputListeningPort: EditText
    private lateinit var qualityGroup: RadioGroup
    private lateinit var adaptiveSwitch: Switch
    private lateinit var btnSave: TextView
    private lateinit var btnSaveAndConnect: TextView
    private lateinit var btnBack: TextView
    private lateinit var versionInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        inputObsHost = findViewById(R.id.settingsObsHost)
        inputObsPort = findViewById(R.id.settingsObsPort)
        inputLatency = findViewById(R.id.settingsLatency)
        inputListeningPort = findViewById(R.id.settingsListeningPort)
        qualityGroup = findViewById(R.id.settingsQuality)
        adaptiveSwitch = findViewById(R.id.settingsAdaptive)
        btnSave = findViewById(R.id.btnSaveSettings)
        btnSaveAndConnect = findViewById(R.id.btnSaveAndConnect)
        btnBack = findViewById(R.id.btnBackSettings)
        versionInfo = findViewById(R.id.settingsVersionInfo)

        loadSettings()
        showVersionInfo()
        val cameraId = intent.getStringExtra("cameraId")
        val modes = listOf(R.id.qualityUsb4k, R.id.qualityUsbHdr, R.id.qualityUsb4kHdr)
        modes.forEach { findViewById<RadioButton>(it).isEnabled = false }
        Thread({
            val supports = if (cameraId == null) modes.map {
                HighQualityCapabilities.Support(false, "Select a camera before checking this mode")
            } else listOf(
                HighQualityCapabilities.camera4kSdr(this, cameraId, 30, 35_000_000),
                HighQualityCapabilities.hdr(this, cameraId),
                HighQualityCapabilities.hdr(this, cameraId, 3840, 2160, 30, 40_000_000),
            )
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                modes.zip(supports).forEach { (id, support) ->
                    findViewById<RadioButton>(id).apply {
                        isEnabled = support.available
                        if (!support.available) {
                            text = "$text\n${support.reason}"
                            if (isChecked) qualityGroup.check(R.id.qualityBalanced)
                        }
                    }
                }
            }
        }, "OpenStreamModeCapabilities").start()

        btnSave.setOnClickListener { saveSettings(connectAfterSave = false) }
        btnSaveAndConnect.setOnClickListener { saveSettings(connectAfterSave = true) }
        btnBack.setOnClickListener { finish() }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        inputObsHost.setText(prefs.getString(KEY_OBS_HOST, ""))
        val port = prefs.getInt(KEY_OBS_PORT, ConnectionTarget.DEFAULT_PORT)
        if (port != ConnectionTarget.DEFAULT_PORT) inputObsPort.setText(port.toString())
        val latency = prefs.getInt(KEY_LATENCY, ConnectionTarget.DEFAULT_LATENCY_MS)
        if (latency != ConnectionTarget.DEFAULT_LATENCY_MS) inputLatency.setText(latency.toString())
        val listenPort = prefs.getInt(KEY_LISTENING_PORT, ConnectionTarget.DEFAULT_PORT)
        inputListeningPort.setText(listenPort.toString())
        qualityGroup.check(when (VideoQuality.fromPreference(prefs.getString(KEY_VIDEO_QUALITY, null))) {
            VideoQuality.DataSaver -> R.id.qualityDataSaver
            VideoQuality.Balanced -> R.id.qualityBalanced
            VideoQuality.High -> R.id.qualityHigh
            VideoQuality.Usb4k -> R.id.qualityUsb4k
            VideoQuality.UsbHdr -> R.id.qualityUsbHdr
            VideoQuality.Usb4kHdr -> R.id.qualityUsb4kHdr
        })
        adaptiveSwitch.isChecked = prefs.getBoolean(KEY_ADAPTIVE_BITRATE, true)
    }

    private fun saveSettings(connectAfterSave: Boolean) {
        clearValidationErrors()
        val host = inputObsHost.text.toString().trim()
        if (!SettingsValidator.isValidHost(host, required = connectAfterSave)) {
            inputObsHost.error = "Enter a valid OBS host or IP address"
            inputObsHost.requestFocus()
            return
        }
        val port = validatedNumber(
            input = inputObsPort,
            defaultValue = ConnectionTarget.DEFAULT_PORT,
            validRange = 1..65535,
            label = "OBS port",
        ) ?: return
        val latency = validatedNumber(
            input = inputLatency,
            defaultValue = ConnectionTarget.DEFAULT_LATENCY_MS,
            validRange = 80..200,
            label = "Latency",
        ) ?: return
        val listenPort = validatedNumber(
            input = inputListeningPort,
            defaultValue = ConnectionTarget.DEFAULT_PORT,
            validRange = 1024..65535,
            label = "Listening port",
        ) ?: return
        val quality = when (qualityGroup.checkedRadioButtonId) {
            R.id.qualityDataSaver -> VideoQuality.DataSaver
            R.id.qualityBalanced -> VideoQuality.Balanced
            R.id.qualityUsb4k -> VideoQuality.Usb4k
            R.id.qualityUsbHdr -> VideoQuality.UsbHdr
            R.id.qualityUsb4kHdr -> VideoQuality.Usb4kHdr
            else -> VideoQuality.High
        }

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_OBS_HOST, host)
            .putInt(KEY_OBS_PORT, port)
            .putInt(KEY_LATENCY, latency)
            .putInt(KEY_LISTENING_PORT, listenPort)
            .putString(KEY_VIDEO_QUALITY, quality.name)
            .putBoolean(KEY_ADAPTIVE_BITRATE, adaptiveSwitch.isChecked)
            .apply()

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_CONNECT_AFTER_SAVE, connectAfterSave),
        )
        finish()
    }

    private fun validatedNumber(
        input: EditText,
        defaultValue: Int,
        validRange: IntRange,
        label: String,
    ): Int? {
        val raw = input.text.toString().trim()
        val value = SettingsValidator.parseNumber(raw, defaultValue, validRange)
        if (value == null) {
            input.error = "$label must be between ${validRange.first} and ${validRange.last}"
            input.requestFocus()
            return null
        }
        return value
    }

    private fun clearValidationErrors() {
        inputObsHost.error = null
        inputObsPort.error = null
        inputLatency.error = null
        inputListeningPort.error = null
    }

    private fun showVersionInfo() {
        runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            versionInfo.text = "OpenStream v${info.versionName} (${code})"
        }
    }

    companion object {
        const val PREFS_NAME = "shin_settings"
        const val KEY_OBS_HOST = "obs_host"
        const val KEY_OBS_PORT = "obs_port"
        const val KEY_LATENCY = "latency_ms"
        const val KEY_LISTENING_PORT = "listening_port"
        const val KEY_VIDEO_QUALITY = "video_quality"
        const val KEY_ADAPTIVE_BITRATE = "adaptive_bitrate"
        const val KEY_MICROPHONE_ENABLED = "microphone_enabled"
        const val EXTRA_CONNECT_AFTER_SAVE = "connect_after_save"
        const val EXTRA_SETTINGS_CHANGED = "settings_changed"
    }
}
