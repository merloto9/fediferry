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

import app.fediferry.api.ApiErrorBody
import app.fediferry.api.AuthorizeRequest
import app.fediferry.api.AuthorizeResponse
import app.fediferry.api.ChannelDto
import app.fediferry.api.ChannelPatch
import app.fediferry.api.CompleteRequest
import app.fediferry.api.ImportAccountRequest
import app.fediferry.mastodon.MastodonAccounts
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ChannelsTest {

    private val dir: File = Files.createTempDirectory("fediferry-channels").toFile()
    private val storage = Storage.open(File(dir, "fediferry.db"))
    private var appRegistrations = 0

    /** A Mastodon instance in miniature: registration, token exchange, the account, the instance. */
    private val fakeMastodon = Interceptor { chain ->
        val request = chain.request()
        val (code, body) = when (request.url.encodedPath) {
            "/api/v1/apps" -> { appRegistrations++; 200 to """{"client_id":"cid","client_secret":"csecret"}""" }
            "/oauth/token" -> 200 to """{"access_token":"good-token","token_type":"Bearer"}"""
            "/api/v1/accounts/verify_credentials" ->
                if (request.header("Authorization") == "Bearer good-token") 200 to """{"id":"77","acct":"memes","display_name":"Meme Account"}"""
                else 401 to """{"error":"The access token is invalid"}"""
            "/api/v2/instance" -> 200 to """{"configuration":{"statuses":{"max_characters":1000,"max_media_attachments":4}}}"""
            else -> 404 to "{}"
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }

    private val services = Services(
        storage, dir, version = "test", resolvers = emptyList(), fetcher = { Result.failure(IllegalStateException()) },
        mastodon = MastodonAccounts(OkHttpClient.Builder().addInterceptor(fakeMastodon).build()),
    )

    @After fun cleanUp() {
        storage.close()
        dir.deleteRecursively()
    }

    private fun test(block: suspend ApplicationTestBuilder.(HttpClient, String) -> Unit) = testApplication {
        application { fediferry(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        block(client, services.projects.create("Memes").token)
    }

    /** Channel ids hold a slash ("instance/acct"), so they travel encoded. */
    private fun enc(id: String) = java.net.URLEncoder.encode(id, "UTF-8")

    private suspend fun HttpClient.postJson(path: String, token: String, body: Any): HttpResponse =
        post(path) { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.signIn(token: String): ChannelDto {
        val auth = postJson("/api/v1/channels/mastodon/authorize", token, AuthorizeRequest("@me@Mastodon.Social", "fediferry-debug://oauth")).body<AuthorizeResponse>()
        return postJson("/api/v1/channels/mastodon/complete", token, CompleteRequest("the-code", auth.state)).body<ChannelDto>()
    }

    @Test
    fun `signing in through the server makes a channel`() = test { client, token ->
        val auth = client.postJson("/api/v1/channels/mastodon/authorize", token, AuthorizeRequest("mastodon.social", "fediferry-debug://oauth")).body<AuthorizeResponse>()
        assertTrue(auth.url.startsWith("https://mastodon.social/oauth/authorize?"))
        assertTrue("client_id=cid" in auth.url && "state=${auth.state}" in auth.url)

        val channel = client.postJson("/api/v1/channels/mastodon/complete", token, CompleteRequest("the-code", auth.state)).body<ChannelDto>()
        assertEquals("mastodon.social/memes", channel.id)
        assertEquals("Meme Account", channel.displayName)
        assertTrue("the first channel is the default", channel.isDefault)
        assertEquals(1000, channel.capabilities.maxCharacters)

        val projectId = services.projects.all().single().id
        assertEquals("good-token", services.channels.token(projectId, channel.id))
    }

    @Test
    fun `the server registers with an instance once`() = test { client, token ->
        client.signIn(token)
        client.signIn(token)
        assertEquals(1, appRegistrations)
        assertEquals(1, client.get("/api/v1/channels") { bearerAuth(token) }.body<List<ChannelDto>>().size)
    }

    @Test
    fun `a sign-in state is used once and only by its project`() = test { client, token ->
        val auth = client.postJson("/api/v1/channels/mastodon/authorize", token, AuthorizeRequest("mastodon.social", "fediferry-debug://oauth")).body<AuthorizeResponse>()
        val other = services.projects.create("Other").token
        val stolen = client.postJson("/api/v1/channels/mastodon/complete", other, CompleteRequest("the-code", auth.state))
        assertEquals(HttpStatusCode.BadRequest, stolen.status)
        assertEquals("channel.unknown_state", stolen.body<ApiErrorBody>().code)

        assertEquals(HttpStatusCode.OK, client.postJson("/api/v1/channels/mastodon/complete", token, CompleteRequest("the-code", auth.state)).status)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/v1/channels/mastodon/complete", token, CompleteRequest("the-code", auth.state)).status)
    }

    @Test
    fun `an account moves over with its token, a bad token is refused`() = test { client, token ->
        val moved = client.postJson("/api/v1/channels/mastodon/import", token, ImportAccountRequest("mastodon.social", "good-token", "mastodon.social/memes")).body<ChannelDto>()
        assertEquals("mastodon.social/memes", moved.id)

        val refused = client.postJson("/api/v1/channels/mastodon/import", token, ImportAccountRequest("mastodon.social", "stale"))
        assertEquals(HttpStatusCode.BadGateway, refused.status)
        assertEquals("mastodon.unauthorized", refused.body<ApiErrorBody>().code)
    }

    @Test
    fun `no response ever carries the account token`() = test { client, token ->
        val channel = client.signIn(token)
        listOf("/api/v1/channels", "/api/v1/channels/${enc(channel.id)}", "/api/v1/secrets").forEach { path ->
            val response = client.get(path) { bearerAuth(token) }
            assertEquals(path, HttpStatusCode.OK, response.status)
            assertFalse(path, response.bodyAsText().contains("good-token"))
        }
    }

    @Test
    fun `channels are renamed, made default and removed with their token`() = test { client, token ->
        val first = client.signIn(token)
        val second = client.postJson("/api/v1/channels/mastodon/import", token, ImportAccountRequest("mastodon.social", "good-token", "second")).body<ChannelDto>()
        assertFalse(second.isDefault)

        val renamed = client.patch("/api/v1/channels/${enc(second.id)}") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(ChannelPatch(name = "Backup", isDefault = true))
        }.body<ChannelDto>()
        assertEquals("Backup", renamed.name)
        assertTrue(renamed.isDefault)
        assertFalse(client.get("/api/v1/channels/${enc(first.id)}") { bearerAuth(token) }.body<ChannelDto>().isDefault)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/channels/${enc(second.id)}") { bearerAuth(token) }.status)
        assertNull(services.channels.token(services.projects.all().single().id, second.id))
    }
}
