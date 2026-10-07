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

import app.fediferry.api.DeviceRegistration
import app.fediferry.api.FolderDto
import app.fediferry.api.FolderInput
import app.fediferry.api.IngestResult
import app.fediferry.api.LibraryItemDto
import app.fediferry.api.LibraryItemPatch
import app.fediferry.api.LibraryKinds
import app.fediferry.api.LibraryPage
import app.fediferry.api.TagDto
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
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

class LibraryTest {

    private val dir: File = Files.createTempDirectory("fediferry-lib").toFile()
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
    fun `a shared screenshot becomes a picture in the library`() = test { client, token ->
        val picture = png(120, 80, 0x336699)
        val response = client.share(token, "s1", picture = picture)
        assertEquals(HttpStatusCode.Created, response.status)
        val item = response.body<IngestResult>().item

        assertEquals(LibraryKinds.IMAGE, item.kind)
        assertEquals(120, item.asset!!.width)
        assertEquals(80, item.asset!!.height)
        assertEquals("phone", item.deviceId)

        val bytes = client.get("/api/v1/media/${item.asset!!.id}") { bearerAuth(token) }.readRawBytes()
        assertArrayEquals(picture, bytes)
        val thumb = client.get("/api/v1/media/${item.asset!!.id}/thumb?w=64") { bearerAuth(token) }
        assertEquals(ContentType.Image.JPEG, thumb.headers[HttpHeaders.ContentType]?.let(ContentType::parse))
        assertEquals(64, ImageIO.read(thumb.readRawBytes().inputStream()).width)
    }

    @Test
    fun `sending the same share twice keeps one item`() = test { client, token ->
        val first = client.share(token, "s1", picture = png(10, 10, 1)).body<IngestResult>()
        val again = client.share(token, "s1", picture = png(10, 10, 1))
        assertEquals(HttpStatusCode.OK, again.status)
        assertTrue(again.body<IngestResult>().replayed)
        assertEquals(first.item.id, again.body<IngestResult>().item.id)
        assertEquals(1, items(client, token).size)
    }

    @Test
    fun `the same picture shared again is the item already there`() = test { client, token ->
        val first = client.share(token, "s1", picture = png(10, 10, 7)).body<IngestResult>()
        val second = client.share(token, "s2", picture = png(10, 10, 7)).body<IngestResult>()
        assertTrue(second.duplicate)
        assertEquals(first.item.id, second.item.id)
        assertEquals(1, items(client, token).size)
    }

    @Test
    fun `a link and its screenshot from one phone become one item`() = test { client, token ->
        val link = client.share(token, "l1", link = "https://pin.it/abc").body<IngestResult>()
        assertEquals(LibraryKinds.LINK, link.item.kind)

        now += 3 * 60_000
        val shot = client.share(token, "p1", picture = png(20, 20, 9)).body<IngestResult>()
        assertTrue(shot.paired)
        assertEquals(link.item.id, shot.item.id)
        assertEquals(LibraryKinds.IMAGE, shot.item.kind)
        assertEquals("https://pin.it/abc", shot.item.sourceUrl)
        assertEquals(1, items(client, token).size)
    }

    @Test
    fun `pairing needs the same phone and the time window`() = test { client, token ->
        client.share(token, "l1", device = "phone", link = "https://pin.it/abc")
        // Another phone's screenshot is its own item.
        assertFalse(client.share(token, "p1", device = "tablet", picture = png(20, 20, 1)).body<IngestResult>().paired)
        // So is one that comes too late.
        now += Library.PAIRING_WINDOW_MS + 1
        assertFalse(client.share(token, "p2", device = "phone", picture = png(20, 20, 2)).body<IngestResult>().paired)
        assertEquals(3, items(client, token).size)
    }

