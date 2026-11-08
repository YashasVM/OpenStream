package dev.openstream.app.irl

import org.junit.Assert.*
import org.junit.Test

class StreamKeyStoreTest {
    private val preferences = MemoryPreferences()
    private val cipher = FakeStreamKeyCipher()
    private val store = StreamKeyStore(preferences, cipher)

    private fun save(platform: LivePlatform, key: String) {
        val editor = preferences.edit()
        store.write(editor, platform, key)
        editor.apply()
    }

    @Test
    fun missingKeyReturnsBlankWithoutCallingCipher() {
        cipher.failDecryption = true
        assertEquals("", store.load(LivePlatform.TWITCH))
    }

    @Test
    fun everyPlatformHasAnIndependentEncryptedKey() {
        LivePlatform.entries.forEach { save(it, "secret-${it.name}") }
        LivePlatform.entries.forEach { platform ->
            assertEquals("secret-${platform.name}", store.load(platform))
            assertFalse(preferences.all.values.contains("secret-${platform.name}"))
        }
    }

    @Test
    fun restoredCiphertextWithoutItsKeyIsDroppedOnce() {
        save(LivePlatform.YOUTUBE, "youtube-key")
        val restored = StreamKeyStore(preferences, FakeStreamKeyCipher())

        assertEquals("", restored.load(LivePlatform.YOUTUBE))
        assertFalse(preferences.contains("key.YOUTUBE"))
        assertEquals("", restored.load(LivePlatform.YOUTUBE))
    }

    @Test
    fun corruptCiphertextDropsOnlyTheAffectedPlatform() {
        save(LivePlatform.TWITCH, "twitch-key")
        save(LivePlatform.YOUTUBE, "youtube-key")
        preferences.edit().putString("key.TWITCH", "broken-base64:ciphertext").apply()

        assertEquals("", store.load(LivePlatform.TWITCH))
        assertFalse(preferences.contains("key.TWITCH"))
        assertEquals("youtube-key", store.load(LivePlatform.YOUTUBE))
    }

    @Test
    fun clearingAKeyNeverRequiresWorkingCrypto() {
        save(LivePlatform.TWITCH, "secret")
        cipher.failEncryption = true
        cipher.failDecryption = true
        save(LivePlatform.TWITCH, "")

        assertFalse(preferences.contains("key.TWITCH"))
        assertEquals("", store.load(LivePlatform.TWITCH))
    }
}
