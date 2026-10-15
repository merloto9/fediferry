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
import app.fediferry.api.PlatformLimits
import app.fediferry.api.ChannelAccount
import app.fediferry.api.ChannelInput
import app.fediferry.api.Platform
import app.fediferry.api.PostSettings
import app.fediferry.api.ChannelDto
import app.fediferry.api.ChannelPatch
import app.fediferry.api.CompleteRequest
import app.fediferry.api.ImportAccountRequest
import app.fediferry.api.PublishedMediaDto
import app.fediferry.api.PublishedPage
import app.fediferry.api.PublishedStatusDto
import app.fediferry.mastodon.MastodonText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import app.fediferry.mastodon.MastodonAccounts
import app.fediferry.mastodon.Pkce
import app.fediferry.mastodon.MastodonException
import app.fediferry.platform.PlatformDetector
import app.fediferry.platform.PlatformException
import app.fediferry.server.db.Channel
import java.util.UUID
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
 * A project's channels — where its posts go. A channel is one fediverse
 * account, its instance's limits and how posts there start. Which software
 * an instance runs is detected when an account is connected; only Mastodon
 * can be connected so far. The server signs in itself, so an account's token
 * lives here, encrypted, and never on a phone.
 */
class Channels(
    private val storage: Storage,
    private val secrets: Secrets,
    private val crypto: Crypto,
    private val feed: ChangeFeed,
    private val mastodon: MastodonAccounts,
    private val detector: PlatformDetector,
    private val clock: () -> Long,
) {
    private val db get() = storage.db
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val log = LoggerFactory.getLogger("channels")

    fun list(projectId: String): List<ChannelDto> = db.channelQueries.all(projectId).executeAsList().map(::dto)

    fun get(projectId: String, id: String): ChannelDto =
        db.channelQueries.byId(projectId, id).executeAsOneOrNull()?.let(::dto) ?: throw ApiException.notFound("channel")

    /** A channel with no account yet, to set up before signing in. */
    fun create(projectId: String, input: ChannelInput): ChannelDto {
        val name = input.name.trim().ifEmpty { throw ApiException.badRequest("name") }
        val id = UUID.randomUUID().toString()
        val now = clock()
        feed.change(projectId, "channel", id) {
            val count = db.channelQueries.count(projectId).executeAsOne()
            db.channelQueries.insert(
                projectId, id, "", name, "", "", "", "", null, json.encodeToString(PlatformLimits()),
                if (count == 0L) 1 else 0, count, now, now, json.encodeToString(input.post ?: PostSettings()),
            )
        }
        return get(projectId, id)
    }

    /** The account's token, for publishing; never sent to a client. */
    fun token(projectId: String, channelId: String): String? = secrets.get(projectId, tokenId(channelId))

    // --- connecting -----------------------------------------------------------

    /**
     * Starts a sign-in: the server registers itself with the instance (once per
     * instance and app build) and returns the address for the phone's browser.
     */
    suspend fun authorize(projectId: String, request: AuthorizeRequest): AuthorizeResponse {
        val instance = requireInstance(request.instance)
        // Only the app's own address: a code sent anywhere else would be someone else's to use.
        if (request.redirectUri !in REDIRECT_URIS) throw ApiException.badRequest("redirect_uri")
        request.channelId?.let { get(projectId, it) }
        requireSupported(instance)
        val app = registration(instance, request.redirectUri)
        val state = randomState()
        val verifier = Pkce.verifier()
        db.oauthPendingQueries.expire(clock() - PENDING_TTL_MS)
        db.oauthPendingQueries.insert(state, projectId, instance, request.redirectUri, clock(), request.channelId, verifier)
        return AuthorizeResponse(mastodon.authorizeUrl(instance, app.first, request.redirectUri, SCOPES, state, verifier), state)
    }

    /** Finishes a sign-in with the code the instance gave the phone. */
    suspend fun complete(projectId: String, request: CompleteRequest): ChannelDto {
        val pending = db.oauthPendingQueries.take(request.state, projectId, clock() - PENDING_TTL_MS).executeAsOneOrNull()
            ?: throw ApiException(HttpStatusCode.BadRequest, "channel.unknown_state")
        db.oauthPendingQueries.delete(request.state)
        val (clientId, clientSecret) = registration(pending.instance, pending.redirect_uri)
        val token = remote { mastodon.exchangeCode(pending.instance, clientId, clientSecret, pending.redirect_uri, request.code, SCOPES, pending.code_verifier) }
        return connect(projectId, pending.instance, token, TokenClient(clientId, clientSecret), channelId = pending.channel_id, newId = null)
    }

    /** Takes over an account a phone was signed in to, with its token, after checking it works. */
    suspend fun import(projectId: String, request: ImportAccountRequest): ChannelDto {
        val instance = requireInstance(request.instance)
        // Without the phone's app the token cannot be revoked from here; it still works.
        val client = request.clientId?.takeIf { it.isNotBlank() }?.let { id -> request.clientSecret?.takeIf { it.isNotBlank() }?.let { TokenClient(id, it) } }
        return connect(projectId, instance, request.accessToken.trim(), client, channelId = null, newId = request.channelId)
    }

    /** [raw] as an instance's host name, or refused when it is not a public one. */
    private fun requireInstance(raw: String): String =
        MastodonAccounts.normaliseInstance(raw).takeIf(PublicHosts::isInstanceName) ?: throw ApiException.badRequest("instance")

    /**
     * Which software runs [instance], refusing what cannot be connected: an
     * instance that cannot be read, software FediFerry does not know, or a
     * platform it knows but cannot post to yet.
     */
    private suspend fun requireSupported(instance: String): Platform {
        val detected = try {
            detector.detect(instance)
        } catch (e: PlatformException) {
            throw ApiException(HttpStatusCode.BadGateway, e.message ?: "platform.undetected", mapOf("instance" to instance))
        }
        val platform = detected.platform
            ?: throw ApiException(HttpStatusCode.UnprocessableEntity, "platform.unknown", mapOf("software" to detected.software))
        if (!platform.supported) throw ApiException(HttpStatusCode.UnprocessableEntity, "platform.unsupported", mapOf("platform" to platform.label))
        return platform
    }

    /**
     * Puts the account [token] opens on a channel: [channelId] when given,
     * else the channel that has it already, else a new one — named [newId]
     * when a phone's account moves over, so its old id keeps working.
     */
    private suspend fun connect(projectId: String, instance: String, token: String, client: TokenClient?, channelId: String?, newId: String?): ChannelDto {
        val me = remote { mastodon.verifyCredentials(instance, token) }
        val now = clock()
        val limits = runCatching { mastodon.limits(instance, now) }
            .onFailure { log.info("Reading what $instance takes failed: ${it.javaClass.simpleName}") }
            .getOrDefault(Platform.MASTODON.defaultLimits)
        val holder = db.channelQueries.byAccount(projectId, instance, me.acct).executeAsOneOrNull()
        if (channelId != null && holder != null && holder.id != channelId) {
            throw ApiException(HttpStatusCode.Conflict, "channel.account_in_use", mapOf("channel" to holder.name))
        }
        val id = channelId ?: holder?.id ?: newId?.takeIf { it.isNotBlank() } ?: "$instance/${me.acct}"
        val shown = me.displayName.ifBlank { me.acct }
        val replaced = signedIn(projectId, id)
        feed.change(projectId, "channel", id) {
            if (db.channelQueries.byId(projectId, id).executeAsOneOrNull() == null) {
                val count = db.channelQueries.count(projectId).executeAsOne()
                db.channelQueries.insert(
                    projectId, id, Platform.MASTODON.name, shown, instance, me.id, me.acct, shown, me.avatar,
                    json.encodeToString(limits), if (count == 0L) 1 else 0, count, now, now, json.encodeToString(PostSettings()),
                )
            } else {
                db.channelQueries.setAccount(Platform.MASTODON.name, instance, me.id, me.acct, shown, me.avatar, json.encodeToString(limits), now, projectId, id)
            }
            secrets.put(projectId, tokenId(id), token)
            if (client != null) secrets.put(projectId, clientId(id), json.encodeToString(client)) else secrets.delete(projectId, clientId(id))
        }
        // The token this channel had before stops working, unless it is the same one again.
        replaced?.takeIf { it.token != token }?.let { revokeQuietly(it) }
        return get(projectId, id)
    }

    // --- changing ---------------------------------------------------------------

    suspend fun refreshLimits(projectId: String, id: String): ChannelDto {
        val channel = db.channelQueries.byId(projectId, id).executeAsOneOrNull() ?: throw ApiException.notFound("channel")
        if (channel.acct.isEmpty()) throw ApiException(HttpStatusCode.Conflict, "channel.no_account")
        val limits = remote { mastodon.limits(channel.instance, clock()) }
        feed.change(projectId, "channel", id) { db.channelQueries.setLimits(json.encodeToString(limits), clock(), projectId, id) }
        return get(projectId, id)
    }

    fun patch(projectId: String, id: String, patch: ChannelPatch): ChannelDto {
        val channel = db.channelQueries.byId(projectId, id).executeAsOneOrNull() ?: throw ApiException.notFound("channel")
        val now = clock()
        feed.change(projectId, "channel", id) {
            db.channelQueries.update(
                patch.name?.trim()?.ifEmpty { null } ?: channel.name,
                patch.post?.let { json.encodeToString(it) } ?: channel.post_json,
                channel.sort_order, now, projectId, id,
            )
            if (patch.isDefault == true) db.channelQueries.setDefault(id, now, projectId)
        }
        return get(projectId, id)
    }

    /** Removes a channel and revokes its token at the instance; the account itself is untouched. */
    suspend fun delete(projectId: String, id: String) {
        db.channelQueries.byId(projectId, id).executeAsOneOrNull() ?: throw ApiException.notFound("channel")
        val signedIn = signedIn(projectId, id)
        feed.change(projectId, "channel", id, deleted = true) {
            db.channelQueries.delete(projectId, id)
            db.scheduleQueries.deleteForChannel(projectId, id)
            secrets.delete(projectId, tokenId(id))
            secrets.delete(projectId, clientId(id))
        }
        signedIn?.let { revokeQuietly(it) }
    }

    /** Revokes every channel's token, for a project about to be deleted. */
    suspend fun revokeAll(projectId: String) {
        db.channelQueries.all(projectId).executeAsList().forEach { channel -> signedIn(projectId, channel.id)?.let { revokeQuietly(it) } }
    }

    /** A channel's token with what is needed to revoke it; null without a token. */
    private fun signedIn(projectId: String, id: String): SignedIn? {
        val channel = db.channelQueries.byId(projectId, id).executeAsOneOrNull() ?: return null
        val token = token(projectId, id) ?: return null
        val client = secrets.get(projectId, clientId(id))?.let { runCatching { json.decodeFromString<TokenClient>(it) }.getOrNull() }
        return SignedIn(channel.instance, token, client)
    }

    /**
     * Makes a token invalid at its instance, so no copy of it works any more.
     * Best effort: an instance that cannot be reached, or a token whose app is
     * unknown, never stops the channel from going.
     */
    private suspend fun revokeQuietly(signedIn: SignedIn) {
        val client = signedIn.client ?: run {
            log.info("A token was dropped without revoking: the app it was given to is unknown")
            return
        }
        runCatching { mastodon.revoke(signedIn.instance, client.id, client.secret, signedIn.token) }
            .onFailure { log.info("Revoking a token failed: {}", it.javaClass.simpleName) }
    }

    private class SignedIn(val instance: String, val token: String, val client: TokenClient?)

    // --- what went out ----------------------------------------------------------

    /**
     * The latest posts on every channel, newest first, read from the instances
     * now: nothing published is kept here. A channel whose instance cannot be
     * read is left out and named, never failing the rest.
     */
    suspend fun published(projectId: String, limit: Int): PublishedPage = coroutineScope {
        val size = limit.coerceIn(1, 40)
        val reads = db.channelQueries.all(projectId).executeAsList().filter { it.acct.isNotEmpty() }.map { channel ->
            async {
                val token = token(projectId, channel.id) ?: return@async channel.id to null
                channel.id to runCatching { mastodon.statuses(channel.instance, token, channel.remote_account_id, size) }
                    .onFailure { log.info("Reading the posts of a channel failed: {}", it.message) }
                    .getOrNull()
            }
        }.awaitAll()
        val statuses = reads.flatMap { (channelId, list) ->
            list.orEmpty().mapNotNull { st ->
                val at = runCatching { Instant.parse(st.createdAt).toEpochMilli() }.getOrNull() ?: return@mapNotNull null
                PublishedStatusDto(
                    channelId = channelId,
                    id = st.id,
                    url = st.url,
                    createdAt = at,
                    text = MastodonText.plain(st.content),
                    contentWarning = st.spoilerText.ifBlank { null },
                    media = st.media.map { PublishedMediaDto(it.previewUrl, it.url, it.description) },
                )
            }
        }
        PublishedPage(statuses.sortedByDescending { it.createdAt }.take(size), reads.filter { it.second == null }.map { it.first })
    }

    // --- plumbing ---------------------------------------------------------------

    /** The server's client id and secret at [instance] for [redirectUri], registering on first use. */
    private suspend fun registration(instance: String, redirectUri: String): Pair<String, String> {
        db.instanceAppQueries.byKey(instance, redirectUri).executeAsOneOrNull()
            ?.takeIf { it.scopes == SCOPES }
            // Sealed under a master key that is gone: register again rather than fail every sign-in.
            ?.let { app -> runCatching { crypto.open(app.client_secret_ciphertext, app.client_secret_nonce, appContext(instance, redirectUri)) }.getOrNull()?.let { return app.client_id to it } }
        val registration = remote { mastodon.registerApp(instance, CLIENT_NAME, redirectUri, SCOPES, WEBSITE) }
        val sealed = crypto.seal(registration.client_secret, appContext(instance, redirectUri))
        db.instanceAppQueries.upsert(instance, redirectUri, registration.client_id, sealed.ciphertext, sealed.nonce, SCOPES, clock())
        return registration.client_id to registration.client_secret
    }

    /** Seals again, bound to its place, every app secret sealed before values were bound. Returns how many. */
    internal fun bindAppSecrets(): Int = db.instanceAppQueries.everything().executeAsList().count { app ->
        val sealed = crypto.rebind(app.client_secret_ciphertext, app.client_secret_nonce, appContext(app.instance, app.redirect_uri)) ?: return@count false
        db.instanceAppQueries.upsert(app.instance, app.redirect_uri, app.client_id, sealed.ciphertext, sealed.nonce, app.scopes, app.created_at)
        true
    }

    private fun appContext(instance: String, redirectUri: String) = "instance-app|$instance|$redirectUri"

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

        /** Where a sign-in may come back to: the app's schemes, release and debug, under both its names. */
        val REDIRECT_URIS = setOf("neoferry://oauth", "neoferry-debug://oauth", "fediferry://oauth", "fediferry-debug://oauth")

        fun tokenId(channelId: String) = "channel-token:$channelId"

        /** The app a channel's token was given to: needed to revoke the token. */
        fun clientId(channelId: String) = "channel-client:$channelId"

        private val dtoJson = Json { ignoreUnknownKeys = true }

        fun dto(row: Channel) = ChannelDto(
            id = row.id,
            name = row.name,
            isDefault = row.is_default != 0L,
            account = row.takeIf { it.acct.isNotEmpty() }?.let {
                ChannelAccount(it.platform, it.instance, it.acct, it.display_name, it.avatar_url)
            },
            limits = runCatching { dtoJson.decodeFromString<PlatformLimits>(row.limits_json) }.getOrDefault(PlatformLimits()),
            post = postOf(row),
        )

        fun postOf(row: Channel): PostSettings = runCatching { dtoJson.decodeFromString<PostSettings>(row.post_json) }.getOrDefault(PostSettings())
    }
}

