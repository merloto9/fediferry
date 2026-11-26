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
package app.fediferry

import app.fediferry.api.InteractionKind
import app.fediferry.api.InteractionsAccess
import app.fediferry.channel.ChannelAccess
import app.fediferry.mastodon.MastodonException
import app.fediferry.mastodon.MastodonInsights
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class MastodonInsightsTest {

    private val seen = mutableListOf<Request>()
    private var notificationsStatus = 200
    private var credentialsStatus = 200

    /** Mastodon as far as Analyze reads it: one account, three posts, four notifications. */
    private val instance = Interceptor { chain ->
        val request = chain.request()
        seen += request
        val (code, body) = when (request.url.encodedPath) {
            "/api/v1/accounts/verify_credentials" ->
                credentialsStatus to """{"id":"77","followers_count":120,"following_count":30,"statuses_count":412}"""
            "/api/v1/accounts/77/statuses" -> 200 to """[
                {"id":"3","created_at":"2026-11-25T18:00:00.000Z","url":"https://m.social/@me/3","content":"<p>New <a href=\"x\">#Meme</a></p>",
                 "media_attachments":[{"id":"m","preview_url":"https://files/p.png","type":"image"}],
                 "tags":[{"name":"Meme"}],"favourites_count":10,"reblogs_count":4,"replies_count":2,"quotes_count":1},
                {"id":"2","created_at":"2026-11-20T09:00:00.000Z","content":"<p>Older</p>","favourites_count":3,"reblogs_count":0,"replies_count":0},
                {"id":"1","created_at":"2026-10-01T09:00:00.000Z","content":"<p>Too old</p>","favourites_count":99}
            ]"""
            "/api/v1/notifications" -> notificationsStatus to """[
                {"id":"n4","type":"mention","created_at":"2026-11-25T19:00:00.000Z","account":{"acct":"ann@a.social"},"status":{"id":"r1","in_reply_to_id":"3"}},
                {"id":"n3","type":"favourite","created_at":"2026-11-25T18:30:00.000Z","account":{"acct":"bob@b.social"},"status":{"id":"3"}},
                {"id":"n2","type":"follow","created_at":"2026-11-24T08:00:00.000Z","account":{"acct":"cy@c.social"}},
                {"id":"n1","type":"mention","created_at":"2026-11-23T08:00:00.000Z","account":{"acct":"dee@d.social"},"status":{"id":"r0","in_reply_to_id":"elsewhere"}}
            ]"""
            else -> 404 to "{}"
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }

    private val insights = MastodonInsights(OkHttpClient.Builder().addInterceptor(instance).build())
    private val access = ChannelAccess("m.social", "secret-token", "77")
    private val since = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()

    @Test
    fun `counts come from the account`() = runBlocking {
        val counts = insights.counts(access)
        assertEquals(120, counts.followers)
        assertEquals(30, counts.following)
        assertEquals(412, counts.posts)
        assertEquals("Bearer secret-token", seen.single().header("Authorization"))
        assertEquals("https", seen.single().url.scheme)
    }

    @Test
    fun `posts since the start, with their counts, tags and preview`() = runBlocking {
        val reading = insights.read(access, since)
        assertEquals(listOf("3", "2"), reading.recent.map { it.id })
        val top = reading.recent.first()
        assertEquals("New #Meme", top.text)
        assertEquals(listOf("#Meme"), top.hashtags)
        assertEquals("https://files/p.png", top.previewUrl)
        assertEquals(17, top.engagement)
        assertNull("an instance before 4.5 counts no quotes", reading.recent[1].quotes)

        val statuses = seen.first { it.url.encodedPath.endsWith("/statuses") }.url
        assertEquals("true", statuses.queryParameter("exclude_replies"))
        assertEquals("true", statuses.queryParameter("exclude_reblogs"))
    }

    @Test
    fun `notifications become interactions, a reply only when it answers the account's post`() = runBlocking {
        val reading = insights.read(access, since)
        assertEquals(InteractionsAccess.OK, reading.interactionsAccess)
        assertEquals(
            listOf(InteractionKind.REPLY, InteractionKind.FAVOURITE, InteractionKind.FOLLOW, InteractionKind.MENTION),
            reading.interactions.map { it.kind },
        )
        assertEquals("3", reading.interactions[0].postId)
        assertEquals("ann@a.social", reading.interactions[0].account)
        assertNull(reading.interactions[3].postId)

        val asked = seen.first { it.url.encodedPath == "/api/v1/notifications" }.url.queryParameterValues("types[]")
        assertEquals(listOf("favourite", "reblog", "mention", "follow", "quote"), asked)
    }

    @Test
    fun `an account connected without the notifications scope is told to reconnect, the rest still comes`() = runBlocking {
        notificationsStatus = 403
        val reading = insights.read(access, since)
        assertEquals(InteractionsAccess.RECONNECT, reading.interactionsAccess)
        assertTrue(reading.interactions.isEmpty())
        assertEquals(2, reading.recent.size)
        assertEquals(120, reading.counts.followers)
    }

    @Test
    fun `without the counts there is no reading`() = runBlocking {
        credentialsStatus = 401
        try {
            insights.read(access, since)
            fail("a token the instance refuses must fail the reading")
        } catch (e: MastodonException) {
            assertEquals(401, e.code)
        }
    }
}
