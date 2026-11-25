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
import app.fediferry.api.InsightsPage
import app.fediferry.api.InteractionsAccess
import app.fediferry.api.PostStatsDto
import app.fediferry.channel.AccountCounts
import app.fediferry.channel.ChannelAccess
import app.fediferry.channel.ChannelInsights
import app.fediferry.channel.ChannelReading
import app.fediferry.channel.Published
import app.fediferry.mastodon.MastodonAccounts
import app.fediferry.mastodon.MastodonException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

class InsightsTest {

    private val dir: File = Files.createTempDirectory("fediferry-insights").toFile()
    private val storage = Storage.open(File(dir, "fediferry.db"))
    private var now = Instant.parse("2026-11-26T12:00:00Z").toEpochMilli()

    private val fakeMastodon = Interceptor { chain ->
        val request = chain.request()
        val body = when (request.url.encodedPath) {
            "/api/v1/accounts/verify_credentials" -> """{"id":"77","acct":"memes","display_name":"Memes"}"""
            else -> "{}"
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("ok")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }

    /** The platform as Analyze sees it: what it was asked, and what it answers. */
    private var followers = 120
    private val countsAsked = mutableListOf<String>()
    private val readsAsked = mutableListOf<Pair<String, Long>>()
    private var failing: String? = null

    private val reader = object : ChannelInsights {
        override suspend fun counts(access: ChannelAccess): AccountCounts {
            countsAsked += access.instance
            return AccountCounts(followers, 30, 412)
        }

        override suspend fun read(access: ChannelAccess, since: Long): ChannelReading {
            readsAsked += access.token to since
            if (access.instance == failing) throw MastodonException("mastodon.unreachable", retryable = true)
            return ChannelReading(
                AccountCounts(followers, 30, 412),
                recent = listOf(PostStatsDto(id = "1", createdAt = now - 1000, text = "Hi", favourites = 3)),
                interactions = emptyList(),
                interactionsAccess = InteractionsAccess.RECONNECT,
            )
        }
    }

    private val services = Services(
        storage, dir, clock = { now }, version = "test", resolvers = emptyList(), fetcher = { Result.failure(IllegalStateException()) },
        mastodon = MastodonAccounts(OkHttpClient.Builder().addInterceptor(fakeMastodon).build()),
        publisher = { Published("s", null) },
        insightsReader = { reader },
    )

    @After fun cleanUp() {
        storage.close()
        dir.deleteRecursively()
    }

    private class Ctx(val client: HttpClient, val token: String, val projectId: String)

    private fun test(block: suspend ApplicationTestBuilder.(Ctx) -> Unit) = testApplication {
        application { fediferry(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val created = services.projects.create("Memes")
        block(Ctx(client, created.token, created.project.id))
    }

    private suspend fun Ctx.channel(instance: String): ChannelDto = client.post("/api/v1/channels/mastodon/import") {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody(ImportAccountRequest(instance, "token-$instance", null))
    }.body()

    private suspend fun Ctx.insights(days: Int? = null): InsightsPage =
        client.get("/api/v1/insights" + (days?.let { "?days=$it" } ?: "")) { bearerAuth(token) }.body()

    @Test
    fun `every channel is read through its platform, and today's count is kept`() = test { ctx ->
        val channel = ctx.channel("social.example")
        val page = ctx.insights(days = 7)

        assertEquals(now - 7 * 86_400_000L, page.since)
        assertEquals(listOf("token-social.example" to page.since), readsAsked)
        val read = page.channels.single()
        assertEquals(channel.id, read.channelId)
        assertEquals(120, read.followers)
        assertEquals(3, read.recent.single().engagement)
        assertEquals(InteractionsAccess.RECONNECT, read.interactionsAccess)
        assertEquals(listOf("2026-11-26" to 120), read.followerHistory.map { it.day to it.followers })
    }

    @Test
    fun `the period is 30 days unless asked, and never more than 90`() = test { ctx ->
        ctx.channel("social.example")
        assertEquals(now - 30 * 86_400_000L, ctx.insights().since)
        assertEquals(now - 90 * 86_400_000L, ctx.insights(days = 400).since)
    }

    @Test
    fun `a channel that cannot be read is named, the others still come`() = test { ctx ->
        val good = ctx.channel("social.example")
        val bad = ctx.channel("down.example")
        failing = "down.example"
        val page = ctx.insights()
        assertEquals(listOf(good.id), page.channels.map { it.channelId })
        assertEquals(listOf(bad.id), page.unreachable)
    }

    @Test
    fun `the daily count is noted once a day and builds the history`() = test { ctx ->
        ctx.channel("social.example")
        services.insights.recordToday(ctx.projectId)
        services.insights.recordToday(ctx.projectId)
        assertEquals("one count a day, not one an hour", 1, countsAsked.size)

        now += 86_400_000L
        followers = 125
        services.insights.recordToday(ctx.projectId)
        assertEquals(
            listOf("2026-11-26" to 120, "2026-11-27" to 125),
            ctx.insights().channels.single().followerHistory.map { it.day to it.followers },
        )
    }

    @Test
    fun `a channel that goes takes its counts along`() = test { ctx ->
        val channel = ctx.channel("social.example")
        services.insights.recordToday(ctx.projectId)
        // A channel id holds a slash (instance/account), so it goes into the path encoded, as the phone sends it.
        val deleted = ctx.client.delete("/api/v1/channels/${channel.id.encodeURLPathPart()}") { bearerAuth(ctx.token) }
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertTrue(storage.db.followerCountQueries.since(ctx.projectId, channel.id, "2000-01-01").executeAsList().isEmpty())
    }

    @Test
    fun `days are UTC dates`() {
        assertEquals("2026-11-26", Insights.day(Instant.parse("2026-11-26T23:59:00Z").toEpochMilli()))
        assertEquals("2026-11-27", Insights.day(Instant.parse("2026-11-27T00:00:00Z").toEpochMilli()))
    }
}
