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

import app.fediferry.api.IngestResult
import app.fediferry.api.PostDto
import app.fediferry.api.PostStages
import app.fediferry.data.model.ContentSource
import app.fediferry.link.CleanedLink
import app.fediferry.link.LinkResolver
import app.fediferry.link.ResolvedPost
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

class IntakeTest {

    private val dir: File = Files.createTempDirectory("fediferry-intake").toFile()
    private val storage = Storage.open(File(dir, "fediferry.db"))
    private var now = 1_800_000_000_000L

    /** A stand-in for Pinterest: one link that names its sharer, one that resolves to a picture. */
    private val fakePinterest = object : LinkResolver {
        override val source = ContentSource.PINTEREST
        override val cleansLinks = true
        override fun handles(url: String) = "pin" in url
        override suspend fun cleanLink(url: String): CleanedLink = when {
            "sender=" in url -> CleanedLink.Clean(url.substringBefore('?'))
            "unsure" in url -> CleanedLink.MayIdentify("test")
            else -> CleanedLink.Unchanged
        }
        override suspend fun resolve(url: String): Result<ResolvedPost> =
            if ("resolvable" in url) Result.success(ResolvedPost("https://i.example/cat.png", "image/png", mapOf("title" to "A cat")))
            else Result.failure(IllegalStateException("no picture"))
    }

    private val services = Services(
        storage, dir, clock = { now }, version = "test",
        resolvers = listOf(fakePinterest),
        fetcher = { url -> if (url == "https://i.example/cat.png") Result.success(png(40, 30, 0xff8800)) else Result.failure(IllegalStateException()) },
    )

    @After fun cleanUp() {
        storage.close()
        dir.deleteRecursively()
    }

