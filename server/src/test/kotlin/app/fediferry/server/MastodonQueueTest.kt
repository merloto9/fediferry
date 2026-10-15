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

import app.fediferry.api.ChannelDto
import app.fediferry.api.ImportAccountRequest
import app.fediferry.api.PlanModes
import app.fediferry.api.PlanRequest
import app.fediferry.api.PostDto
import app.fediferry.api.PostStages
import app.fediferry.mastodon.MastodonAccounts
import app.fediferry.mastodon.ScheduledPosts
import app.fediferry.platform.PlatformDetector
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

class MastodonQueueTest {

    private val dir: File = Files.createTempDirectory("fediferry-held").toFile()
    private val storage = Storage.open(File(dir, "fediferry.db"))

    /** What the fake instance holds for later; the test moves and cancels it. */
    private var heldAt = Instant.now().plusSeconds(3600).toString()
    private var cancelled = false
    private val calls = mutableListOf<String>()

    private val heldJson get() = """{"id":"501","scheduled_at":"$heldAt","params":{"text":"Held #meme","spoiler_text":"","visibility":"unlisted"},
        "media_attachments":[{"id":"m1","url":"https://files.example/m1.png","preview_url":"https://files.example/m1s.png","description":"A cat","type":"image"}]}"""

    private val fakeMastodon = Interceptor { chain ->
        val request = chain.request()
        calls += "${request.method} ${request.url.encodedPath}"
        val (code, body) = when (request.url.encodedPath) {
            "/.well-known/nodeinfo" -> 200 to """{"links":[{"rel":"http://nodeinfo.diaspora.software/ns/schema/2.0","href":"https://${request.url.host}/nodeinfo/2.0"}]}"""
            "/nodeinfo/2.0" -> 200 to """{"version":"2.0","software":{"name":"mastodon","version":"4"}}"""
            "/api/v1/accounts/verify_credentials" -> 200 to """{"id":"77","acct":"memes","display_name":"Meme Account"}"""
            "/api/v2/instance" -> 200 to "{}"
            "/api/v1/scheduled_statuses" -> 200 to (if (cancelled) "[]" else "[$heldJson]")
            "/api/v1/scheduled_statuses/501" -> when (request.method) {
                "PUT" -> {
                    val form = request.body as okhttp3.FormBody
                    heldAt = (0 until form.size).first { form.name(it) == "scheduled_at" }.let(form::value)
                    200 to heldJson
                }
                "DELETE" -> { cancelled = true; 200 to "{}" }
                else -> if (cancelled) 404 to "{}" else 200 to heldJson
            }
            else -> 404 to "{}"
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }

    /** A tiny PNG, as the picture behind the held post. */
    private val png = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )

    private val services = Services(
        storage, dir, version = "test", resolvers = emptyList(),
        fetcher = { url -> if (url == "https://files.example/m1.png") Result.success(png) else Result.failure(IllegalStateException(url)) },
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
        val token = services.projects.create("Memes").token
        client.postJson("/api/v1/channels/mastodon/import", token, ImportAccountRequest("mastodon.social", "good-token")).body<ChannelDto>()
        block(client, token)
    }

    private suspend fun HttpClient.postJson(path: String, token: String, body: Any): HttpResponse =
        post(path) { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun HttpClient.queue(token: String) = get("/api/v1/posts?stage=SCHEDULED") { bearerAuth(token) }.body<List<PostDto>>()

    @Test
    fun `the queue shows what the instance holds, marked as its own`() = test { client, token ->
        val held = client.queue(token).single()
        assertTrue(held.publication!!.heldByInstance)
        assertEquals("Held #meme", held.body)
        assertEquals("mastodon.social/memes" to "501", ScheduledPosts.RemoteId.parse(held.id))
        assertEquals(Instant.parse(heldAt).toEpochMilli(), held.publication!!.publishAfter)
        // Opened on its own, it is the same post.
        assertEquals("Held #meme", client.get("/api/v1/posts/${held.id}") { bearerAuth(token) }.body<PostDto>().body)
    }

    @Test
    fun `a held post is moved by the instance itself`() = test { client, token ->
        val held = client.queue(token).single()
        val at = Instant.now().plusSeconds(7200).toEpochMilli()
        val moved = client.postJson("/api/v1/posts/${held.id}/plan", token, PlanRequest(PlanModes.AT, at = at)).body<PostDto>()
        assertEquals(at, moved.publication!!.publishAfter)
        assertTrue("PUT /api/v1/scheduled_statuses/501" in calls)

        val tooSoon = client.postJson("/api/v1/posts/${held.id}/plan", token, PlanRequest(PlanModes.AT, at = System.currentTimeMillis() + 60_000))
        assertEquals(HttpStatusCode.UnprocessableEntity, tooSoon.status)
    }

    @Test
    fun `taken back, it is the server's to post, with its picture`() = test { client, token ->
        val held = client.queue(token).single()
        val back = client.post("/api/v1/posts/${held.id}/unplan") { bearerAuth(token) }.body<PostDto>()
        assertEquals(PostStages.READY, back.stage)
        assertEquals("Held #meme", back.finalText)
        assertEquals("UNLISTED", back.visibility)
        assertEquals("A cat", back.media.single().altText)
        assertTrue("the instance let go of it", cancelled)
        // It is no longer the instance's: the queue is empty until it is planned here.
        assertTrue(client.queue(token).isEmpty())

        val planned = client.postJson("/api/v1/posts/${back.id}/plan", token, PlanRequest(PlanModes.AT, at = Instant.now().plusSeconds(600).toEpochMilli())).body<PostDto>()
        assertFalse(planned.publication!!.heldByInstance)
    }

    @Test
    fun `a post gone from the instance cannot be taken back twice`() = test { client, token ->
        val held = client.queue(token).single()
        client.post("/api/v1/posts/${held.id}/unplan") { bearerAuth(token) }
        val again = client.post("/api/v1/posts/${held.id}/unplan") { bearerAuth(token) }
        assertEquals(HttpStatusCode.NotFound, again.status)
        assertEquals(1, client.get("/api/v1/posts?stage=READY") { bearerAuth(token) }.body<List<PostDto>>().size)
    }
}
