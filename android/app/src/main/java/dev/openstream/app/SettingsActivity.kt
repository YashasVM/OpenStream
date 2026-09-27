package dev.openstream.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.openstream.app.stream.ConnectionTarget
import dev.openstream.app.stream.VideoQuality

class SettingsActivity : Activity() {
    private lateinit var qualityGroup: RadioGroup
    private lateinit var microphoneSwitch: Switch
    private lateinit var keepAwakeSwitch: Switch
    private lateinit var listeningPort: EditText
    private lateinit var versionInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        qualityGroup = findViewById(R.id.settingsQuality)
        microphoneSwitch = findViewById(R.id.settingsMicrophone)
        keepAwakeSwitch = findViewById(R.id.settingsKeepAwake)
        listeningPort = findViewById(R.id.settingsListeningPort)
        versionInfo = findViewById(R.id.settingsVersionInfo)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        qualityGroup.check(when (VideoQuality.fromPreference(prefs.getString(KEY_VIDEO_QUALITY, null))) {
            VideoQuality.DataSaver -> R.id.qualityDataSaver
            VideoQuality.Balanced -> R.id.qualityBalanced
            VideoQuality.High -> R.id.qualityHigh
        })
        microphoneSwitch.isChecked = prefs.getBoolean(KEY_MICROPHONE_ENABLED, true)
        keepAwakeSwitch.isChecked = prefs.getBoolean(KEY_KEEP_AWAKE, false)
        listeningPort.setText(prefs.getInt(KEY_LISTENING_PORT, ConnectionTarget.DEFAULT_PORT).toString())
        showVersionInfo()

        findViewById<TextView>(R.id.btnSaveSettings).setOnClickListener { saveSettings() }
        findViewById<TextView>(R.id.btnBackSettings).setOnClickListener { finish() }
    }

    private fun saveSettings() {
        val port = listeningPort.text.toString().trim().toIntOrNull()
        if (port == null || port !in 1024..65535) {
            listeningPort.error = "Port must be between 1024 and 65535"
            listeningPort.requestFocus()
            return
        }
        val quality = when (qualityGroup.checkedRadioButtonId) {
            R.id.qualityDataSaver -> VideoQuality.DataSaver
            R.id.qualityBalanced -> VideoQuality.Balanced
            else -> VideoQuality.High
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_VIDEO_QUALITY, quality.name)
            .putBoolean(KEY_MICROPHONE_ENABLED, microphoneSwitch.isChecked)
            .putBoolean(KEY_KEEP_AWAKE, keepAwakeSwitch.isChecked)
            .putInt(KEY_LISTENING_PORT, port)
            .apply()
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SETTINGS_CHANGED, true))
        finish()
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
        const val KEY_LISTENING_PORT = "listening_port"
        const val KEY_VIDEO_QUALITY = "video_quality"
        const val KEY_MICROPHONE_ENABLED = "microphone_enabled"
        const val KEY_KEEP_AWAKE = "keep_awake"
        const val EXTRA_SETTINGS_CHANGED = "settings_changed"
    }
}
