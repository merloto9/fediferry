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
import app.fediferry.api.ChannelDto
import app.fediferry.api.CreatePost
import app.fediferry.api.DeviceRegistration
import app.fediferry.api.ImportAccountRequest
import app.fediferry.api.IngestResult
import app.fediferry.api.LabelDto
import app.fediferry.api.PostDto
import app.fediferry.api.PostPatch
import app.fediferry.api.PostStages
import app.fediferry.api.ReviewFolderDto
import app.fediferry.api.ReviewFolderInput
import app.fediferry.api.ReviewPatch
import app.fediferry.channel.PostValidator
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

class ReviewTest {

    private val dir: File = Files.createTempDirectory("fediferry-review").toFile()
    private val storage = Storage.open(File(dir, "fediferry.db"))

    /** An instance that knows one account and takes 100 characters. */
    private val fakeMastodon = Interceptor { chain ->
        val request = chain.request()
        val body = when (request.url.encodedPath) {
            "/api/v1/accounts/verify_credentials" -> """{"id":"77","acct":"memes","display_name":"Memes"}"""
            "/api/v2/instance" -> """{"configuration":{"statuses":{"max_characters":100,"max_media_attachments":4}}}"""
            else -> "{}"
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("ok")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }

    private val services = Services(
        storage, dir, version = "test", resolvers = emptyList(), fetcher = { Result.failure(IllegalStateException()) },
        mastodon = app.fediferry.mastodon.MastodonAccounts(OkHttpClient.Builder().addInterceptor(fakeMastodon).build()),
    )

    @After fun cleanUp() {
        storage.close()
        dir.deleteRecursively()
    }

    private fun png(): ByteArray = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    private fun test(block: suspend ApplicationTestBuilder.(HttpClient, String) -> Unit) = testApplication {
        application { fediferry(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val token = services.projects.create("Memes").token
        for (id in listOf("pixel", "fairphone")) {
            client.post("/api/v1/devices") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(DeviceRegistration(id, id, "android", "1")) }
        }
        block(client, token)
    }

    private suspend fun HttpClient.channel(token: String): ChannelDto = post("/api/v1/channels/mastodon/import") {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody(ImportAccountRequest("social.example", "good-token", null))
    }.body()

    private suspend fun HttpClient.draft(token: String, channelId: String?): PostDto {
        val item = submitFormWithBinaryData(
            url = "/api/v1/ingest/${java.util.UUID.randomUUID()}",
            formData = formData {
                append("link", "https://example.org/meme")
                append("file", png(), Headers.build {
                    append(HttpHeaders.ContentType, "image/png"); append(HttpHeaders.ContentDisposition, "filename=\"x.png\"")
                })
            },
        ) { method = HttpMethod.Put; bearerAuth(token); header("X-Device-Id", "pixel") }.body<IngestResult>().item.id
        return post("/api/v1/posts") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(CreatePost(listOf(item), channelId))
        }.body()
    }

    private suspend fun HttpClient.ready(token: String, post: PostDto, device: String = "pixel"): HttpResponse =
        post("/api/v1/posts/${post.id}/ready") { bearerAuth(token); header("X-Device-Id", device); header(HttpHeaders.IfMatch, post.version.toString()) }

    private suspend fun HttpClient.edit(token: String, post: PostDto, patch: PostPatch): PostDto {
        post("/api/v1/posts/${post.id}/lock") { bearerAuth(token); header("X-Device-Id", "pixel") }
        return patch("/api/v1/posts/${post.id}") {
            bearerAuth(token); header("X-Device-Id", "pixel"); header(HttpHeaders.IfMatch, post.version.toString())
            contentType(ContentType.Application.Json); setBody(patch)
        }.body()
    }

    @Test
    fun `ready freezes the text with the hashtags in and lets go of the lock`() = test { client, token ->
        val channel = client.channel(token)
        val draft = client.edit(token, client.draft(token, channel.id), PostPatch(body = "Look {tags}", hashtags = listOf("#meme")))
        val ready = client.ready(token, draft).body<PostDto>()
        assertEquals(PostStages.READY, ready.stage)
        assertEquals("Look #meme", ready.finalText)
        assertTrue(ready.readyAt != null)
        assertNull(ready.lock)

        // Frozen: neither the lock nor an edit is to be had any more.
        assertEquals(HttpStatusCode.Conflict, client.post("/api/v1/posts/${ready.id}/lock") { bearerAuth(token); header("X-Device-Id", "pixel") }.status)
    }

    @Test
    fun `a post the channel would not take is refused with every reason`() = test { client, token ->
        val noChannel = client.draft(token, channelId = null)
        val refused = client.ready(token, noChannel)
        assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
        assertEquals(listOf(PostValidator.NO_CHANNEL), refused.body<ApiErrorBody>().violations.map { it.code })

        val channel = client.channel(token)
        val long = client.edit(token, client.draft(token, channel.id), PostPatch(body = "x".repeat(101)))
        val tooLong = client.ready(token, long).body<ApiErrorBody>()
        assertEquals("post.not_ready", tooLong.code)
        assertEquals(PostValidator.TOO_LONG, tooLong.violations.single().code)
        assertEquals("100", tooLong.violations.single().args["max"])
    }

    @Test
    fun `ready waits while another phone is editing`() = test { client, token ->
        val channel = client.channel(token)
        val draft = client.draft(token, channel.id)
        client.post("/api/v1/posts/${draft.id}/lock") { bearerAuth(token); header("X-Device-Id", "fairphone") }
        assertEquals(HttpStatusCode.Locked, client.ready(token, draft).status)
        assertEquals(HttpStatusCode.OK, client.ready(token, draft, device = "fairphone").status)
    }

    @Test
    fun `back to draft opens the post again`() = test { client, token ->
        val channel = client.channel(token)
        val ready = client.ready(token, client.draft(token, channel.id)).body<PostDto>()
        val draft = client.post("/api/v1/posts/${ready.id}/unready") { bearerAuth(token) }.body<PostDto>()
        assertEquals(PostStages.DRAFT, draft.stage)
        assertNull(draft.finalText)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/posts/${draft.id}/lock") { bearerAuth(token); header("X-Device-Id", "pixel") }.status)
    }

    @Test
    fun `ready posts sort into folders and carry labels`() = test { client, token ->
        val channel = client.channel(token)
        val ready = client.ready(token, client.draft(token, channel.id)).body<PostDto>()
        val folder = client.post("/api/v1/review/folders") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(ReviewFolderInput("Weekend"))
        }.body<ReviewFolderDto>()

        val sorted = client.patch("/api/v1/posts/${ready.id}/review") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(ReviewPatch(folder.id, listOf("evening", " Evening ", "cats")))
        }.body<PostDto>()
        assertEquals(folder.id, sorted.reviewFolderId)
        assertEquals(listOf("cats", "evening"), sorted.labels)
        assertEquals(ready.version, sorted.version)
        assertEquals(listOf(LabelDto("cats", 1), LabelDto("evening", 1)), client.get("/api/v1/labels") { bearerAuth(token) }.body<List<LabelDto>>())

        // Deleting the folder keeps the post, out of any folder.
        client.delete("/api/v1/review/folders/${folder.id}") { bearerAuth(token) }
        assertNull(client.get("/api/v1/posts/${ready.id}") { bearerAuth(token) }.body<PostDto>().reviewFolderId)
    }

    @Test
    fun `a draft cannot be sorted for review`() = test { client, token ->
        val draft = client.draft(token, null)
        val response = client.patch("/api/v1/posts/${draft.id}/review") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(ReviewPatch(labels = listOf("x")))
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
    }
}
