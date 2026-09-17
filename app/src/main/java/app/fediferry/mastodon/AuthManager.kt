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
package app.fediferry.mastodon

import android.content.Context
import androidx.core.net.toUri
import androidx.browser.customtabs.CustomTabsIntent
import app.fediferry.BuildConfig
import app.fediferry.data.TokenStore
import app.fediferry.data.db.AccountDao
import app.fediferry.data.model.Account
import app.fediferry.data.model.InstanceApp
import java.security.SecureRandom

/**
 * OAuth 2 authorization-code flow against an arbitrary instance.
 *
 * The app registers itself per instance on first connect (`POST /api/v1/apps`),
 * caches the client credentials, and sends the user to a Custom Tab. The
 * redirect comes back to [OAuthRedirectActivity] via the app scheme.
 */
class AuthManager(
    private val context: Context,
    private val client: MastodonClient,
    private val accounts: AccountDao,
    private val tokens: TokenStore,
) {

    /** Survives only for the duration of one authorisation; not persisted. */
    private data class Pending(val instance: String, val state: String)

    @Volatile private var pending: Pending? = null

    suspend fun beginAuthorization(rawInstance: String) {
        val instance = normalise(rawInstance)
        val app = accounts.appFor(instance) ?: registerApp(instance)
        val state = randomState()
        pending = Pending(instance, state)

        val url = client.authorizeUrl(
            instance = instance,
            clientId = app.clientId,
            redirectUri = REDIRECT_URI,
            scopes = SCOPES,
            state = state,
        )
        CustomTabsIntent.Builder().build().apply {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }.launchUrl(context, url.toUri())
    }

    /**
     * Completes the flow. Returns the connected account, or fails if the
     * redirect did not match the authorisation we started.
     */
    suspend fun completeAuthorization(code: String, state: String): Result<Account> =
        runCatching {
            val session = pending
                ?: throw MastodonException("No sign-in is in progress")
            if (session.state != state) {
                throw MastodonException("Sign-in response did not match the request")
            }
            pending = null

            val app = accounts.appFor(session.instance)
                ?: throw MastodonException("Lost the client registration for ${session.instance}")

            val token = client.exchangeCode(
                instance = session.instance,
                clientId = app.clientId,
                clientSecret = app.clientSecret,
                redirectUri = REDIRECT_URI,
                code = code,
                scopes = SCOPES,
            )
            val me = client.verifyCredentials(session.instance, token.accessToken)

            val account = Account(
                id = "${session.instance}/${me.acct}",
                instance = session.instance,
                acct = me.acct,
                displayName = me.displayName.ifBlank { me.acct },
                avatarUrl = me.avatar,
                isDefault = accounts.all().isEmpty(),
            )
            accounts.upsert(account)
            tokens.put(account.id, token.accessToken)
            if (account.isDefault) accounts.setDefault(account.id)
            account
        }

    fun cancel() {
        pending = null
    }

    suspend fun disconnect(accountId: String) {
        accounts.delete(accountId)
        tokens.remove(accountId)
    }

    private suspend fun registerApp(instance: String): InstanceApp {
        val registration = client.registerApp(
            instance = instance,
            clientName = CLIENT_NAME,
            redirectUri = REDIRECT_URI,
            scopes = SCOPES,
            website = WEBSITE,
        )
        return InstanceApp(instance, registration.clientId, registration.clientSecret)
            .also { accounts.upsertApp(it) }
    }

    private fun normalise(raw: String): String = raw.trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .trimEnd('/')
        .substringBefore('/')
        .removePrefix("@")
        .substringAfterLast('@')
        .lowercase()

    private fun randomState(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val SCOPES = "write:statuses write:media read:accounts"
        const val CLIENT_NAME = "FediFerry"
        const val WEBSITE = "https://github.com/merloto9/fediferry"
        val REDIRECT_URI: String get() = "${BuildConfig.OAUTH_SCHEME}://oauth"
    }
}