    private fun png(w: Int, h: Int, rgb: Int): ByteArray {
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until w) for (y in 0 until h) image.setRGB(x, y, rgb)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun test(block: suspend ApplicationTestBuilder.(HttpClient, String) -> Unit) = testApplication {
        application { fediferry(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val token = services.projects.create("Memes").token
        block(client, token)
    }

    private suspend fun HttpClient.share(
        token: String,
        shareId: String,
        device: String = "phone",
        link: String? = null,
        text: String? = null,
        picture: ByteArray? = null,
    ): HttpResponse = submitFormWithBinaryData(
        url = "/api/v1/ingest/$shareId",
        formData = formData {
            link?.let { append("link", it) }
            text?.let { append("text", it) }
            append("mode", "SAVE")
            picture?.let {
                append("file", it, Headers.build {
                    append(HttpHeaders.ContentType, "image/png")
                    append(HttpHeaders.ContentDisposition, "filename=\"shot.png\"")
                })
            }
        },
    ) {
        method = HttpMethod.Put
        bearerAuth(token)
        header("X-Device-Id", device)
    }

    @Test
    fun `a shared screenshot becomes a draft with its picture`() = test { client, token ->
        val picture = png(120, 80, 0x336699)
        val response = client.share(token, "s1", picture = picture)
        assertEquals(HttpStatusCode.Created, response.status)
        val post = response.body<IngestResult>().post!!

        assertEquals(PostStages.DRAFT, post.stage)
        val asset = post.media.single().asset
        assertEquals(120, asset.width)
        assertEquals(80, asset.height)

        val bytes = client.get("/api/v1/media/${asset.id}") { bearerAuth(token) }.readRawBytes()
        assertArrayEquals(picture, bytes)
        val thumb = client.get("/api/v1/media/${asset.id}/thumb?w=64") { bearerAuth(token) }
        assertEquals(ContentType.Image.JPEG, thumb.headers[HttpHeaders.ContentType]?.let(ContentType::parse))
        assertEquals(64, ImageIO.read(thumb.readRawBytes().inputStream()).width)
        assertEquals(listOf(post.id), drafts(client, token).map { it.id })
    }

    @Test
    fun `sending the same share twice keeps one draft`() = test { client, token ->
        val first = client.share(token, "s1", picture = png(10, 10, 1)).body<IngestResult>()
        val again = client.share(token, "s1", picture = png(10, 10, 1))
        assertEquals(HttpStatusCode.OK, again.status)
        assertTrue(again.body<IngestResult>().replayed)
        assertEquals(first.post!!.id, again.body<IngestResult>().post!!.id)
        assertEquals(1, drafts(client, token).size)
    }

    @Test
    fun `a share repeated after its draft is gone says so`() = test { client, token ->
        val first = client.share(token, "s1", picture = png(10, 10, 1)).body<IngestResult>().post!!
        client.delete("/api/v1/posts/${first.id}") { bearerAuth(token); header("X-Device-Id", "phone") }
        // The record went with the draft, so the share is taken in afresh.
        val again = client.share(token, "s1", picture = png(10, 10, 1)).body<IngestResult>()
        assertFalse(again.replayed)
        assertEquals(1, drafts(client, token).size)
    }

    @Test
    fun `the same picture shared again is the draft already there`() = test { client, token ->
        val first = client.share(token, "s1", picture = png(10, 10, 7)).body<IngestResult>()
        val second = client.share(token, "s2", picture = png(10, 10, 7)).body<IngestResult>()
        assertTrue(second.duplicate)
        assertEquals(first.post!!.id, second.post!!.id)
        assertEquals(1, drafts(client, token).size)
    }

    @Test
    fun `the same picture with a link is still the draft already there, now with the link`() = test { client, token ->
        val first = client.share(token, "s1", picture = png(10, 10, 8)).body<IngestResult>()
        val second = client.share(token, "s2", device = "tablet", link = "https://pin.it/abc", picture = png(10, 10, 8)).body<IngestResult>()
        assertTrue(second.duplicate)
        assertEquals(first.post!!.id, second.post!!.id)
        assertEquals("https://pin.it/abc", second.post!!.sourceUrl)
        assertEquals(1, drafts(client, token).size)
    }

    @Test
    fun `a link and its screenshot from one phone become one draft`() = test { client, token ->
        val link = client.share(token, "l1", link = "https://pin.it/abc").body<IngestResult>().post!!
        assertTrue(link.media.isEmpty())

        now += 3 * 60_000
        val shot = client.share(token, "p1", picture = png(20, 20, 9)).body<IngestResult>()
        assertTrue(shot.paired)
        assertEquals(link.id, shot.post!!.id)
        assertEquals(1, shot.post!!.media.size)
        assertEquals("https://pin.it/abc", shot.post!!.sourceUrl)
        assertEquals(1, drafts(client, token).size)
    }

    @Test
    fun `a screenshot and then its link make one draft whose text gains the link`() = test { client, token ->
        val shot = client.share(token, "p1", picture = png(20, 20, 3)).body<IngestResult>().post!!
        now += 60_000
        val link = client.share(token, "l1", link = "https://pin.it/abc").body<IngestResult>()
        assertTrue(link.paired)
        assertEquals(shot.id, link.post!!.id)
        assertEquals("https://pin.it/abc", link.post!!.sourceUrl)
        assertTrue("https://pin.it/abc" in link.post!!.body)
    }

    @Test
    fun `pairing needs the same phone and the time window`() = test { client, token ->
        client.share(token, "l1", device = "phone", link = "https://pin.it/abc")
        // Another phone's screenshot is its own draft.
        assertFalse(client.share(token, "p1", device = "tablet", picture = png(20, 20, 1)).body<IngestResult>().paired)
        // So is one that comes too late.
        now += Intake.PAIRING_WINDOW_MS + 1
        assertFalse(client.share(token, "p2", device = "phone", picture = png(20, 20, 2)).body<IngestResult>().paired)
        assertEquals(3, drafts(client, token).size)
    }

    @Test
    fun `a link is cleaned and its picture fetched on the server`() = test { client, token ->
        val result = client.share(token, "l1", text = "Look https://pinterest.com/pin/1/resolvable?sender=42 nice").body<IngestResult>()
        assertTrue(result.resolved)
        val post = result.post!!
        assertEquals("https://pinterest.com/pin/1/resolvable", post.sourceUrl)
        assertEquals("PINTEREST", post.origin)
        assertEquals("A cat", post.sourceFields["title"])
        assertEquals(40, post.media.single().asset.width)
        assertFalse(post.linkMayIdentify)
    }

    @Test
    fun `a link that cannot be cleaned is kept and flagged`() = test { client, token ->
        val post = client.share(token, "l1", link = "https://pin.it/unsure").body<IngestResult>().post!!
        assertEquals("https://pin.it/unsure", post.sourceUrl)
        assertTrue(post.linkMayIdentify)
    }

    @Test
    fun `a text on its own becomes the draft's text`() = test { client, token ->
        val post = client.share(token, "t1", text = "Just words").body<IngestResult>().post!!
        assertEquals("Just words", post.body)
        assertTrue(post.media.isEmpty())
    }

    @Test
    fun `a deleted draft takes its picture with it`() = test { client, token ->
        val post = client.share(token, "a", picture = png(5, 5, 1)).body<IngestResult>().post!!
        val asset = post.media.single().asset
        val file = services.media.file(projectId(), asset.sha256)
        client.get("/api/v1/media/${asset.id}/thumb?w=64") { bearerAuth(token) }
        assertTrue(file.exists())

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/posts/${post.id}") { bearerAuth(token); header("X-Device-Id", "phone") }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/posts/${post.id}") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/media/${asset.id}") { bearerAuth(token) }.status)
        assertFalse(file.exists())
        assertTrue(File(dir, "thumbs").walk().none { it.isFile })
    }

    @Test
    fun `a picture another draft still uses stays`() = test { client, token ->
        val a = client.share(token, "a", picture = png(5, 5, 1)).body<IngestResult>().post!!
        val asset = a.media.single().asset
        // A second draft on the same file, as an older server's library could leave behind.
        storage.db.postQueries.insert(projectId(), "b", "DRAFT", null, "", "", 1, null, "PUBLIC", null, null, "{}", 0, null, now, now)
        storage.db.postMediaQueries.insert(projectId(), "b", 0, asset.id, null, 0)

        client.delete("/api/v1/posts/${a.id}") { bearerAuth(token); header("X-Device-Id", "phone") }
        assertNotNull(services.media.byId(projectId(), asset.id))
        client.delete("/api/v1/posts/b") { bearerAuth(token); header("X-Device-Id", "phone") }
        assertNull(services.media.byId(projectId(), asset.id))
    }

    @Test
    fun `a file on no post is swept once its grace is over`() = test { _, _ ->
        val loose = services.media.store(projectId(), png(7, 7, 4), "image/png")
        assertEquals(0, services.media.sweep(projectId()))
        now += MediaStore.SWEEP_GRACE_MS + 1
        assertEquals(1, services.media.sweep(projectId()))
        assertNull(services.media.byId(projectId(), loose.id))
        assertFalse(services.media.file(projectId(), loose.sha256).exists())
    }

    @Test
    fun `another project cannot see the media`() = test { client, token ->
        val post = client.share(token, "a", picture = png(5, 5, 1)).body<IngestResult>().post!!
        val other = services.projects.create("Other").token
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/media/${post.media.single().asset.id}") { bearerAuth(other) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/posts/${post.id}") { bearerAuth(other) }.status)
        assertTrue(drafts(client, other).isEmpty())
    }

    private fun projectId(): String = storage.db.projectQueries.all().executeAsList().first { it.name == "Memes" }.id

    private suspend fun drafts(client: HttpClient, token: String): List<PostDto> =
        client.get("/api/v1/posts?stage=DRAFT") { bearerAuth(token) }.body()
}
