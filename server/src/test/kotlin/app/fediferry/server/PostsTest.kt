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
import app.fediferry.api.CreatePost
import app.fediferry.api.CropRect
import app.fediferry.api.DeriveRequest
import app.fediferry.api.DeviceRegistration
import app.fediferry.api.IngestResult
import app.fediferry.api.LockDto
import app.fediferry.api.MediaAssetDto
import app.fediferry.api.PostDto
import app.fediferry.api.PostMediaPatch
import app.fediferry.api.PostPatch
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
import io.ktor.client.request.put
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

class PostsTest {

    private val dir: File = Files.createTempDirectory("fediferry-posts").toFile()
    private val storage = Storage.open(File(dir, "fediferry.db"))
    private var now = 1_800_000_000_000L
    private val services = Services(storage, dir, clock = { now }, version = "test", resolvers = emptyList(), fetcher = { Result.failure(IllegalStateException()) })

    @After fun cleanUp() {
        storage.close()
        dir.deleteRecursively()
    }

    private fun png(w: Int, h: Int): ByteArray {
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until w) for (y in 0 until h) image.setRGB(x, y, if (x < w / 2) 0xff0000 else 0x0000ff)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun test(block: suspend ApplicationTestBuilder.(HttpClient, String) -> Unit) = testApplication {
        application { fediferry(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val token = services.projects.create("Memes").token
        for ((id, name) in listOf("pixel" to "Pixel 9", "fairphone" to "Fairphone 4")) {
            client.post("/api/v1/devices") {
                bearerAuth(token); contentType(ContentType.Application.Json); setBody(DeviceRegistration(id, name, "android", "1"))
            }
        }
        // The project's template and {caption}, as a phone's sync would put them there.
        client.put("/api/v1/settings/template/t1") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(Json.decodeFromString<JsonObject>("""{"id":"t1","name":"Meme","body":"{caption}\n\n{tags}\n\nvia {link}","tags":"#meme","isDefault":true}"""))
        }
        client.put("/api/v1/settings/template/t2") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(Json.decodeFromString<JsonObject>("""{"id":"t2","name":"Plain","body":"Found at {link}","tags":""}"""))
        }
        client.put("/api/v1/settings/placeholder_key/caption") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(Json.decodeFromString<JsonObject>("""{"id":"caption","name":"caption","mappings":{"PINTEREST":"{title}"}}"""))
        }
        block(client, token)
    }

    private suspend fun HttpClient.libraryItem(token: String, link: String? = null): String = submitFormWithBinaryData(
        url = "/api/v1/ingest/${java.util.UUID.randomUUID()}",
        formData = formData {
            link?.let { append("link", it) }
            append("file", png(100, 50), Headers.build {
                append(HttpHeaders.ContentType, "image/png"); append(HttpHeaders.ContentDisposition, "filename=\"x.png\"")
            })
        },
    ) { method = HttpMethod.Put; bearerAuth(token); header("X-Device-Id", "pixel") }.body<IngestResult>().item.id

    private suspend fun HttpClient.draft(token: String, vararg items: String): PostDto = post("/api/v1/posts") {
        bearerAuth(token); header("X-Device-Id", "pixel"); contentType(ContentType.Application.Json); setBody(CreatePost(items.toList()))
    }.body()

    private suspend fun HttpClient.lock(token: String, post: String, device: String, force: Boolean = false): HttpResponse =
        post("/api/v1/posts/$post/lock${if (force) "?force=true" else ""}") { bearerAuth(token); header("X-Device-Id", device) }

    private suspend fun HttpClient.edit(token: String, post: String, device: String, version: Long, patch: PostPatch): HttpResponse =
        patch("/api/v1/posts/$post") {
            bearerAuth(token); header("X-Device-Id", device); header(HttpHeaders.IfMatch, version.toString())
            contentType(ContentType.Application.Json); setBody(patch)
        }

    @Test
    fun `a draft is written with the project's template`() = test { client, token ->
        val item = client.libraryItem(token, link = "https://www.pinterest.com/pin/1/")
        val post = client.draft(token, item)
        assertEquals("DRAFT", post.stage)
        assertEquals("t1", post.templateId)
        assertEquals("{tags}\n\nvia https://www.pinterest.com/pin/1/", post.body)
        assertEquals(listOf("#meme"), post.hashtags)
        assertEquals(1, post.media.size)
        assertEquals(100, post.media.single().asset.width)
    }

    @Test
    fun `one device edits at a time`() = test { client, token ->
        val post = client.draft(token, client.libraryItem(token))
        assertEquals(HttpStatusCode.OK, client.lock(token, post.id, "pixel").status)

        val blocked = client.lock(token, post.id, "fairphone")
        assertEquals(HttpStatusCode.Locked, blocked.status)
        assertEquals("Pixel 9", blocked.body<ApiErrorBody>().args["device"])
        assertEquals(HttpStatusCode.Locked, client.edit(token, post.id, "fairphone", post.version, PostPatch(body = "mine")).status)

        val edited = client.edit(token, post.id, "pixel", post.version, PostPatch(body = "Hello", contentWarning = "spiders")).body<PostDto>()
        assertEquals("Hello", edited.body)
        assertEquals("spiders", edited.contentWarning)
        assertEquals("pixel", edited.lock?.deviceId)

        // Taking over: the first device's next edit is refused.
        assertEquals("fairphone", client.lock(token, post.id, "fairphone", force = true).body<LockDto>().deviceId)
        assertEquals(HttpStatusCode.Locked, client.edit(token, post.id, "pixel", edited.version, PostPatch(body = "again")).status)
    }

    @Test
    fun `a lock runs out when its device goes quiet`() = test { client, token ->
        val post = client.draft(token, client.libraryItem(token))
        client.lock(token, post.id, "pixel")
        now += Posts.LOCK_TTL_MS + 1
        assertEquals(HttpStatusCode.OK, client.lock(token, post.id, "fairphone").status)
    }

    @Test
    fun `an edit made on an old copy is refused`() = test { client, token ->
        val post = client.draft(token, client.libraryItem(token))
        client.lock(token, post.id, "pixel")
        val first = client.edit(token, post.id, "pixel", post.version, PostPatch(body = "one")).body<PostDto>()
        val stale = client.edit(token, post.id, "pixel", post.version, PostPatch(body = "two"))
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals(first.version.toString(), stale.body<ApiErrorBody>().args["current"])
    }

    @Test
    fun `switching template writes the text again`() = test { client, token ->
        val post = client.draft(token, client.libraryItem(token, link = "https://example.org/x"))
        client.lock(token, post.id, "pixel")
        val switched = client.edit(token, post.id, "pixel", post.version, PostPatch(templateId = "t2", rerender = true)).body<PostDto>()
        assertEquals("Found at https://example.org/x", switched.body)
        assertTrue(switched.hashtags.isEmpty())
    }

    @Test
    fun `a cropped picture is a new asset that replaces the old one in the post`() = test { client, token ->
        val post = client.draft(token, client.libraryItem(token))
        val original = post.media.single().asset
        val cropped = client.post("/api/v1/media/${original.id}/derive") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(DeriveRequest(crop = CropRect(0f, 0f, 0.5f, 1f)))
        }.body<MediaAssetDto>()
        assertEquals(50, cropped.width)
        assertEquals(50, cropped.height)
        assertEquals(original.id, cropped.parentId)

        client.lock(token, post.id, "pixel")
        val updated = client.edit(token, post.id, "pixel", post.version, PostPatch(media = listOf(PostMediaPatch(0, altText = "Red", assetId = cropped.id)))).body<PostDto>()
        assertEquals(cropped.id, updated.media.single().asset.id)
        assertEquals("Red", updated.media.single().altText)
    }

    @Test
    fun `a draft is deleted unless another device is editing it`() = test { client, token ->
        val post = client.draft(token, client.libraryItem(token))
        client.lock(token, post.id, "fairphone")
        assertEquals(HttpStatusCode.Locked, client.delete("/api/v1/posts/${post.id}") { bearerAuth(token); header("X-Device-Id", "pixel") }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/posts/${post.id}") { bearerAuth(token); header("X-Device-Id", "fairphone") }.status)
        assertTrue(client.get("/api/v1/posts?stage=DRAFT") { bearerAuth(token) }.body<List<PostDto>>().isEmpty())
    }

    @Test
    fun `alt text needs a model`() = test { client, token ->
        val post = client.draft(token, client.libraryItem(token))
        val response = client.post("/api/v1/posts/${post.id}/media/0/alt-suggestion") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("alt.no_model", response.body<ApiErrorBody>().code)
    }

    @Test
    fun `another project cannot see a post`() = test { client, token ->
        val post = client.draft(token, client.libraryItem(token))
        val other = services.projects.create("Other").token
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/posts/${post.id}") { bearerAuth(other) }.status)
    }
}
