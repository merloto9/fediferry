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
package app.fediferry.server

import app.fediferry.api.AuthorizeRequest
import app.fediferry.api.AuthorizeResponse
import app.fediferry.api.ChannelCapabilities
import app.fediferry.api.ChannelDefaults
import app.fediferry.api.ChannelDto
import app.fediferry.api.ChannelPatch
import app.fediferry.api.ChannelTypes
import app.fediferry.api.CompleteRequest
import app.fediferry.api.ImportAccountRequest
import app.fediferry.mastodon.MastodonAccounts
import app.fediferry.mastodon.MastodonException
import app.fediferry.server.db.Channel
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.security.SecureRandom

/**
 * A project's channels — where its posts go. For now Mastodon accounts: the
 * server signs in itself, so an account's token lives here, encrypted, and
 * never on a phone.
 */
class Channels(
    private val storage: Storage,
    private val secrets: Secrets,
    private val crypto: Crypto,
    private val feed: ChangeFeed,
    private val mastodon: MastodonAccounts,
    private val clock: () -> Long,
) {
    private val db get() = storage.db
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val log = LoggerFactory.getLogger("channels")

    fun list(projectId: String): List<ChannelDto> = db.channelQueries.all(projectId).executeAsList().map(::dto)

    fun get(projectId: String, id: String): ChannelDto =
        db.channelQueries.byId(projectId, id).executeAsOneOrNull()?.let(::dto) ?: throw ApiException.notFound("channel")

    /** The account's token, for publishing; never sent to a client. */
    fun token(projectId: String, channelId: String): String? = secrets.get(projectId, tokenId(channelId))

    // --- connecting -----------------------------------------------------------

    /**
     * Starts a sign-in: the server registers itself with the instance (once per
     * instance and app build) and returns the address for the phone's browser.
     */
    suspend fun authorize(projectId: String, request: AuthorizeRequest): AuthorizeResponse {
        val instance = MastodonAccounts.normaliseInstance(request.instance)
        if (instance.isBlank() || '.' !in instance) throw ApiException.badRequest("instance")
        if (!request.redirectUri.matches(Regex("""[a-z][a-z0-9+.-]*://[\w./-]+"""))) throw ApiException.badRequest("redirect_uri")
        val app = registration(instance, request.redirectUri)
        val state = randomState()
        db.oauthPendingQueries.expire(clock() - PENDING_TTL_MS)
        db.oauthPendingQueries.insert(state, projectId, instance, request.redirectUri, clock())
        return AuthorizeResponse(mastodon.authorizeUrl(instance, app.first, request.redirectUri, SCOPES, state), state)
    }

    /** Finishes a sign-in with the code the instance gave the phone. */
    suspend fun complete(projectId: String, request: CompleteRequest): ChannelDto {
        val pending = db.oauthPendingQueries.take(request.state, projectId).executeAsOneOrNull()
            ?: throw ApiException(HttpStatusCode.BadRequest, "channel.unknown_state")
        db.oauthPendingQueries.delete(request.state)
        val (clientId, clientSecret) = registration(pending.instance, pending.redirect_uri)
        val token = remote { mastodon.exchangeCode(pending.instance, clientId, clientSecret, pending.redirect_uri, request.code, SCOPES) }
        return connect(projectId, pending.instance, token, null)
    }

    /** Takes over an account a phone was signed in to, with its token, after checking it works. */
    suspend fun import(projectId: String, request: ImportAccountRequest): ChannelDto {
        val instance = MastodonAccounts.normaliseInstance(request.instance)
        return connect(projectId, instance, request.accessToken.trim(), request.channelId)
    }

    private suspend fun connect(projectId: String, instance: String, token: String, preferredId: String?): ChannelDto {
        val me = remote { mastodon.verifyCredentials(instance, token) }
        // The same id the app gave its accounts, so templates naming an account keep working.
        val id = preferredId?.takeIf { it.isNotBlank() } ?: "$instance/${me.acct}"
        val now = clock()
        val capabilities = runCatching { mastodon.capabilities(instance, now) }
            .onFailure { log.info("Reading what $instance takes failed: ${it.javaClass.simpleName}") }
            .getOrDefault(ChannelCapabilities(readAt = 0))
        feed.change(projectId, "channel", id) {
            val existing = db.channelQueries.byId(projectId, id).executeAsOneOrNull()
            if (existing == null) {
                val first = db.channelQueries.count(projectId).executeAsOne() == 0L
                db.channelQueries.insert(
                    projectId, id, ChannelTypes.MASTODON, me.displayName.ifBlank { me.acct }, instance, me.id, me.acct,
                    me.displayName.ifBlank { me.acct }, me.avatar, json.encodeToString(capabilities), json.encodeToString(ChannelDefaults()),
                    if (first) 1 else 0, db.channelQueries.count(projectId).executeAsOne(), now, now,
                )
            } else {
                db.channelQueries.refreshAccount(me.displayName.ifBlank { me.acct }, me.avatar, me.id, now, projectId, id)
                db.channelQueries.setCapabilities(json.encodeToString(capabilities), now, projectId, id)
            }
            secrets.put(projectId, tokenId(id), token)
        }
        return get(projectId, id)
    }

    // --- changing ---------------------------------------------------------------

    suspend fun refreshCapabilities(projectId: String, id: String): ChannelDto {
        val channel = db.channelQueries.byId(projectId, id).executeAsOneOrNull() ?: throw ApiException.notFound("channel")
        val capabilities = remote { mastodon.capabilities(channel.instance, clock()) }
        feed.change(projectId, "channel", id) { db.channelQueries.setCapabilities(json.encodeToString(capabilities), clock(), projectId, id) }
        return get(projectId, id)
    }

    fun patch(projectId: String, id: String, patch: ChannelPatch): ChannelDto {
        val channel = db.channelQueries.byId(projectId, id).executeAsOneOrNull() ?: throw ApiException.notFound("channel")
        val now = clock()
        feed.change(projectId, "channel", id) {
            db.channelQueries.update(
                patch.name?.trim()?.ifEmpty { null } ?: channel.name,
                patch.defaults?.let { json.encodeToString(it) } ?: channel.defaults_json,
                channel.sort_order, now, projectId, id,
            )
            if (patch.isDefault == true) db.channelQueries.setDefault(id, now, projectId)
        }
        return get(projectId, id)
    }

    /** Removes a channel and forgets its token; the account itself is untouched. */
    fun delete(projectId: String, id: String) {
        db.channelQueries.byId(projectId, id).executeAsOneOrNull() ?: throw ApiException.notFound("channel")
        feed.change(projectId, "channel", id, deleted = true) {
            db.channelQueries.delete(projectId, id)
            secrets.delete(projectId, tokenId(id))
        }
    }

    // --- plumbing ---------------------------------------------------------------

    /** The server's client id and secret at [instance] for [redirectUri], registering on first use. */
    private suspend fun registration(instance: String, redirectUri: String): Pair<String, String> {
        db.instanceAppQueries.byKey(instance, redirectUri).executeAsOneOrNull()
            ?.takeIf { it.scopes == SCOPES }
            ?.let { return it.client_id to crypto.open(it.client_secret_ciphertext, it.client_secret_nonce) }
        val registration = remote { mastodon.registerApp(instance, CLIENT_NAME, redirectUri, SCOPES, WEBSITE) }
        val sealed = crypto.seal(registration.client_secret)
        db.instanceAppQueries.upsert(instance, redirectUri, registration.client_id, sealed.ciphertext, sealed.nonce, SCOPES, clock())
        return registration.client_id to registration.client_secret
    }

    private suspend fun <T> remote(block: suspend () -> T): T = try {
        block()
    } catch (e: MastodonException) {
        throw ApiException(HttpStatusCode.BadGateway, e.message ?: "mastodon.error", mapOf("status" to e.code.toString()))
    }


    private fun randomState(): String = ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val SCOPES = "read:accounts read:statuses write:statuses write:media"
        const val CLIENT_NAME = "FediFerry"
        const val WEBSITE = "https://github.com/merloto9/fediferry"
        private const val PENDING_TTL_MS = 30 * 60 * 1000L

        fun tokenId(channelId: String) = "channel-token:$channelId"

        private val dtoJson = Json { ignoreUnknownKeys = true }

        fun dto(row: Channel) = ChannelDto(
            id = row.id,
            type = row.type,
            name = row.name,
            instance = row.instance,
            acct = row.acct,
            displayName = row.display_name,
            avatarUrl = row.avatar_url,
            isDefault = row.is_default != 0L,
            capabilities = runCatching { dtoJson.decodeFromString<ChannelCapabilities>(row.capabilities_json) }.getOrDefault(ChannelCapabilities()),
            defaults = runCatching { dtoJson.decodeFromString<ChannelDefaults>(row.defaults_json) }.getOrDefault(ChannelDefaults()),
        )
    }
}

fun Route.channelRoutes(services: Services) {
    val channels = services.channels

    get("/channels") { call.respond(channels.list(call.projectId())) }
    get("/channels/{id}") { call.respond(channels.get(call.projectId(), call.parameters["id"].orEmpty())) }
    patch("/channels/{id}") {
        call.respond(channels.patch(call.projectId(), call.parameters["id"].orEmpty(), call.receive<ChannelPatch>()))
    }
    delete("/channels/{id}") {
        channels.delete(call.projectId(), call.parameters["id"].orEmpty())
        call.respond(HttpStatusCode.NoContent)
    }
    post("/channels/{id}/refresh-capabilities") {
        call.respond(channels.refreshCapabilities(call.projectId(), call.parameters["id"].orEmpty()))
    }
    post("/channels/mastodon/authorize") { call.respond(channels.authorize(call.projectId(), call.receive<AuthorizeRequest>())) }
    post("/channels/mastodon/complete") { call.respond(channels.complete(call.projectId(), call.receive<CompleteRequest>())) }
    post("/channels/mastodon/import") { call.respond(channels.import(call.projectId(), call.receive<ImportAccountRequest>())) }
}
