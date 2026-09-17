package app.fediferry.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Access tokens, keyed by account id. Kept out of Room so a database export or
 * backup never carries credentials.
 *
 * Nothing here is ever logged.
 *
 * androidx.security.crypto is deprecated upstream with no drop-in replacement;
 * the alternative is hand-rolling Keystore-backed encryption, which is a worse
 * trade for a single string per account. Revisit if it stops resolving.
 */
class TokenStore(context: Context) {

    @Suppress("DEPRECATION")
    private val prefs: SharedPreferences by lazy {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "fediferry_tokens",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun put(accountId: String, token: String) {
        prefs.edit { putString(accountId, token) }
    }

    fun get(accountId: String): String? = prefs.getString(accountId, null)

    fun remove(accountId: String) {
        prefs.edit { remove(accountId) }
    }
}
