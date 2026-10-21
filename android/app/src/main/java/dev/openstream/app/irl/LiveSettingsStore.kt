package dev.openstream.app.irl

import android.content.Context
import android.content.SharedPreferences

/** IRL preferences are separate from the OBS settings. */
class LiveSettingsStore internal constructor(
    private val preferences: SharedPreferences,
    cipher: StreamKeyCipher,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences("openstream_irl", Context.MODE_PRIVATE),
        AndroidStreamKeyCipher(),
    )

    private val keys = StreamKeyStore(preferences, cipher)

    fun load(): LiveSettings = load(
        LivePlatform.entries.firstOrNull { it.name == preferences.getString("platform", null) }
            ?: LivePlatform.TWITCH,
    )

    fun load(platform: LivePlatform): LiveSettings = LiveSettings(
        platform = platform,
        server = if (platform.serverHoldsSecrets) {
            keys.loadSecret("server.${platform.name}") ?: platform.server
        } else {
            preferences.getString("server.${platform.name}", platform.server) ?: platform.server
        },
        key = keys.load(platform),
        quality = LiveQuality.entries.firstOrNull { it.name == preferences.getString("quality", null) }
            ?: LiveQuality.P720,
    )

    fun save(settings: LiveSettings) {
        val editor = preferences.edit()
        val serverName = "server.${settings.platform.name}"
        keys.write(editor, settings.platform, settings.key)
        if (settings.platform.serverHoldsSecrets) {
            keys.writeSecret(editor, serverName, settings.server)
        } else {
            editor.putString(serverName, settings.server)
        }
        editor.putString("platform", settings.platform.name)
            .putString("quality", settings.quality.name)
            .apply()
    }

    // Custom SRT URLs carry passphrase/streamid and custom RTMP URLs may embed the key.
    private val LivePlatform.serverHoldsSecrets: Boolean
        get() = this == LivePlatform.CUSTOM_RTMP || this == LivePlatform.CUSTOM_SRT
}