/** An app registered with an instance, as kept beside a token it was given. */
@kotlinx.serialization.Serializable
internal data class TokenClient(val id: String, val secret: String)

fun Route.channelRoutes(services: Services) {
    val channels = services.channels

    get("/channels") { call.respond(channels.list(call.projectId())) }
    post("/channels") { call.respond(HttpStatusCode.Created, channels.create(call.projectId(), call.receive<ChannelInput>())) }
    get("/channels/{id}") { call.respond(channels.get(call.projectId(), call.parameters["id"].orEmpty())) }
    patch("/channels/{id}") {
        call.respond(channels.patch(call.projectId(), call.parameters["id"].orEmpty(), call.receive<ChannelPatch>()))
    }
    delete("/channels/{id}") {
        channels.delete(call.projectId(), call.parameters["id"].orEmpty())
        call.respond(HttpStatusCode.NoContent)
    }
    post("/channels/{id}/refresh-limits") {
        call.respond(channels.refreshLimits(call.projectId(), call.parameters["id"].orEmpty()))
    }
    post("/channels/mastodon/authorize") { call.respond(channels.authorize(call.projectId(), call.receive<AuthorizeRequest>())) }
    post("/channels/mastodon/complete") { call.respond(channels.complete(call.projectId(), call.receive<CompleteRequest>())) }
    get("/published") {
        call.respond(channels.published(call.projectId(), call.request.queryParameters["limit"]?.toIntOrNull() ?: 20))
    }
    post("/channels/mastodon/import") { call.respond(channels.import(call.projectId(), call.receive<ImportAccountRequest>())) }
}
