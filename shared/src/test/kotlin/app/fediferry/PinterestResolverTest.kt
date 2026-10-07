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
import app.fediferry.link.CleanedLink
import app.fediferry.module.pinterest.PinterestResolver
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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

        assertEquals("Hydrangeas Art Print", post.fields["title"])
    }

    @Test
    fun `a pin without a title resolves without a caption`() {
        val html = fixture("pinterest_pin.html").replace("og:title", "og:untitled")

        val post = PinterestResolver.parse(html).getOrThrow()

        assertNotNull(post.mediaUrl)
        assertNull(post.fields["title"])
    }

    @Test
    fun `keeps a long title, minus the tail`() {
        val post = PinterestResolver.parse(fixture("pinterest_pin.html")).getOrThrow()

        assertEquals(
            "Family Tree Quilt, Personalized Grandkids Names, Birthdates, " +
                "Custom Embroidered Birthday Gift, for Grandparent, Parent, …",
            post.fields["title"],
        )
    }

    @Test
    fun `unescapes entities in the title`() {
        val html = fixture("pinterest_pin_titled.html")
            .replace("Hydrangeas Art Print |", "Salt &amp; Pepper |")

        assertEquals("Salt & Pepper", PinterestResolver.parse(html).getOrThrow().fields["title"])
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

    // --- source fields ---------------------------------------------------

    @Test
    fun `sends the description as its own field, apart from the title`() {
        val fields = PinterestResolver.parse(fixture("pinterest_pin_titled.html")).getOrThrow().fields

        assertEquals("Hydrangeas Art Print", fields["title"])
        assertTrue(fields["description"]!!.startsWith("Find the perfect handmade gift"))
        assertTrue(ContentSource.PINTEREST.fieldNames.containsAll(fields.keys))
    }

    // --- the sharer's details in a link -------------------------------------

    @Test
    fun `reads the pin id from every shape a link comes in`() {
        for (url in listOf(
            "https://www.pinterest.com/pin/13018286423924683/sent/?invite_code=ebf11b4c&sender=323555691887329335&sfo=1",
            "https://de.pinterest.com/pin/13018286423924683/",
            "https://www.pinterest.co.uk/pin/some-title--13018286423924683/",
        )) {
            assertEquals(url, "13018286423924683", PinterestResolver.pinIdOf(url))
        }
        assertNull(PinterestResolver.pinIdOf("https://pin.it/1KEyRrKCD"))
    }

    @Test
    fun `the clean address names the pin and nothing else`() {
        assertEquals("https://www.pinterest.com/pin/13018286423924683/", PinterestResolver.canonicalOf("13018286423924683"))
    }

    @Test
    fun `a pin page is recognised as that pin, and only that pin`() {
        val html = fixture("pinterest_pin_titled.html")

        assertTrue(PinterestResolver.isPinPage(html, "751467887802740312"))
        assertFalse("another pin's page", PinterestResolver.isPinPage(html, "13018286423924683"))
        assertFalse(
            "a page without the pin's picture",
            PinterestResolver.isPinPage(html.replace("og:image", "og:nothing"), "751467887802740312"),
        )
    }

    @Test
    fun `a repin is its own pin though its page names the pin it was saved from`() {
        val html = fixture("pinterest_repin.html")

        assertEquals("1132373900084961258", PinterestResolver.mainPinIdOf(html))
        assertTrue(PinterestResolver.isPinPage(html, "1132373900084961258"))
        // The original is named in og:url, so it counts as well.
        assertTrue(PinterestResolver.isPinPage(html, "30962316181759460"))
        // A related pin further down the page is not this page's pin.
        assertFalse(PinterestResolver.isPinPage(html, "1132374037465594811"))
        // Pinterest's page for a pin that does not exist has no picture and no pin data.
        assertFalse(PinterestResolver.isPinPage("<html><head></head><body></body></html>", "9132373900084961258"))
    }

    @Test
    fun `a shared repin loses its sender too`() = runTest {
        val http = fake(mutableListOf()) { url ->
            if (url == "https://www.pinterest.com/pin/1132373900084961258/") page(fixture("pinterest_repin.html")) else notFound()
        }

        val cleaned = PinterestResolver(http).cleanLink(
            "https://www.pinterest.de/pin/1132373900084961258/sent/?invite_code=89bae6ed&sender=616148930178164272&sfo=1",
        )

        assertEquals(CleanedLink.Clean("https://www.pinterest.com/pin/1132373900084961258/"), cleaned)
    }

    @Test
    fun `a shared link loses its sender once the plain address shows the same pin`() = runTest {
        val seen = mutableListOf<String>()
        val http = fake(seen) { url ->
            if (url == "https://www.pinterest.com/pin/751467887802740312/") page(fixture("pinterest_pin_titled.html")) else notFound()
        }

        val cleaned = PinterestResolver(http).cleanLink(
            "https://www.pinterest.com/pin/751467887802740312/sent/?invite_code=ebf11b4c&sender=323555691887329335&sfo=1",
        )

        assertEquals(CleanedLink.Clean("https://www.pinterest.com/pin/751467887802740312/"), cleaned)
        assertTrue("the sender's address is never loaded", seen.none { "sender=" in it })
    }

    @Test
    fun `a pin_it link is followed, without loading its page, to a clean address`() = runTest {
        val seen = mutableListOf<String>()
        val http = fake(seen) { url ->
            when (url) {
                // The chain Pinterest really answers with, shortener hop included.
                "https://pin.it/1KEyRrKCD" -> redirect("https://api.pinterest.com/url_shortener/1KEyRrKCD/redirect/")
                "https://api.pinterest.com/url_shortener/1KEyRrKCD/redirect/" ->
                    redirect("https://www.pinterest.com/pin/751467887802740312/sent/?invite_code=ebf11b4c&sender=323555691887329335&sfo=1")
                "https://www.pinterest.com/pin/751467887802740312/" -> page(fixture("pinterest_pin_titled.html"))
                else -> notFound()
            }
        }

        val cleaned = PinterestResolver(http).cleanLink("https://pin.it/1KEyRrKCD")

        assertEquals(CleanedLink.Clean("https://www.pinterest.com/pin/751467887802740312/"), cleaned)
        assertTrue(seen.none { "sender=" in it })
    }

    @Test
    fun `when the plain address shows no such pin, the shared link is kept and flagged`() = runTest {
        // Pinterest answers a missing pin with a page too — one that is not this pin.
        val http = fake { page("<html><head><meta property=\"og:url\" content=\"https://www.pinterest.com/\"></head></html>") }

        val cleaned = PinterestResolver(http).cleanLink(
            "https://www.pinterest.com/pin/751467887802740312/sent/?invite_code=ebf11b4c&sender=323555691887329335",
        )

        assertTrue(cleaned is CleanedLink.MayIdentify)
    }

    @Test
    fun `when Pinterest can't be reached, the shared link is kept and flagged`() = runTest {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { throw java.io.IOException("offline") }).build()

        assertTrue(PinterestResolver(http).cleanLink("https://pin.it/1KEyRrKCD") is CleanedLink.MayIdentify)
    }

    @Test
    fun `an already clean link is left alone, and a non-pin link too`() = runTest {
        val http = fake { notFound() }

        assertEquals(CleanedLink.Unchanged, PinterestResolver(http).cleanLink("https://www.pinterest.com/pin/751467887802740312/"))
        assertEquals(CleanedLink.Unchanged, PinterestResolver(http).cleanLink("https://www.instagram.com/p/abc/"))
    }

    @Test
    fun `fetching the picture after the check does not load the page again`() = runTest {
        val seen = mutableListOf<String>()
        val http = fake(seen) { url ->
            if (url == "https://www.pinterest.com/pin/751467887802740312/") page(fixture("pinterest_pin_titled.html")) else notFound()
        }
        val resolver = PinterestResolver(http)

        val clean = (resolver.cleanLink("https://www.pinterest.com/pin/751467887802740312/sent/?sender=1") as CleanedLink.Clean).url
        resolver.resolve(clean).getOrThrow()

        assertEquals(1, seen.count { it == "GET https://www.pinterest.com/pin/751467887802740312/" })
    }

    // --- fake network ---------------------------------------------------------

    private class Reply(val code: Int, val body: String = "", val location: String? = null)

    private fun page(html: String) = Reply(200, html)
    private fun notFound() = Reply(404)
    private fun redirect(to: String) = Reply(302, location = to)

    private fun fake(seen: MutableList<String> = mutableListOf(), route: (url: String) -> Reply): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                seen += "${request.method} ${request.url}"
                val reply = route(request.url.toString())
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(reply.code)
                    .message("fake")
                    .apply { reply.location?.let { header("Location", it) } }
                    .body(reply.body.toResponseBody("text/html".toMediaType()))
                    .build()
            })
            .build()

    private companion object {
        /** `handles` does no I/O, so the client is never touched. */
        val NO_HTTP = okhttp3.OkHttpClient()
    }
}
