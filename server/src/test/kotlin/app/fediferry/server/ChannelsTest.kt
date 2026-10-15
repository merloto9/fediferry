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
import app.fediferry.api.PublishedPage
import app.fediferry.mastodon.MastodonAccounts
import app.fediferry.platform.PlatformDetector
import app.fediferry.api.ChannelInput
import app.fediferry.api.PostSettings
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
import app.fediferry.api.SecretInput
import okhttp3.HttpUrl.Companion.toHttpUrl
import io.ktor.client.request.put
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
    private var statusesDown = false
    /** Each revoke the fake instance received: client id, client secret and token. */
    private val revoked = mutableListOf<Triple<String?, String?, String?>>()
    /** The PKCE verifier the last token exchange carried. */
    private var tokenVerifier: String? = null
    /** What the fake instance says it runs. */
    private var software = "mastodon"

    /** A Mastodon instance in miniature: registration, token exchange, the account, the instance. */
    private val fakeMastodon = Interceptor { chain ->
        val request = chain.request()
        val (code, body) = when (request.url.encodedPath) {
            "/.well-known/nodeinfo" -> 200 to """{"links":[{"rel":"http://nodeinfo.diaspora.software/ns/schema/2.0","href":"https://${request.url.host}/nodeinfo/2.0"}]}"""
            "/nodeinfo/2.0" -> 200 to """{"version":"2.0","software":{"name":"$software","version":"1"}}"""
            "/api/v1/apps" -> { appRegistrations++; 200 to """{"client_id":"cid","client_secret":"csecret"}""" }
            "/oauth/token" -> {
                val form = request.body as okhttp3.FormBody
                tokenVerifier = (0 until form.size).firstOrNull { form.name(it) == "code_verifier" }?.let(form::value)
                200 to """{"access_token":"good-token","token_type":"Bearer"}"""
            }
            "/oauth/revoke" -> {
                val form = request.body as okhttp3.FormBody
                val field = { name: String -> (0 until form.size).firstOrNull { form.name(it) == name }?.let(form::value) }
                revoked += Triple(field("client_id"), field("client_secret"), field("token"))
                200 to "{}"
            }
            "/api/v1/accounts/verify_credentials" ->
                if (request.header("Authorization") == "Bearer good-token") 200 to """{"id":"77","acct":"memes","display_name":"Meme Account"}"""
                else 401 to """{"error":"The access token is invalid"}"""
            "/api/v1/accounts/77/statuses" ->
                if (statusesDown) 503 to "{}"
                else 200 to """[{"id":"s2","created_at":"2026-10-08T12:00:00.000Z","url":"https://mastodon.social/@memes/s2","content":"<p>Second &amp; last</p>","spoiler_text":"",
                    "media_attachments":[{"id":"m1","preview_url":"https://files.example/m1.png","description":"A cat"}]},
                   {"id":"s1","created_at":"2026-10-07T12:00:00.000Z","content":"<p>First</p>","spoiler_text":"cw"}]"""
            "/api/v2/instance" -> 200 to """{"configuration":{"statuses":{"max_characters":1000,"max_media_attachments":4}}}"""
            else -> 404 to "{}"
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }

    private val services = Services(
        storage, dir, version = "test", resolvers = emptyList(), fetcher = { Result.failure(IllegalStateException()) },
        mastodon = MastodonAccounts(OkHttpClient.Builder().addInterceptor(fakeMastodon).build()),
        detector = PlatformDetector(OkHttpClient.Builder().addInterceptor(fakeMastodon).build()),
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
        assertEquals("Meme Account", channel.account?.displayName)
        assertEquals("MASTODON", channel.account?.platform)
        assertTrue("the first channel is the default", channel.isDefault)
        assertEquals(1000, channel.limits.maxCharacters)

        val projectId = services.projects.all().single().id
        assertEquals("good-token", services.channels.token(projectId, channel.id))
    }

    @Test
    fun `a sign-in through the server uses PKCE`() = test { client, token ->
        val auth = client.postJson("/api/v1/channels/mastodon/authorize", token, AuthorizeRequest("mastodon.social", "fediferry-debug://oauth")).body<AuthorizeResponse>()
        val url = auth.url.toHttpUrl()
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        client.postJson("/api/v1/channels/mastodon/complete", token, CompleteRequest("the-code", auth.state))
        // The verifier never left the server until the exchange, and it matches the challenge.
        assertEquals(url.queryParameter("code_challenge"), app.fediferry.mastodon.Pkce.challenge(tokenVerifier!!))
    }

    @Test
    fun `a sign-in may only come back to the app`() = test { client, token ->
        val evil = client.postJson("/api/v1/channels/mastodon/authorize", token, AuthorizeRequest("mastodon.social", "https://evil.example/cb"))
        assertEquals(HttpStatusCode.BadRequest, evil.status)
    }

    @Test
    fun `the server talks only to public instances`() = test { client, token ->
        listOf("127.0.0.1", "localhost", "169.254.169.254", "mastodon.social:8443", "nas.local").forEach { host ->
            val auth = client.postJson("/api/v1/channels/mastodon/authorize", token, AuthorizeRequest(host, "fediferry-debug://oauth"))
            assertEquals(host, HttpStatusCode.BadRequest, auth.status)
            val import = client.postJson("/api/v1/channels/mastodon/import", token, ImportAccountRequest(host, "good-token"))
            assertEquals(host, HttpStatusCode.BadRequest, import.status)
        }
    }

    @Test
    fun `an old sign-in state has run out`() = test { client, token ->
        val projectId = services.projects.all().single().id
        val halfAnHourAgo = System.currentTimeMillis() - 31 * 60 * 1000L
        storage.db.oauthPendingQueries.insert("old-state", projectId, "mastodon.social", "fediferry-debug://oauth", halfAnHourAgo, null, "v")
        val late = client.postJson("/api/v1/channels/mastodon/complete", token, CompleteRequest("the-code", "old-state"))
        assertEquals(HttpStatusCode.BadRequest, late.status)
    }

    @Test
    fun `a channel's token cannot be set or removed as a secret`() = test { client, token ->
        val channel = client.signIn(token)
        val id = "channel-token:${channel.id}"
        val put = client.put("/api/v1/secrets/${enc(id)}") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(SecretInput("planted")) }
        assertEquals(HttpStatusCode.BadRequest, put.status)
        assertEquals(HttpStatusCode.BadRequest, client.delete("/api/v1/secrets/${enc(id)}") { bearerAuth(token) }.status)
        assertEquals("good-token", services.channels.token(services.projects.all().single().id, channel.id))
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
        val second = client.postJson("/api/v1/channels", token, ChannelInput("Second")).body<ChannelDto>()
        assertFalse(second.isDefault)

        val renamed = client.patch("/api/v1/channels/${enc(second.id)}") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(ChannelPatch(name = "Backup", isDefault = true))
        }.body<ChannelDto>()
        assertEquals("Backup", renamed.name)
        assertTrue(renamed.isDefault)
        assertFalse(client.get("/api/v1/channels/${enc(first.id)}") { bearerAuth(token) }.body<ChannelDto>().isDefault)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/channels/${enc(first.id)}") { bearerAuth(token) }.status)
        assertNull(services.channels.token(services.projects.all().single().id, first.id))
        // The token stops working at the instance too, revoked as the app it was given to.
        assertEquals(listOf(Triple("cid", "csecret", "good-token")), revoked)
    }

    @Test
    fun `a moved-over account is revoked with the phone's app, and only with one`() = test { client, token ->
        val moved = client.postJson(
            "/api/v1/channels/mastodon/import", token,
            ImportAccountRequest("mastodon.social", "good-token", "mastodon.social/memes", clientId = "phone-cid", clientSecret = "phone-secret"),
        ).body<ChannelDto>()
        client.delete("/api/v1/channels/${enc(moved.id)}") { bearerAuth(token) }
        assertEquals(listOf(Triple("phone-cid", "phone-secret", "good-token")), revoked)

        // From an older phone, without its app: the channel still goes, nothing is revoked.
        revoked.clear()
        val bare = client.postJson("/api/v1/channels/mastodon/import", token, ImportAccountRequest("mastodon.social", "good-token")).body<ChannelDto>()
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/channels/${enc(bare.id)}") { bearerAuth(token) }.status)
        assertTrue(revoked.isEmpty())
    }

    @Test
    fun `what went out is read live from the channel`() = test { client, token ->
        val channel = client.signIn(token)
        val page = client.get("/api/v1/published?limit=5") { bearerAuth(token) }.body<PublishedPage>()
        assertEquals(listOf("s2", "s1"), page.statuses.map { it.id })
        val latest = page.statuses.first()
        assertEquals(channel.id, latest.channelId)
        assertEquals("Second & last", latest.text)
        assertEquals("https://mastodon.social/@memes/s2", latest.url)
        assertEquals("A cat", latest.media.single().description)
        assertEquals("cw", page.statuses[1].contentWarning)
        assertTrue(page.unreachable.isEmpty())
    }

    @Test
    fun `an instance that cannot be read leaves an empty list, not an error`() = test { client, token ->
        val channel = client.signIn(token)
        statusesDown = true
        val response = client.get("/api/v1/published") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status)
        val page = response.body<PublishedPage>()
        assertTrue(page.statuses.isEmpty())
        assertEquals(listOf(channel.id), page.unreachable)
    }

    @Test
    fun `a channel set up first gets its account by signing in to it`() = test { client, token ->
        val made = client.postJson("/api/v1/channels", token, ChannelInput("Cats", PostSettings(body = "{tags}", tags = "#cats"))).body<ChannelDto>()
        assertNull(made.account)
        assertEquals("Cats", made.label)

        val auth = client.postJson("/api/v1/channels/mastodon/authorize", token, AuthorizeRequest("mastodon.social", "fediferry-debug://oauth", made.id)).body<AuthorizeResponse>()
        val channel = client.postJson("/api/v1/channels/mastodon/complete", token, CompleteRequest("the-code", auth.state)).body<ChannelDto>()
        assertEquals(made.id, channel.id)
        assertEquals("memes", channel.account?.acct)
        assertEquals("@memes", channel.label)
        assertEquals("#cats", channel.post.tags)
        assertEquals(1, client.get("/api/v1/channels") { bearerAuth(token) }.body<List<ChannelDto>>().size)
    }

    @Test
    fun `an account already on one channel is not connected to a second`() = test { client, token ->
        client.signIn(token)
        val other = client.postJson("/api/v1/channels", token, ChannelInput("Other")).body<ChannelDto>()
        val auth = client.postJson("/api/v1/channels/mastodon/authorize", token, AuthorizeRequest("mastodon.social", "fediferry-debug://oauth", other.id)).body<AuthorizeResponse>()
        val response = client.postJson("/api/v1/channels/mastodon/complete", token, CompleteRequest("the-code", auth.state))
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("channel.account_in_use", response.body<ApiErrorBody>().code)
    }

    @Test
    fun `an instance running other software is detected and refused`() = test { client, token ->
        software = "pixelfed"
        val pixelfed = client.postJson("/api/v1/channels/mastodon/authorize", token, AuthorizeRequest("pixelfed.social", "fediferry-debug://oauth"))
        assertEquals(HttpStatusCode.UnprocessableEntity, pixelfed.status)
        assertEquals("platform.unsupported" to "Pixelfed", pixelfed.body<ApiErrorBody>().let { it.code to it.args["platform"] })

        software = "misskey"
        val misskey = client.postJson("/api/v1/channels/mastodon/authorize", token, AuthorizeRequest("misskey.io", "fediferry-debug://oauth")).body<ApiErrorBody>()
        assertEquals("platform.unknown" to "misskey", misskey.code to misskey.args["software"])
    }

    @Test
    fun `a channel's post settings are changed with the channel`() = test { client, token ->
        val channel = client.signIn(token)
        val changed = client.patch("/api/v1/channels/${enc(channel.id)}") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(ChannelPatch(post = PostSettings(body = "{link}", tags = "#dogs", visibility = "UNLISTED")))
        }.body<ChannelDto>()
        assertEquals("{link}", changed.post.body)
        assertEquals("UNLISTED", changed.post.visibility)
    }
}
