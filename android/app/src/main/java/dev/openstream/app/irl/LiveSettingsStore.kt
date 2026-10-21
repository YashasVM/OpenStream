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
        server = preferences.getString("server.${platform.name}", platform.server) ?: platform.server,
        key = keys.load(platform),
        quality = LiveQuality.entries.firstOrNull { it.name == preferences.getString("quality", null) }
            ?: LiveQuality.P720,
    )

    fun save(settings: LiveSettings) {
        val editor = preferences.edit()
        keys.write(editor, settings.platform, settings.key)
        editor.putString("platform", settings.platform.name)
            .putString("server.${settings.platform.name}", settings.server)
            .putString("quality", settings.quality.name)
            .apply()
    }
}
