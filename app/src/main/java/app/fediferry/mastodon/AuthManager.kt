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
import androidx.core.content.edit
import app.fediferry.api.AuthorizeResponse
import app.fediferry.BuildConfig
import app.fediferry.R
import app.fediferry.data.TokenStore
import app.fediferry.data.db.AccountDao
import app.fediferry.data.model.Account
import app.fediferry.data.model.InstanceApp
import app.fediferry.log.DebugLog
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

    /** One authorisation under way. No secret in it, so it is kept in plain preferences. */
    private data class Pending(val instance: String, val state: String, val channelId: String? = null)

    /** A finished sign-in: the account, its id on the instance, and the channel it was for. */
    data class Connected(val account: Account, val remoteAccountId: String, val channelId: String?)

    /**
     * Kept on disk: Android may end the app while the browser is in front
     * (a password manager, a 2FA app), and the redirect then starts a new process.
     */
    private val prefs = context.getSharedPreferences("auth_pending", Context.MODE_PRIVATE)

    private var pending: Pending?
        get() {
            val instance = prefs.getString(KEY_INSTANCE, null) ?: return null
            val state = prefs.getString(KEY_STATE, null) ?: return null
            return Pending(instance, state, prefs.getString(KEY_CHANNEL, null))
        }
        set(value) {
            // Written at once: the browser opens next, and Android may end the app behind it.
            prefs.edit(commit = true) {
                if (value == null) clear()
                else putString(KEY_INSTANCE, value.instance).putString(KEY_STATE, value.state).putString(KEY_CHANNEL, value.channelId)
            }
        }

    /**
     * Registers with [rawInstance] if need be and returns where to sign in, for
     * the caller to open; the account goes to [channelId] when the browser is back.
     */
    suspend fun authorize(rawInstance: String, channelId: String?): AuthorizeResponse {
        val instance = normalise(rawInstance)
        val app = accounts.appFor(instance)?.takeIf { it.scopes == SCOPES } ?: registerApp(instance)
        val state = randomState()
        pending = Pending(instance, state, channelId)
        val url = client.authorizeUrl(
            instance = instance,
            clientId = app.clientId,
            redirectUri = REDIRECT_URI,
            scopes = SCOPES,
            state = state,
        )
        return AuthorizeResponse(url, state)
    }

    /**
     * Completes the flow. Returns the connected account, or fails if the
     * redirect did not match the authorisation we started.
     */
    suspend fun completeAuthorization(code: String, state: String): Result<Connected> =
        runCatching {
            val session = pending
                ?: throw MastodonException(context.getString(R.string.auth_no_sign_in))
            if (session.state != state) {
                throw MastodonException(context.getString(R.string.auth_state_mismatch))
            }
            pending = null

            val app = accounts.appFor(session.instance)
                ?: throw MastodonException(context.getString(R.string.auth_lost_registration, session.instance))

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
            Connected(account, me.id, session.channelId)
        }

    fun cancel() {
        pending = null
    }

    /**
     * Signs [accountId] out: its token is revoked at the instance, so a copy
     * of it elsewhere stops working too, then forgotten here. Revoking is best
     * effort — an unreachable instance does not keep the account here.
     */
    suspend fun disconnect(accountId: String) {
        val account = accounts.byId(accountId)
        val token = tokens.get(accountId)
        val app = account?.let { accounts.appFor(it.instance) }
        if (account != null && token != null && app != null) {
            runCatching { client.revoke(account.instance, app.clientId, app.clientSecret, token) }
                .onFailure { DebugLog.w(LOG, "Revoking a token failed: ${it.javaClass.simpleName}") }
        }
        forget(accountId)
    }

    /**
     * Forgets [accountId] on this phone without revoking its token: for an
     * account whose token now lives somewhere else, on the server.
     */
    suspend fun forget(accountId: String) {
        accounts.delete(accountId)
        tokens.remove(accountId)
    }

    /** The app this phone registered with [instance], for handing on with a token so it can be revoked later. */
    suspend fun registration(instance: String): InstanceApp? = accounts.appFor(normalise(instance))

    private suspend fun registerApp(instance: String): InstanceApp {
        val registration = client.registerApp(
            instance = instance,
            clientName = CLIENT_NAME,
            redirectUri = REDIRECT_URI,
            scopes = SCOPES,
            website = WEBSITE,
        )
        return InstanceApp(instance, registration.clientId, registration.clientSecret, scopes = SCOPES)
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
        /** `read:statuses` reads the posts Mastodon holds for later, for the Queue. */
        const val SCOPES = "write:statuses write:media read:accounts read:statuses"
        const val CLIENT_NAME = "FediFerry"
        const val WEBSITE = "https://github.com/merloto9/fediferry"
        private const val LOG = "auth"
        private const val KEY_INSTANCE = "instance"
        private const val KEY_STATE = "state"
        private const val KEY_CHANNEL = "channel"

        val REDIRECT_URI: String get() = "${BuildConfig.OAUTH_SCHEME}://oauth"
    }
}
