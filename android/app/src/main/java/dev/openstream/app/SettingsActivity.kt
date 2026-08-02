package dev.openstream.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import dev.openstream.app.stream.ConnectionTarget
import dev.openstream.app.update.AppUpdater

class SettingsActivity : Activity() {

    private lateinit var inputObsHost: EditText
    private lateinit var inputObsPort: EditText
    private lateinit var inputLatency: EditText
    private lateinit var inputListeningPort: EditText
    private lateinit var btnSave: TextView
    private lateinit var btnSaveAndConnect: TextView
    private lateinit var btnBack: TextView
    private lateinit var appUpdater: AppUpdater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        inputObsHost = findViewById(R.id.settingsObsHost)
        inputObsPort = findViewById(R.id.settingsObsPort)
        inputLatency = findViewById(R.id.settingsLatency)
        inputListeningPort = findViewById(R.id.settingsListeningPort)
        btnSave = findViewById(R.id.btnSaveSettings)
        btnSaveAndConnect = findViewById(R.id.btnSaveAndConnect)
        btnBack = findViewById(R.id.btnBackSettings)

        appUpdater = AppUpdater(this)
        appUpdater.register()

        loadSettings()

        btnSave.setOnClickListener { saveSettings(connectAfterSave = false) }
        btnSaveAndConnect.setOnClickListener { saveSettings(connectAfterSave = true) }
        btnBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        appUpdater.resumePendingInstallIfAllowed()
    }

    override fun onDestroy() {
        appUpdater.dispose()
        super.onDestroy()
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
    }

    private fun saveSettings(connectAfterSave: Boolean) {
        clearValidationErrors()
        val host = inputObsHost.text.toString().trim()
        if (!SettingsValidator.isValidHost(host, required = connectAfterSave)) {
            inputObsHost.error = getString(R.string.error_invalid_host)
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

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_OBS_HOST, host)
            .putInt(KEY_OBS_PORT, port)
            .putInt(KEY_LATENCY, latency)
            .putInt(KEY_LISTENING_PORT, listenPort)
            .apply()

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
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
        if (raw.isBlank()) return defaultValue
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

    companion object {
        const val PREFS_NAME = "openstream_settings"
        const val KEY_OBS_HOST = "obs_host"
        const val KEY_OBS_PORT = "obs_port"
        const val KEY_LATENCY = "latency_ms"
        const val KEY_LISTENING_PORT = "listening_port"
        const val EXTRA_CONNECT_AFTER_SAVE = "connect_after_save"
    }
}
