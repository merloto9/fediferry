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
import app.fediferry.api.PlanModes
import app.fediferry.api.PlanRequest
import app.fediferry.api.PostDto
import app.fediferry.api.PostPatch
import app.fediferry.api.PostStages
import app.fediferry.api.PublicationStates
import app.fediferry.api.ScheduleDto
import app.fediferry.api.ScheduleInput
import app.fediferry.api.SettingsEntryDto
import app.fediferry.api.SlotDto
import app.fediferry.channel.PublishJob
import app.fediferry.channel.Published
import app.fediferry.mastodon.MastodonAccounts
import app.fediferry.mastodon.MastodonException
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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
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
import java.time.Instant
import javax.imageio.ImageIO

class PublishingTest {

    private val dir: File = Files.createTempDirectory("fediferry-publishing").toFile()
    private val storage = Storage.open(File(dir, "fediferry.db"))
    /** Thursday 8 October 2026, 12:00 UTC. */
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

    /** What the publisher was asked to send, and what it does next. */
    private val sent = mutableListOf<PublishJob>()
    private var failWith: MastodonException? = null

    private val services = Services(
        storage, dir, clock = { now }, version = "test", resolvers = emptyList(), fetcher = { Result.failure(IllegalStateException()) },
        mastodon = MastodonAccounts(OkHttpClient.Builder().addInterceptor(fakeMastodon).build()),
        publisher = { job ->
            sent += job
            failWith?.let { throw it }
            Published("s${sent.size}", "https://social.example/@memes/s${sent.size}")
        },
    )

    @After fun cleanUp() {
        storage.close()
        dir.deleteRecursively()
    }

    private fun png(): ByteArray = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    private class Ctx(val client: HttpClient, val token: String, val channel: ChannelDto)

    private fun test(block: suspend ApplicationTestBuilder.(Ctx) -> Unit) = testApplication {
        application { fediferry(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val token = services.projects.create("Memes").token
        client.post("/api/v1/devices") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(DeviceRegistration("pixel", "Pixel", "android", "1")) }
        val channel = client.post("/api/v1/channels/mastodon/import") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(ImportAccountRequest("social.example", "good-token", null))
        }.body<ChannelDto>()
        block(Ctx(client, token, channel))
    }

