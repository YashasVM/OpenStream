package dev.openstream.app.irl

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class LiveSettingsStoreTest {
    private val preferences = MemoryPreferences()
    private val cipher = FakeStreamKeyCipher()
    private val store = LiveSettingsStore(preferences, cipher)

    @Test
    fun emptyPreferencesUseDefaultsAndPlatformPresets() {
        assertEquals(LiveSettings(), store.load())
        LivePlatform.entries.forEach { platform ->
            assertEquals(LiveSettings(platform, platform.server), store.load(platform))
        }
    }

    @Test
    fun settingsSurviveRecreatingTheStoreWithoutPersistingPlaintextKeys() {
        val settings = LiveSettings(LivePlatform.YOUTUBE, "rtmps://override.example/live", "secret-key", LiveQuality.P1080)
        store.save(settings)

        assertEquals(settings, LiveSettingsStore(preferences, cipher).load())
        assertFalse(preferences.all.values.any { it.toString().contains("secret-key") })
    }

    @Test
    fun switchingPlatformsRestoresEachServerAndKeyAndTheLastQuality() {
        val twitch = LiveSettings(key = "twitch-key", server = "rtmps://twitch.example/app")
        val youtube = LiveSettings(LivePlatform.YOUTUBE, "rtmps://youtube.example/live", "youtube-key", LiveQuality.P1080)
        store.save(twitch)
        store.save(youtube)

        assertEquals(youtube, store.load())
        assertEquals(twitch.copy(quality = LiveQuality.P1080), store.load(LivePlatform.TWITCH))
        store.save(store.load(LivePlatform.TWITCH))
        assertEquals(LivePlatform.TWITCH, store.load().platform)
        assertEquals(youtube, store.load(LivePlatform.YOUTUBE))
    }

    @Test
    fun blankKeyClearsOnlyThatPlatformsEncryptedKey() {
        store.save(LiveSettings(key = "twitch-key"))
        store.save(LiveSettings(LivePlatform.KICK, "rtmps://kick.example/app", "kick-key"))
        store.save(store.load(LivePlatform.TWITCH).copy(key = "  "))

        assertEquals("", store.load().key)
        assertFalse(preferences.contains("key.TWITCH"))
        assertEquals("kick-key", store.load(LivePlatform.KICK).key)
    }

    @Test
    fun unknownEnumsFallBackWithoutDiscardingOtherSettings() {
        preferences.edit().putString("platform", "FUTURE_PLATFORM")
            .putString("quality", "FUTURE_QUALITY")
            .putString("server.TWITCH", "rtmps://override.example/app").apply()

        assertEquals(LiveSettings(server = "rtmps://override.example/app"), store.load())
    }

    @Test
    fun encryptionFailureDoesNotPartiallySaveSettingsOrPlaintext() {
        val original = LiveSettings(key = "existing-key")
        store.save(original)
        cipher.failEncryption = true
        assertThrows(IllegalStateException::class.java) {
            store.save(LiveSettings(LivePlatform.KICK, "rtmps://kick.example/app", "new-secret", LiveQuality.P480))
        }

        assertEquals(original, store.load())
        assertFalse(preferences.all.values.any { it.toString().contains("new-secret") })
    }

    @Test
    fun invalidatedKeyLoadsBlankAndPreservesNonSecretSettings() {
        val settings = LiveSettings(key = "secret-key", quality = LiveQuality.P720_60)
        store.save(settings)
        cipher.failDecryption = true

        assertEquals(settings.copy(key = ""), store.load())
        assertFalse(preferences.contains("key.TWITCH"))
    }
}

internal class FakeStreamKeyCipher : StreamKeyCipher {
    private val plaintexts = mutableMapOf<String, String>()
    var failEncryption = false
    var failDecryption = false

    override fun encrypt(plaintext: String): String {
        check(!failEncryption)
        val encrypted = "encrypted-${plaintexts.size}"
        plaintexts[encrypted] = plaintext
        return encrypted
    }

    override fun decrypt(encrypted: String): String {
        check(!failDecryption)
        return requireNotNull(plaintexts[encrypted])
    }
}

internal class MemoryPreferences : SharedPreferences {
    private val values = mutableMapOf<String, String>()
    override fun getAll(): Map<String, *> = values.toMap()
    override fun getString(key: String, defValue: String?): String? = values[key] ?: defValue
    override fun contains(key: String): Boolean = key in values
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val updates = mutableMapOf<String, String?>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { updates[key] = value }
        override fun remove(key: String) = apply { updates[key] = null }
        override fun clear() = apply { clear = true }
        override fun apply() { commit() }
        override fun commit(): Boolean {
            if (clear) values.clear()
            updates.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            return true
        }
        override fun putStringSet(key: String?, values: Set<String>?) = error("Unused")
        override fun putInt(key: String?, value: Int) = error("Unused")
        override fun putLong(key: String?, value: Long) = error("Unused")
        override fun putFloat(key: String?, value: Float) = error("Unused")
        override fun putBoolean(key: String?, value: Boolean) = error("Unused")
    }
    override fun getStringSet(key: String?, defValues: Set<String>?) = error("Unused")
    override fun getInt(key: String?, defValue: Int) = error("Unused")
    override fun getLong(key: String?, defValue: Long) = error("Unused")
    override fun getFloat(key: String?, defValue: Float) = error("Unused")
    override fun getBoolean(key: String?, defValue: Boolean) = error("Unused")
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
}
