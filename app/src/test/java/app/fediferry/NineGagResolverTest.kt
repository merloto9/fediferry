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

import app.fediferry.data.model.ContentSource
import app.fediferry.module.ninegag.NineGagResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JSON fixtures in `src/test/resources` are real responses captured from
 * 9GAG, trimmed to the fields the resolver reads.
 */
class NineGagResolverTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing $name" }
            .bufferedReader().readText()

    // --- link matching ---------------------------------------------------

    @Test
    fun `recognises the shapes a share can arrive in`() {
        val expected = "ayNyegr"
        for (url in listOf(
            "https://9gag.com/gag/ayNyegr",
            "https://www.9gag.com/gag/ayNyegr",
            "https://m.9gag.com/gag/ayNyegr",
            "http://9gag.com/gag/ayNyegr",
            "https://9gag.com/gag/ayNyegr?ref=android",
            "https://9gag.com/gag/ayNyegr/comment/abc",
            "Look at this https://9gag.com/gag/ayNyegr trailing text",
        )) {
            assertEquals("failed for $url", expected, NineGagResolver.idOf(url))
        }
    }

    @Test
    fun `ignores links it has no business resolving`() {
        for (url in listOf(
            "https://www.instagram.com/p/DXyZ123abc/",
            "https://9gag.com/",
            "https://9gag.com/trending",
            "https://not9gag.com/gag/ayNyegr",
            "",
        )) {
            assertNull("should not match $url", NineGagResolver.idOf(url))
        }
    }

    // --- payload parsing --------------------------------------------------

    @Test
    fun `a photo post resolves to its image and title`() {
        val post = NineGagResolver.parse(fixture("ninegag_photo.json")).getOrThrow()
        assertEquals("https://img-9gag-fun.9cache.com/photo/ayNyegr_700b.jpg", post.mediaUrl)
        assertEquals("image/jpeg", post.mimeType)
        assertEquals("Its somewhat a downer", post.fields["title"])
    }

    @Test
    fun `an animated post resolves to the video, not the still frame`() {
        // The whole reason this resolver uses the API instead of og:image: the
        // Open Graph tag for this post is a single frame, and nothing on the
        // page says the animation exists.
        val post = NineGagResolver.parse(fixture("ninegag_animated.json")).getOrThrow()
        assertTrue("expected the mp4, got ${post.mediaUrl}", post.mediaUrl.endsWith(".mp4"))
        assertEquals("video/mp4", post.mimeType)
    }

    @Test
    fun `fails rather than inventing media when there is none`() {
        val empty = """{"data":{"post":{"id":"x","type":"Video","images":{}}}}"""
        assertTrue(NineGagResolver.parse(empty).isFailure)
    }

    @Test
    fun `fails on a payload that is not a post at all`() {
        assertTrue(NineGagResolver.parse("""{"data":{}}""").isFailure)
        assertTrue(NineGagResolver.parse("not json").isFailure)
        assertTrue(NineGagResolver.parse("").isFailure)
    }

    @Test
    fun `tolerates a post with no title`() {
        val untitled = """
            {"data":{"post":{"id":"x","type":"Photo","images":
            {"image700":{"url":"https://img.example/x_700b.jpg"}}}}}
        """.trimIndent()
        val post = NineGagResolver.parse(untitled).getOrThrow()
        assertNull(post.fields["title"])
        assertEquals("https://img.example/x_700b.jpg", post.mediaUrl)
    }
    // --- source fields ---------------------------------------------------

    @Test
    fun `sends every field 9GAG publishes about the post`() {
        val fields = NineGagResolver.parse(fixture("ninegag_fields.json")).getOrThrow().fields

        assertEquals("Its somewhat a downer", fields["title"])
        assertEquals("#meme #random #funny", fields["hashtags"])
        assertEquals("Funny", fields["section"])
        assertEquals("someuser", fields["author"])
        assertTrue(fields["alt"]!!.startsWith("The post features a meme"))
        assertNull("an empty description is no description", fields["description"])
    }

    @Test
    fun `only declares fields the 9GAG source lists`() {
        val declared = ContentSource.NINEGAG.fields.map { it.name }.toSet()
        val sent = NineGagResolver.parse(fixture("ninegag_fields.json")).getOrThrow().fields.keys

        assertTrue("undeclared: ${sent - declared}", declared.containsAll(sent))
    }

}