    @Test
    fun `a link is cleaned and its picture fetched on the server`() = test { client, token ->
        val result = client.share(token, "l1", text = "Look https://pinterest.com/pin/1/resolvable?sender=42 nice").body<IngestResult>()
        assertTrue(result.resolved)
        assertEquals(LibraryKinds.IMAGE, result.item.kind)
        assertEquals("https://pinterest.com/pin/1/resolvable", result.item.sourceUrl)
        assertEquals("PINTEREST", result.item.origin)
        assertEquals("A cat", result.item.title)
        assertEquals(40, result.item.asset!!.width)
        assertFalse(result.item.linkMayIdentify)
        assertEquals("Look https://pinterest.com/pin/1/resolvable?sender=42 nice", result.item.text)
    }

    @Test
    fun `a link that cannot be cleaned is kept and flagged`() = test { client, token ->
        val item = client.share(token, "l1", link = "https://pin.it/unsure").body<IngestResult>().item
        assertEquals("https://pin.it/unsure", item.sourceUrl)
        assertTrue(item.linkMayIdentify)
    }

    @Test
    fun `folders and tags organise the library`() = test { client, token ->
        val a = client.share(token, "a", picture = png(5, 5, 1)).body<IngestResult>().item
        val b = client.share(token, "b", picture = png(5, 5, 2)).body<IngestResult>().item
        val folder = client.post("/api/v1/library/folders") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(FolderInput("Weekend"))
        }.body<FolderDto>()

        client.patch("/api/v1/library/items/${a.id}") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(LibraryItemPatch(folderId = folder.id, tags = listOf("#cats", "Funny", "cats")))
        }
        val moved = client.get("/api/v1/library/items/${a.id}") { bearerAuth(token) }.body<LibraryItemDto>()
        assertEquals(folder.id, moved.folderId)
        assertEquals(listOf("cats", "Funny"), moved.tags)

        assertEquals(listOf(a.id), items(client, token, "folder=${folder.id}").map { it.id })
        assertEquals(listOf(b.id), items(client, token, "unsorted=true").map { it.id })
        assertEquals(listOf(a.id), items(client, token, "tag=CATS").map { it.id })
        assertEquals(listOf("cats" to 1, "Funny" to 1), client.get("/api/v1/tags") { bearerAuth(token) }.body<List<TagDto>>().map { it.name to it.uses })

        // Deleting the folder keeps what was in it.
        client.delete("/api/v1/library/folders/${folder.id}") { bearerAuth(token) }
        assertEquals(2, items(client, token, "unsorted=true").size)
    }

    @Test
    fun `a deleted item leaves the library`() = test { client, token ->
        val a = client.share(token, "a", picture = png(5, 5, 1)).body<IngestResult>().item
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/library/items/${a.id}") { bearerAuth(token) }.status)
        assertTrue(items(client, token).isEmpty())
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/library/items/${a.id}") { bearerAuth(token) }.status)
    }

    @Test
    fun `another project cannot see the media`() = test { client, token ->
        val item = client.share(token, "a", picture = png(5, 5, 1)).body<IngestResult>().item
        val other = services.projects.create("Other").token
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/media/${item.asset!!.id}") { bearerAuth(other) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/library/items/${item.id}") { bearerAuth(other) }.status)
        assertTrue(items(client, other).isEmpty())
    }

    @Test
    fun `the timeline pages backwards`() = test { client, token ->
        repeat(5) { i ->
            now += 1000
            client.share(token, "s$i", picture = png(5, 5, i + 10))
        }
        val first = client.get("/api/v1/library/items?limit=2") { bearerAuth(token) }.body<LibraryPage>()
        assertEquals(2, first.items.size)
        val second = client.get("/api/v1/library/items?limit=2&before=${first.nextBefore}") { bearerAuth(token) }.body<LibraryPage>()
        assertEquals(2, second.items.size)
        assertTrue(second.items.first().capturedAt < first.items.last().capturedAt)
        assertNull(client.get("/api/v1/library/items?limit=10") { bearerAuth(token) }.body<LibraryPage>().nextBefore)
    }

    private suspend fun items(client: HttpClient, token: String, query: String = ""): List<LibraryItemDto> =
        client.get("/api/v1/library/items?$query") { bearerAuth(token) }.body<LibraryPage>().items
}