    /** A ready post with one picture, its alt text and the hashtag #meme. */
    private suspend fun Ctx.readyPost(): PostDto {
        val item = client.submitFormWithBinaryData(
            url = "/api/v1/ingest/${java.util.UUID.randomUUID()}",
            formData = formData {
                append("file", png(), Headers.build {
                    append(HttpHeaders.ContentType, "image/png"); append(HttpHeaders.ContentDisposition, "filename=\"x.png\"")
                })
            },
        ) { method = HttpMethod.Put; bearerAuth(token); header("X-Device-Id", "pixel") }.body<IngestResult>().item.id
        val draft = client.post("/api/v1/posts") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(CreatePost(listOf(item))) }.body<PostDto>()
        client.post("/api/v1/posts/${draft.id}/lock") { bearerAuth(token); header("X-Device-Id", "pixel") }
        val edited = client.patch("/api/v1/posts/${draft.id}") {
            bearerAuth(token); header("X-Device-Id", "pixel"); header(HttpHeaders.IfMatch, draft.version.toString())
            contentType(ContentType.Application.Json)
            setBody(PostPatch(body = "Look {tags}", hashtags = listOf("#meme"), media = listOf(app.fediferry.api.PostMediaPatch(0, altText = "A black square"))))
        }.body<PostDto>()
        return client.post("/api/v1/posts/${edited.id}/ready") { bearerAuth(token); header("X-Device-Id", "pixel") }.body()
    }

    private suspend fun Ctx.plan(post: PostDto, request: PlanRequest): HttpResponse =
        client.post("/api/v1/posts/${post.id}/plan") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(request) }

    private suspend fun Ctx.get(post: PostDto): PostDto = client.get("/api/v1/posts/${post.id}") { bearerAuth(token) }.body()

    private suspend fun Ctx.schedule(slots: List<SlotDto>): ScheduleDto = client.post("/api/v1/schedules") {
        bearerAuth(token); contentType(ContentType.Application.Json)
        setBody(ScheduleInput(channelId = channel.id, name = "Evenings", timezone = "UTC", slots = slots))
    }.body()

    @Test
    fun `posts take the next free slots in turn`() = test { ctx ->
        val schedule = ctx.schedule(listOf(SlotDto((1..7).toList(), "18:00"), SlotDto(listOf(1, 2, 3, 4, 5), "09:00")))
        assertEquals(
            listOf("2026-10-08T18:00:00Z", "2026-10-09T09:00:00Z", "2026-10-09T18:00:00Z").map { Instant.parse(it).toEpochMilli() },
            schedule.nextFree,
        )
        val first = ctx.plan(ctx.readyPost(), PlanRequest(PlanModes.NEXT_SLOT)).body<PostDto>()
        val second = ctx.plan(ctx.readyPost(), PlanRequest(PlanModes.NEXT_SLOT)).body<PostDto>()
        assertEquals(PostStages.SCHEDULED, first.stage)
        assertEquals(Instant.parse("2026-10-08T18:00:00Z").toEpochMilli(), first.publication?.slotAt)
        assertEquals(Instant.parse("2026-10-09T09:00:00Z").toEpochMilli(), second.publication?.slotAt)
        assertEquals(schedule.id, second.publication?.scheduleId)

        // Moving a post to "next slot" again keeps its own slot rather than skipping it.
        assertEquals(first.publication?.slotAt, ctx.plan(first, PlanRequest(PlanModes.NEXT_SLOT)).body<PostDto>().publication?.slotAt)
    }

    @Test
    fun `without a schedule there is no next slot`() = test { ctx ->
        val response = ctx.plan(ctx.readyPost(), PlanRequest(PlanModes.NEXT_SLOT))
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("plan.no_schedule", response.body<ApiErrorBody>().code)
    }

    @Test
    fun `a post goes out once its undo window has passed`() = test { ctx ->
        val post = ctx.plan(ctx.readyPost(), PlanRequest(PlanModes.NOW, delaySeconds = 5)).body<PostDto>()
        services.publishing.tick()
        assertTrue("nothing goes out inside the undo window", sent.isEmpty())

        now += 6_000
        services.publishing.tick()
        val job = sent.single()
        assertEquals("Look #meme", job.text)
        assertEquals("good-token", job.token)
        assertEquals("public", job.visibility)
        assertEquals("A black square", job.media.single().altText)

        val out = ctx.get(post)
        assertEquals(PostStages.PUBLISHED, out.stage)
        assertEquals(PublicationStates.PUBLISHED, out.publication?.state)
        assertEquals("https://social.example/@memes/s1", out.publication?.remoteUrl)
        // Sent hashtags count as used, for every phone.
        val usage = ctx.client.get("/api/v1/settings/hashtag_usage") { bearerAuth(ctx.token) }.body<List<SettingsEntryDto>>().single()
        assertEquals("meme", usage.id)
        assertEquals(1, usage.data["uses"]!!.jsonPrimitive.int)

        services.publishing.tick()
        assertEquals("a post goes out once", 1, sent.size)
    }

    @Test
    fun `undo takes a post back to review before it goes`() = test { ctx ->
        val post = ctx.plan(ctx.readyPost(), PlanRequest(PlanModes.NOW, delaySeconds = 5)).body<PostDto>()
        val back = ctx.client.post("/api/v1/posts/${post.id}/unplan") { bearerAuth(ctx.token) }.body<PostDto>()
        assertEquals(PostStages.READY, back.stage)
        assertNull(back.publication)
        now += 10_000
        services.publishing.tick()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a busy instance is tried again later with the same key`() = test { ctx ->
        val post = ctx.plan(ctx.readyPost(), PlanRequest(PlanModes.NOW)).body<PostDto>()
        failWith = MastodonException("mastodon.http_503", 503, retryable = true)
        services.publishing.tick()
        val waiting = ctx.get(post)
        assertEquals(PostStages.SCHEDULED, waiting.stage)
        assertEquals(1, waiting.publication?.attempts)
        assertEquals("mastodon.http_503", waiting.publication?.failureCode)
        assertEquals(now + Publishing.BACKOFF_MS[0], waiting.publication?.publishAfter)

        failWith = null
        now += Publishing.BACKOFF_MS[0]
        services.publishing.tick()
        assertEquals(PostStages.PUBLISHED, ctx.get(post).stage)
        assertEquals(sent[0].idempotencyKey, sent[1].idempotencyKey)
    }

    @Test
    fun `a refused post fails at once and can be tried again`() = test { ctx ->
        val post = ctx.plan(ctx.readyPost(), PlanRequest(PlanModes.NOW)).body<PostDto>()
        failWith = MastodonException("mastodon.rejected", 422)
        services.publishing.tick()
        val failed = ctx.get(post)
        assertEquals(PostStages.FAILED, failed.stage)
        assertEquals("mastodon.rejected", failed.publication?.failureCode)

        failWith = null
        ctx.plan(failed, PlanRequest(PlanModes.NOW))
        services.publishing.tick()
        assertEquals(PostStages.PUBLISHED, ctx.get(post).stage)
    }

    @Test
    fun `a post being sent when the server stopped goes back in the queue`() = test { ctx ->
        val post = ctx.plan(ctx.readyPost(), PlanRequest(PlanModes.NOW)).body<PostDto>()
        // Simulate the crash: claimed, never finished.
        val row = storage.db.publicationQueries.due(now).executeAsOne()
        storage.db.publicationQueries.claim(now, row.project_id, row.post_id)
        services.publishing.recover()
        services.publishing.tick()
        assertEquals(PostStages.PUBLISHED, ctx.get(post).stage)
    }

    @Test
    fun `a post planned in the past is refused`() = test { ctx ->
        val response = ctx.plan(ctx.readyPost(), PlanRequest(PlanModes.AT, at = now - 3_600_000))
        assertEquals("plan.in_past", response.body<ApiErrorBody>().code)
    }

    @Test
    fun `the queue lists planned and failed posts together`() = test { ctx ->
        ctx.plan(ctx.readyPost(), PlanRequest(PlanModes.AT, at = now + 3_600_000))
        val queue = ctx.client.get("/api/v1/posts?stage=SCHEDULED,PUBLISHING,FAILED") { bearerAuth(ctx.token) }.body<List<PostDto>>()
        assertEquals(1, queue.size)
        assertEquals(HttpStatusCode.Conflict, ctx.client.delete("/api/v1/posts/${queue[0].id}") { bearerAuth(ctx.token) }.status)
    }
}
