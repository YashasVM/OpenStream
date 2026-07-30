package dev.openstream.app.control

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

class PairingTokenStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    @Synchronized
    fun currentPairingCode(): String {
        val existing = preferences.getString(KEY_PAIRING_CODE, null)
        if (!existing.isNullOrBlank()) return existing
        return newPairingCode().also { code ->
            preferences.edit().putString(KEY_PAIRING_CODE, code).apply()
        }
    }

    @Synchronized
    fun pair(sourceInstanceId: String, sourceName: String, suppliedCode: String?): PairingResult {
        if (sourceInstanceId.isBlank()) return PairingResult.Invalid("missing sourceInstanceId")
        if (sourceName.isBlank()) return PairingResult.Invalid("missing sourceName")
        val code = suppliedCode?.trim().orEmpty()
        if (!constantTimeEquals(code, currentPairingCode())) return PairingResult.CodeRejected

        val token = ByteArray(TOKEN_BYTES).also(random::nextBytes).let { bytes ->
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }
        preferences.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_SOURCE_ID, sourceInstanceId.trim())
            .putString(KEY_SOURCE_NAME, sourceName.trim())
            .putString(KEY_PAIRING_CODE, newPairingCode())
            .apply()
        return PairingResult.Paired(token)
    }

    fun validateBearer(value: String?): Boolean {
        val expected = preferences.getString(KEY_TOKEN, null) ?: return false
        val supplied = value?.trim()?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)
            ?.trim()
            ?: return false
        return constantTimeEquals(supplied, expected)
    }

    fun hasPairedAdministrator(): Boolean = !preferences.getString(KEY_TOKEN, null).isNullOrBlank()

    fun streamPassphrase(): String? = preferences.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun pairedSourceName(): String? = preferences.getString(KEY_SOURCE_NAME, null)

    private fun newPairingCode(): String = (random.nextInt(900_000) + 100_000).toString()

    private fun constantTimeEquals(a: String, b: String): Boolean = MessageDigest.isEqual(
        a.toByteArray(Charsets.UTF_8),
        b.toByteArray(Charsets.UTF_8),
    )

    sealed interface PairingResult {
        data class Paired(val token: String) : PairingResult
        data class Invalid(val reason: String) : PairingResult
        data object CodeRejected : PairingResult
    }

    companion object {
        private const val PREFS_NAME = "openstream_control_auth"
        private const val KEY_PAIRING_CODE = "pairing_code"
        private const val KEY_TOKEN = "bearer_token"
        private const val KEY_SOURCE_ID = "source_instance_id"
        private const val KEY_SOURCE_NAME = "source_name"
        private const val TOKEN_BYTES = 32
        private const val BEARER_PREFIX = "Bearer "
    }
}
