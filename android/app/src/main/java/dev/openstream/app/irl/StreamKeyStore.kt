package dev.openstream.app.irl

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface StreamKeyCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(encrypted: String): String
}

internal class StreamKeyStore(
    private val preferences: SharedPreferences,
    private val cipher: StreamKeyCipher,
) {
    fun load(platform: LivePlatform): String {
        val name = "key.${platform.name}"
        val encrypted = preferences.getString(name, null) ?: return ""
        return try {
            cipher.decrypt(encrypted)
        } catch (_: Exception) {
            // Backups can restore ciphertext without its device-bound Keystore key.
            preferences.edit().remove(name).apply()
            ""
        }
    }

    fun write(editor: SharedPreferences.Editor, platform: LivePlatform, key: String) {
        val name = "key.${platform.name}"
        if (key.isBlank()) editor.remove(name) else editor.putString(name, cipher.encrypt(key))
    }
}

internal class AndroidStreamKeyCipher : StreamKeyCipher {
    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return "${Base64.getEncoder().encodeToString(cipher.iv)}:${Base64.getEncoder().encodeToString(ciphertext)}"
    }

    override fun decrypt(encrypted: String): String {
        val parts = encrypted.split(':', limit = 2)
        require(parts.size == 2)
        val iv = Base64.getDecoder().decode(parts[0])
        require(iv.size == 12)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, requireNotNull(existingKey()), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), Charsets.UTF_8)
    }

    private fun existingKey(): SecretKey? = KeyStore.getInstance("AndroidKeyStore").run {
        load(null)
        getKey(ALIAS, null) as SecretKey?
    }

    private fun createKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        generateKey()
    }

    private companion object {
        const val ALIAS = "openstream_irl_stream_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
