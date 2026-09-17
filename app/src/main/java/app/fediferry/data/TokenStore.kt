/*
 * FediFerry — share a meme screenshot straight to Mastodon.
 * Copyright (C) 2026 Jasper Ramthun
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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
