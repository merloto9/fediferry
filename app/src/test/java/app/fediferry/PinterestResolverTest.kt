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

import app.fediferry.link.PinterestResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HTML fixtures in `src/test/resources` are real pin pages captured from
 * Pinterest, trimmed to the tags the resolver reads.
 */
class PinterestResolverTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing $name" }
            .bufferedReader().readText()

    // --- link matching ---------------------------------------------------

    @Test
    fun `recognises the shapes a share can arrive in`() {
        for (url in listOf(
            "https://www.pinterest.com/pin/246361042101456882/",
            "https://pinterest.com/pin/246361042101456882",
            "https://de.pinterest.com/pin/246361042101456882/",
            "https://www.pinterest.de/pin/246361042101456882/",
            "https://www.pinterest.co.uk/pin/246361042101456882/",
            "http://pinterest.com/pin/246361042101456882/",
            "https://www.pinterest.com/pin/quilted-for-home-gifts--246361042101456882/",
            "Schau mal https://pin.it/2Abc3De rest of the message",
        )) {
            assertTrue("should match $url", PinterestResolver(NO_HTTP).handles(url))
        }
    }

    @Test
    fun `ignores links it has no business resolving`() {
        for (url in listOf(
            "https://www.instagram.com/p/DXyZ123abc/",
            "https://9gag.com/gag/ayNyegr",
            "https://www.pinterest.com/",
            "https://www.pinterest.com/someuser/some-board/",
            "https://notpinterest.com/pin/123/",
            "",
        )) {
            assertNull("should not match $url", PinterestResolver.linkOf(url))
        }
    }

    @Test
    fun `takes only the link out of a longer share text`() {
        assertEquals(
            "https://www.pinterest.com/pin/246361042101456882/",
            PinterestResolver.linkOf("Look: https://www.pinterest.com/pin/246361042101456882/ nice"),
        )
    }

    // --- page parsing -----------------------------------------------------

    @Test
    fun `prefers the full-size original over the preview`() {
        val post = PinterestResolver.parse(fixture("pinterest_pin.html")).getOrThrow()

        assertEquals(
            "https://i.pinimg.com/originals/c1/3a/4c/c13a4cb89ddbbbcc374abc82ed5c2ec6.jpg",
            post.mediaUrl,
        )
        assertEquals("image/jpeg", post.mimeType)
    }

    @Test
    fun `falls back to the preview when the page names no original`() {
        val html = fixture("pinterest_pin.html").replace("i.pinimg.com/originals/", "i.pinimg.com/elsewhere/")

        val post = PinterestResolver.parse(html).getOrThrow()

        assertEquals(
            "https://i.pinimg.com/736x/c1/3a/4c/c13a4cb89ddbbbcc374abc82ed5c2ec6.jpg",
            post.mediaUrl,
        )
    }

    @Test
    fun `takes the original's own extension, not the preview's`() {
        val html = fixture("pinterest_pin.html")
            .replace("originals/c1/3a/4c/c13a4cb89ddbbbcc374abc82ed5c2ec6.jpg",
                "originals/c1/3a/4c/c13a4cb89ddbbbcc374abc82ed5c2ec6.png")

        val post = PinterestResolver.parse(html).getOrThrow()

        assertTrue(post.mediaUrl.endsWith(".png"))
        assertEquals("image/png", post.mimeType)
    }

    @Test
    fun `carries the pin's title without Pinterest's keyword tail`() {
        val post = PinterestResolver.parse(fixture("pinterest_pin_titled.html")).getOrThrow()

        assertEquals("Hydrangeas Art Print", post.caption)
    }

    @Test
    fun `a pin without a title resolves without a caption`() {
        val html = fixture("pinterest_pin.html").replace("og:title", "og:untitled")

        val post = PinterestResolver.parse(html).getOrThrow()

        assertNotNull(post.mediaUrl)
        assertNull(post.caption)
    }

    @Test
    fun `keeps a long title, minus the tail`() {
        val post = PinterestResolver.parse(fixture("pinterest_pin.html")).getOrThrow()

        assertEquals(
            "Family Tree Quilt, Personalized Grandkids Names, Birthdates, " +
                "Custom Embroidered Birthday Gift, for Grandparent, Parent, …",
            post.caption,
        )
    }

    @Test
    fun `unescapes entities in the title`() {
        val html = fixture("pinterest_pin_titled.html")
            .replace("Hydrangeas Art Print |", "Salt &amp; Pepper |")

        assertEquals("Salt & Pepper", PinterestResolver.parse(html).getOrThrow().caption)
    }

    // --- the cases that must not resolve ----------------------------------

    @Test
    fun `declines a video pin rather than posting its cover frame`() {
        val html = fixture("pinterest_pin.html")
            .replace(""""videos":null""", """"videos":{"V_720P":{"url":"https://v1.pinimg.com/x.mp4"}}""")

        assertTrue(PinterestResolver.isVideoPin(html))
        assertTrue(PinterestResolver.parse(html).isFailure)
    }

    @Test
    fun `declines a page declaring og-video`() {
        val html = fixture("pinterest_pin.html")
            .replace("</head>", """<meta content="https://v1.pinimg.com/x.mp4" name="og:video"/></head>""")

        assertTrue(PinterestResolver.parse(html).isFailure)
    }

    @Test
    fun `an image pin is not mistaken for a video one`() {
        assertFalse(PinterestResolver.isVideoPin(fixture("pinterest_pin.html")))
        assertFalse(PinterestResolver.isVideoPin(fixture("pinterest_pin_titled.html")))
    }

    @Test
    fun `declines a page with no pin image`() {
        val html = fixture("pinterest_pin.html").replace("og:image", "og:nothing")

        assertTrue(PinterestResolver.parse(html).isFailure)
    }

    @Test
    fun `declines an image hosted somewhere other than Pinterest`() {
        val html = fixture("pinterest_pin.html").replace("https://i.pinimg.com/736x/", "https://evil.example/")

        assertTrue(PinterestResolver.parse(html).isFailure)
    }

    @Test
    fun `declines a login wall that carries no pin at all`() {
        val html = "<html><head><title>Pinterest</title></head><body>Log in to continue</body></html>"

        assertTrue(PinterestResolver.parse(html).isFailure)
    }

    @Test
    fun `an original named for a different pin is not stolen`() {
        // Pin pages carry unrelated originals URLs in their stylesheets.
        val html = fixture("pinterest_pin.html")
            .replace(
                "i.pinimg.com/originals/c1/3a/4c/c13a4cb89ddbbbcc374abc82ed5c2ec6.jpg",
                "i.pinimg.com/originals/d5/3b/01/d53b014d86a6b6761bf649a0ed813c2b.png",
            )

        assertNotNull(PinterestResolver.parse(html).getOrThrow().mediaUrl)
        assertEquals(
            "https://i.pinimg.com/736x/c1/3a/4c/c13a4cb89ddbbbcc374abc82ed5c2ec6.jpg",
            PinterestResolver.parse(html).getOrThrow().mediaUrl,
        )
    }

    private companion object {
        /** `handles` does no I/O, so the client is never touched. */
        val NO_HTTP = okhttp3.OkHttpClient()
    }
}
