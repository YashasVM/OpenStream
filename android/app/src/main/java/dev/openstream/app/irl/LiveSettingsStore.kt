package dev.openstream.app.irl

import android.content.Context

/** Persists IRL settings; the stream key is Keystore-encrypted. Stub; owned by the keystore workstream. */
class LiveSettingsStore(context: Context) {
    fun load(): LiveSettings = LiveSettings()
    fun save(settings: LiveSettings) {}
}
