package dev.openstream.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
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
    private lateinit var btnBack: TextView
    private lateinit var btnCheckUpdates: TextView
    private lateinit var versionInfo: TextView
    private lateinit var appUpdater: AppUpdater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        inputObsHost = findViewById(R.id.settingsObsHost)
        inputObsPort = findViewById(R.id.settingsObsPort)
        inputLatency = findViewById(R.id.settingsLatency)
        inputListeningPort = findViewById(R.id.settingsListeningPort)
        btnSave = findViewById(R.id.btnSaveSettings)
        btnBack = findViewById(R.id.btnBackSettings)
        btnCheckUpdates = findViewById(R.id.btnCheckUpdates)
        versionInfo = findViewById(R.id.settingsVersionInfo)

        appUpdater = AppUpdater(this)
        appUpdater.register()

        loadSettings()
        showVersionInfo()

        btnSave.setOnClickListener { saveSettings() }
        btnBack.setOnClickListener { finish() }
        btnCheckUpdates.setOnClickListener {
            appUpdater.checkForUpdates(showAlreadyCurrent = true)
        }
    }

    override fun onResume() {
        super.onResume()
        appUpdater.resumePendingInstallIfAllowed()
    }

    override fun onDestroy() {
        appUpdater.unregister()
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

    private fun saveSettings() {
        val host = inputObsHost.text.toString().trim()
        val port = inputObsPort.text.toString().toIntOrNull() ?: ConnectionTarget.DEFAULT_PORT
        val latency = inputLatency.text.toString().toIntOrNull() ?: ConnectionTarget.DEFAULT_LATENCY_MS
        val listenPort = inputListeningPort.text.toString().toIntOrNull() ?: ConnectionTarget.DEFAULT_PORT

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_OBS_HOST, host)
            .putInt(KEY_OBS_PORT, port.coerceIn(1, 65535))
            .putInt(KEY_LATENCY, latency.coerceIn(20, 2000))
            .putInt(KEY_LISTENING_PORT, listenPort.coerceIn(1024, 65535))
            .apply()

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
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
        const val PREFS_NAME = "openstream_settings"
        const val KEY_OBS_HOST = "obs_host"
        const val KEY_OBS_PORT = "obs_port"
        const val KEY_LATENCY = "latency_ms"
        const val KEY_LISTENING_PORT = "listening_port"
    }
}
