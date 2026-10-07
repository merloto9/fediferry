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

import app.fediferry.channel.MastodonPublisher
import app.fediferry.channel.PublishJob
import app.fediferry.channel.PublishMedia
import app.fediferry.mastodon.MastodonException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MastodonPublisherTest {

    private val seen = mutableListOf<Request>()
    private val bodies = mutableListOf<String>()
    private var mediaPolls = 0
    private var statusCode = 200

    private val instance = Interceptor { chain ->
        val request = chain.request()
        seen += request
        bodies += Buffer().also { request.body?.writeTo(it) }.readUtf8()
        val (code, body) = when (request.url.encodedPath) {
            "/api/v2/media" -> 202 to """{"id":"m1"}"""
            "/api/v1/media/m1" -> (if (++mediaPolls < 2) 206 else 200) to """{"id":"m1"}"""
            "/api/v1/statuses" -> statusCode to """{"id":"s1","url":"https://social.example/@memes/s1"}"""
            else -> 404 to "{}"
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }

    private val publisher = MastodonPublisher(OkHttpClient.Builder().addInterceptor(instance).build())
    private val job = PublishJob(
        "social.example", "tok", "#meme via x", "spiders", "unlisted",
        listOf(PublishMedia(byteArrayOf(1, 2, 3), "image/png", "A cat")), "key-1",
    )

    @Test
    fun `waits for the picture, then posts with the key`() = runBlocking {
        val published = publisher.publish(job)
        assertEquals("s1", published.remoteId)
        assertEquals("https://social.example/@memes/s1", published.url)
        assertEquals(listOf("/api/v2/media", "/api/v1/media/m1", "/api/v1/media/m1", "/api/v1/statuses"), seen.map { it.url.encodedPath })
        val status = seen.last()
        assertEquals("key-1", status.header("Idempotency-Key"))
        assertEquals("Bearer tok", status.header("Authorization"))
        assertTrue("A cat" in bodies.first())
        val form = bodies.last()
        assertTrue("media_ids%5B%5D=m1" in form)
        assertTrue("spoiler_text=spiders" in form)
        assertTrue("visibility=unlisted" in form)
    }

    @Test
    fun `a refused post is not tried again, a busy instance is`() = runBlocking {
        statusCode = 422
        val refused = runCatching { publisher.publish(job) }.exceptionOrNull() as MastodonException
        assertEquals("mastodon.rejected", refused.message)
        assertFalse(refused.retryable)

        statusCode = 503
        mediaPolls = 0
        val busy = runCatching { publisher.publish(job) }.exceptionOrNull() as MastodonException
        assertTrue(busy.retryable)
    }
}
