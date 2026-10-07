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

import app.fediferry.module.youtube.YouTubePostParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixture is a real ytInitialData fragment from a live channel, trimmed to
 * two posts. This is the most fragile code in the app — it reads a shape nobody
 * promised — so it is pinned against something YouTube actually served.
 */
class YouTubePostParserTest {

    private fun fixture(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("youtube_posts.json"))
            .bufferedReader().readText()

    @Test
    fun `reads both posts from a real page`() {
        val posts = YouTubePostParser.parse(fixture())
        assertEquals(2, posts.size)
    }

    @Test
    fun `a multi-image post keeps every picture`() {
        val post = YouTubePostParser.parse(fixture()).first()
        assertEquals(2, post.imageUrls.size)
        assertTrue(post.imageUrls.all { it.startsWith("https://") })
    }

    @Test
    fun `a single-image post has exactly one`() {
        val post = YouTubePostParser.parse(fixture())[1]
        assertEquals(1, post.imageUrls.size)
    }

    @Test
    fun `text, author and timestamp survive`() {
        val post = YouTubePostParser.parse(fixture()).first()
        assertEquals("Trockener Humor", post.text)
        assertEquals("ZDF heute-show", post.author)
        assertNotNull(post.publishedText)
    }

    @Test
    fun `the permalink is built from the post id`() {
        val post = YouTubePostParser.parse(fixture()).first()
        assertTrue(post.permalink, post.permalink.startsWith("https://www.youtube.com/post/Ugkx"))
    }

    @Test
    fun `the author avatar is not mistaken for a post image`() {
        // The avatar is a 76px thumbnail living in the same structure. Every
        // returned URL must be a real attachment.
        val posts = YouTubePostParser.parse(fixture())
        assertTrue(posts.all { it.imageUrls.none { url -> url.contains("googleusercontent.com/ytc/") } })
    }

    @Test
    fun `a shape it does not recognise yields nothing rather than nonsense`() {
        assertTrue(YouTubePostParser.parse("""{"contents":{}}""").isEmpty())
        assertTrue(YouTubePostParser.parse("not json").isEmpty())
        assertTrue(YouTubePostParser.parse("").isEmpty())
    }

    // --- pulling the blob out of the page --------------------------------

    @Test
    fun `finds the blob in both spellings youtube uses`() {
        for (page in listOf(
            """<script>var ytInitialData = {"a":{"b":1}};</script>""",
            """<script>window["ytInitialData"] = {"a":{"b":1}};</script>""",
        )) {
            assertEquals("""{"a":{"b":1}}""", YouTubePostParser.extractInitialData(page))
        }
    }

    @Test
    fun `a brace inside a caption does not truncate the blob`() {
        // Stopping at the first closing brace is the obvious mistake here.
        val page = """<script>var ytInitialData = {"t":"a } brace","x":{"y":2}};</script>"""
        assertEquals("""{"t":"a } brace","x":{"y":2}}""", YouTubePostParser.extractInitialData(page))
    }

    @Test
    fun `an escaped quote does not confuse the scanner`() {
        val page = """<script>var ytInitialData = {"t":"say \"} hi\"","x":1};</script>"""
        assertEquals("""{"t":"say \"} hi\"","x":1}""", YouTubePostParser.extractInitialData(page))
    }

    @Test
    fun `a page without the blob returns null`() {
        assertNull(YouTubePostParser.extractInitialData("<html>nothing here</html>"))
        assertNull(YouTubePostParser.extractInitialData(""))
    }

    @Test
    fun `an unterminated blob returns null rather than a truncated object`() {
        assertNull(YouTubePostParser.extractInitialData("""<script>var ytInitialData = {"a":{"b":1}"""))
    }

    // --- the escaped form mobile pages use -------------------------------

    @Test
    fun `reads the hex-escaped string form mobile pages serve`() {
        // Mobile assigns the blob as a quoted JS string whose braces and quotes
        // are \xNN escapes, and JSON.parses it at runtime. Looking for a brace
        // finds one belonging to something else entirely, which is exactly how
        // this shipped broken the first time.
        val page = """<script>var ytInitialData = '\x7b\x22a\x22:1\x7d';</script>"""
        assertEquals("""{"a":1}""", YouTubePostParser.extractInitialData(page))
    }

    @Test
    fun `an escaped blob parses into real posts`() {
        val escaped = fixture()
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("{", "\\x7b")
            .replace("}", "\\x7d")
            .replace("\"", "\\x22")
        val page = "<script>var ytInitialData = '" + escaped + "';</script>"
        val blob = YouTubePostParser.extractInitialData(page)
        assertNotNull("the escaped blob was not recovered", blob)
        assertEquals(2, YouTubePostParser.parse(blob!!).size)
    }

    @Test
    fun `escapes decode to the characters they stand for`() {
        val page = """<script>var ytInitialData = '\x7b\x22t\x22:\x22caf\u00e9\x22\x7d';</script>"""
        assertEquals("""{"t":"café"}""", YouTubePostParser.extractInitialData(page))
    }

    @Test
    fun `an escaped backslash becomes one backslash, not two`() {
        // Doubling it turned the JSON's own \" into \\" and stopped parsing
        // partway through a real page.
        val page = """<script>var ytInitialData = '\x7b\x22t\x22:\x22a \\\x22b\x22\x7d';</script>"""
        val blob = YouTubePostParser.extractInitialData(page)
        assertEquals("""{"t":"a \"b"}""", blob)
    }

    @Test
    fun `an escaped quote inside the string does not end it early`() {
        val page = """<script>var ytInitialData = '\x7b\x22t\x22:\x22a \' b\x22\x7d';</script>"""
        val blob = YouTubePostParser.extractInitialData(page)
        assertNotNull(blob)
        assertTrue(blob!!, blob.startsWith("{") && blob.endsWith("}"))
    }
}
