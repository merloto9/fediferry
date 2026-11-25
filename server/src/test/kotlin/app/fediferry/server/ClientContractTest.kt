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
import app.fediferry.api.Features
import app.fediferry.api.IngestModes
import app.fediferry.api.PlanModes
import app.fediferry.api.PlanRequest
import app.fediferry.api.PostPatch
import app.fediferry.api.PostStages
import app.fediferry.api.SettingsKinds
import app.fediferry.channel.PublishJob
import app.fediferry.channel.Published
import app.fediferry.client.ServerClient
import app.fediferry.client.ServerException
import app.fediferry.mastodon.MastodonAccounts
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO

/**
 * The phone's [ServerClient] against the real server over HTTP, the way a
 * phone reaches it: paths, query strings, headers, encodings, multipart
 * bodies and error codes have to agree on both sides. The server's own tests
 * call raw URLs, so a client that asks for the wrong thing passes them.
 *
 * Only Mastodon is faked; everything between the phone and the server is real.
 */
class ClientContractTest {

    private val dir: File = Files.createTempDirectory("fediferry-contract").toFile()
    private val storage = Storage.open(File(dir, "fediferry.db"))
    private var now = Instant.parse("2026-10-08T12:00:00Z").toEpochMilli()

    private val fakeMastodon = Interceptor { chain ->
        val request = chain.request()
        val body = when (request.url.encodedPath) {
            "/api/v1/accounts/verify_credentials" -> """{"id":"77","acct":"memes","display_name":"Memes"}"""
            else -> "{}"
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("ok")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }

    private val sent = mutableListOf<PublishJob>()

    private val services = Services(
        storage, dir, clock = { now }, version = "test", resolvers = emptyList(), fetcher = { Result.failure(IllegalStateException()) },
        mastodon = MastodonAccounts(OkHttpClient.Builder().addInterceptor(fakeMastodon).build()),
        publisher = { job ->
            sent += job
            Published("s${sent.size}", "https://social.example/@memes/s${sent.size}")
        },
    )

    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private lateinit var baseUrl: String
    private lateinit var token: String
    private lateinit var client: ServerClient
    private val http = OkHttpClient()

    @Before fun start() {
        server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { fediferry(services) }.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        // Written the way a person types it into the app: the client adds the scheme's rest.
        baseUrl = "http://127.0.0.1:$port"
        token = services.projects.create("Memes").token
        client = ServerClient(baseUrl, token, deviceId = "pixel", http)
    }

    @After fun stop() {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        storage.close()
        dir.deleteRecursively()
    }

    private var shares = 0

    private fun png(): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(20 + shares++, 20, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    private suspend fun expectError(status: Int, code: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $status $code")
        } catch (e: ServerException) {
            assertEquals(status, e.status)
            assertEquals(code, e.code)
        }
    }

    // --- connecting ------------------------------------------------------------

    @Test
    fun `a phone connects, with health, project and device`() = runBlocking {
        val health = client.health()
        assertEquals("fediferry", health.service)
        assertTrue(Features.POST_IMPORT in health.features)

        assertEquals("Memes", client.project().name)
        val device = client.registerDevice(DeviceRegistration("pixel", "Pixel", "android", "0.24.0"))
        assertEquals("pixel", device.id)
    }

    @Test
    fun `a wrong token is refused, health still answers`() = runBlocking {
        val stranger = ServerClient(baseUrl, "not-a-token", "pixel", http)
        assertEquals("fediferry", stranger.health().service)
        try {
            stranger.project()
            fail("a wrong token must not reach the project")
        } catch (e: ServerException) {
            assertEquals(401, e.status)
        }
    }

    // --- settings ----------------------------------------------------------------

    @Test
    fun `settings go up, come back and go away, ids with a hash included`() = runBlocking {
        client.putSetting(SettingsKinds.HASHTAG, "#Überraschung", buildJsonObject { put("tag", "#Überraschung"); put("sortOrder", 0) })
        val list = client.settings(SettingsKinds.HASHTAG)
        assertEquals(listOf("#Überraschung"), list.map { it.id })
        assertEquals(0, list.single().data["sortOrder"]!!.jsonPrimitive.int)

        client.deleteSetting(SettingsKinds.HASHTAG, "#Überraschung")
        assertTrue(client.settings(SettingsKinds.HASHTAG).isEmpty())
    }

    @Test
    fun `every kind the phone syncs is one the server knows`() = runBlocking {
        for (kind in SettingsKinds.ALL) client.settings(kind)
    }

    /** ProjectSync skips a kind on exactly this answer, so an older server keeps working. */
    @Test
    fun `an unknown kind is refused with the code the phone looks for`() = runBlocking {
        expectError(404, "settings.unknown_kind") { client.settings("from_the_future") }
    }

    @Test
    fun `a settings change shows in the change feed`() = runBlocking {
        val rev = client.project().rev
        client.putSetting(SettingsKinds.HASHTAG, "#meme", buildJsonObject { put("tag", "#meme"); put("sortOrder", 0) })
        val changes = client.changes(rev, waitSeconds = 0)
        assertTrue(changes.rev > rev)
        assertTrue(changes.changed.any { it.type == SettingsKinds.changeType(SettingsKinds.HASHTAG) && it.id == "#meme" })
    }

    @Test
    fun `an AI key goes up and only its name comes back`() = runBlocking {
        client.putSecret("ai-model:m1", "sk-secret")
        client.deleteSecret("ai-model:m1")
    }

    // --- media -------------------------------------------------------------------

    @Test
    fun `a picture goes up once and is found by its hash`() = runBlocking {
        val bytes = png()
        val asset = client.uploadMedia(bytes, "image/png")
        assertEquals(asset.id, client.mediaBySha(asset.sha256)?.id)
        assertNull(client.mediaBySha("0".repeat(64)))

        // The address the phone's image loader is given, with the token it sends.
        val response = http.newCall(okhttp3.Request.Builder().url(client.mediaUrl(asset.id)).header("Authorization", "Bearer $token").build()).execute()
        response.use { assertEquals(200, it.code); assertEquals(bytes.size, it.body.bytes().size) }
    }

    // --- a share's way out ---------------------------------------------------------

    /** Share, edit, ready, plan, out: what a phone with a server does all day. */
    @Test
    fun `a share becomes a draft, is edited, made ready and goes out`() = runBlocking {
        client.registerDevice(DeviceRegistration("pixel", "Pixel", "android", "0.24.0"))
        val channel = client.importMastodon("social.example", "good-token", null)
        assertEquals(listOf(channel.id), client.channels().map { it.id })

        val shareId = UUID.randomUUID().toString()
        val picture = png()
        val ingested = client.ingest(shareId, IngestModes.COMPOSE, link = null, text = null, file = picture, mime = "image/png", capturedAt = now)
        val draft = ingested.post ?: error("a share with a picture becomes a draft")
        assertEquals(PostStages.DRAFT, draft.stage)
        // The phone retries an upload it is not sure of: the same share id, the same draft.
        val again = client.ingest(shareId, IngestModes.COMPOSE, link = null, text = null, file = picture, mime = "image/png", capturedAt = now)
        assertTrue(again.replayed)
        assertEquals(draft.id, again.post?.id)

        client.lockPost(draft.id)
        val edited = client.patchPost(
            draft.id, draft.version,
            PostPatch(channelId = channel.id, body = "Look {tags}", hashtags = listOf("#meme"), media = listOf(app.fediferry.api.PostMediaPatch(0, altText = "A black square"))),
        )
        // An edit made on an older version is refused, with the version to start again from.
        try {
            client.patchPost(draft.id, draft.version, PostPatch(body = "stale"))
            fail("a stale edit must be refused")
        } catch (e: ServerException) {
            assertEquals(409, e.status)
            assertEquals("post.version_conflict", e.code)
            assertEquals(edited.version.toString(), e.args["current"])
        }

        val ready = client.markReady(edited.id, edited.version)
        assertEquals(PostStages.READY, ready.stage)
        assertEquals(listOf(ready.id), client.posts(PostStages.READY).map { it.id })

        val planned = client.plan(ready.id, PlanRequest(PlanModes.NOW))
        assertNotNull(planned.publication)
        services.publishing.tick()

        assertEquals("Look #meme", sent.single().text)
        assertEquals("A black square", sent.single().media.single().altText)
        // Out: the server let go of it, and the phone is told so with a 404.
        try {
            client.post(ready.id)
            fail("a published post is not kept")
        } catch (e: ServerException) {
            assertEquals(404, e.status)
        }
        assertEquals(1, client.settings(SettingsKinds.HASHTAG_USAGE).single { it.id == "meme" }.data["uses"]!!.jsonPrimitive.int)
    }

    @Test
    fun `undo takes a planned post back before it goes`() = runBlocking {
        val channel = client.importMastodon("social.example", "good-token", null)
        val draft = client.ingest(UUID.randomUUID().toString(), IngestModes.COMPOSE, null, null, png(), "image/png", now).post!!
        client.lockPost(draft.id)
        val edited = client.patchPost(draft.id, draft.version, PostPatch(channelId = channel.id, body = "Undo me", media = listOf(app.fediferry.api.PostMediaPatch(0, altText = "x"))))
        val ready = client.markReady(edited.id, edited.version)

        client.plan(ready.id, PlanRequest(PlanModes.NOW, delaySeconds = 5))
        val back = client.unplan(ready.id)
        assertEquals(PostStages.READY, back.stage)
        now += 10_000
        services.publishing.tick()
        assertTrue(sent.isEmpty())
        assertFalse(client.posts(PostStages.READY).isEmpty())
    }
}
