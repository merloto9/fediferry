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
import app.fediferry.link.RedditResolver
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HTML fixtures in `src/test/resources` are real embed pages captured from
 * embed.reddit.com, trimmed to the elements the resolver reads.
 */
class RedditResolverTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing $name" }
            .bufferedReader().readText()

    // --- link matching ---------------------------------------------------

    @Test
    fun `recognises the shapes a share can arrive in`() {
        for (url in listOf(
            "https://www.reddit.com/r/memes/s/Ab3dEf9Hij",
            "https://www.reddit.com/r/memes/comments/1wn0qai/when_the/?share_id=x&utm_source=share",
            "https://reddit.com/r/memes/comments/1wn0qai/",
            "https://old.reddit.com/r/memes/comments/1wn0qai/when_the/",
            "https://m.reddit.com/r/memes/comments/1wn0qai",
            "https://www.reddit.com/user/someone/comments/1wn0qai/mine/",
            "https://www.reddit.com/comments/1wn0qai",
            "https://www.reddit.com/gallery/1wmu6f7",
            "https://redd.it/1wn0qai",
            "https://i.redd.it/vd0shyo8d0rh1.jpeg",
            "Look at this https://www.reddit.com/r/memes/s/Ab3dEf9Hij lol",
        )) {
            assertTrue("should match $url", RedditResolver(NO_HTTP).handles(url))
        }
    }

    @Test
    fun `ignores links it has no business resolving`() {
        for (url in listOf(
            "https://www.instagram.com/p/DXyZ123abc/",
            "https://www.pinterest.com/pin/246361042101456882/",
            "https://www.reddit.com/",
            "https://www.reddit.com/r/memes/",
            "https://www.reddit.com/user/someone/",
            "https://notreddit.com/r/memes/comments/1wn0qai/",
            "https://v.redd.it/5tvnzx4871rh1",
            "",
        )) {
            assertNull("should not match $url", RedditResolver.linkOf(url))
        }
    }

    @Test
    fun `takes the share link rather than whatever else is in the text`() {
        assertEquals(
            "https://www.reddit.com/r/memes/s/Ab3dEf9Hij",
            RedditResolver.linkOf("Look at this https://www.reddit.com/r/memes/s/Ab3dEf9Hij lol"),
        )
    }

    @Test
    fun `knows where a post lives when the link says`() {
        assertEquals(
            RedditResolver.PostRef("r/memes", "1wn0qai"),
            RedditResolver.postOf("https://www.reddit.com/r/memes/comments/1wn0qai/when_the/"),
        )
        assertEquals(
            RedditResolver.PostRef("user/someone", "1wn0qai"),
            RedditResolver.postOf("https://www.reddit.com/u/someone/comments/1wn0qai/"),
        )
        assertEquals(RedditResolver.PostRef(null, "1wn0qai"), RedditResolver.postOf("https://redd.it/1wn0qai"))
        assertNull(RedditResolver.postOf("https://www.reddit.com/r/memes/s/Ab3dEf9Hij"))
    }

    @Test
    fun `a directly linked picture needs no page`() {
        val post = RedditResolver.directMediaOf("https://i.redd.it/vd0shyo8d0rh1.jpeg")!!

        assertEquals("https://i.redd.it/vd0shyo8d0rh1.jpeg", post.mediaUrl)
        assertEquals("image/jpeg", post.mimeType)
    }

    // --- page parsing -----------------------------------------------------

    @Test
    fun `an image post attaches the original`() {
        val post = RedditResolver.parse(fixture("reddit_image.html")).getOrThrow()

        assertEquals("https://i.redd.it/f58v4g8mwh551.jpg", post.mediaUrl)
        assertEquals("image/jpeg", post.mimeType)
        assertEquals(
            "I’ve found a few funny memories during lockdown. This is from my 1st tour in 89, backstage in Vegas.",
            post.fields["title"],
        )
    }

    @Test
    fun `a gallery attaches its first picture at the widest size shown`() {
        val post = RedditResolver.parse(fixture("reddit_gallery.html")).getOrThrow()

        assertEquals(
            "https://preview.redd.it/the-next-episode-v0-qlmiuotnsyqh1.jpg?width=1080&crop=smart&auto=webp&s=4b2bc80f7672a9a552332cb68ab92ea5efdada6f",
            post.mediaUrl,
        )
        assertEquals("image/jpeg", post.mimeType)
        assertEquals("The Next Episode", post.fields["title"])
    }

    @Test
    fun `a gallery preview names its original`() {
        assertEquals(
            "https://i.redd.it/qlmiuotnsyqh1.jpg",
            RedditResolver.originalOf(
                "https://preview.redd.it/the-next-episode-v0-qlmiuotnsyqh1.jpg?width=1080&crop=smart&auto=webp&s=4b",
            ),
        )
    }

    @Test
    fun `a GIF's mp4 is not mistaken for a gallery preview`() {
        assertNull(
            RedditResolver.originalOf("https://preview.redd.it/3fi70fyqzaoh1.gif?width=1280&format=mp4&s=1b"),
        )
    }

    @Test
    fun `a video attaches the packaged mp4 that carries its sound`() {
        val post = RedditResolver.parse(fixture("reddit_video.html")).getOrThrow()

        assertTrue(post.mediaUrl, post.mediaUrl.startsWith("https://packaged-media.redd.it/5tvnzx4871rh1/pb/m2-res_760p.mp4?"))
        assertTrue("entities must be unescaped", "&amp;" !in post.mediaUrl)
        assertEquals("video/mp4", post.mimeType)
        assertEquals("So how's your new year resolutions going", post.fields["title"])
    }

    @Test
    fun `declines a video without a packaged copy rather than posting it silent`() {
        val html = fixture("reddit_video.html").replace(Regex("""\spackaged-media-json="[^"]*""""), "")

        assertTrue(RedditResolver.parse(html).isFailure)
    }

    @Test
    fun `a GIF comes across as its mp4`() {
        val post = RedditResolver.parse(fixture("reddit_gif.html")).getOrThrow()

        assertEquals(
            "https://preview.redd.it/3fi70fyqzaoh1.gif?width=1280&format=mp4&s=1b2fc7ebebee626525a3eb95a96db03474e4d943",
            post.mediaUrl,
        )
        assertEquals("video/mp4", post.mimeType)
    }

    @Test
    fun `a GIF without a player falls back to the GIF itself`() {
        val html = fixture("reddit_gif.html").replace(Regex("""<shreddit-player[^>]*>"""), "")

        val post = RedditResolver.parse(html).getOrThrow()

        assertEquals("https://i.redd.it/3fi70fyqzaoh1.gif", post.mediaUrl)
        assertEquals("image/gif", post.mimeType)
    }

    @Test
    fun `declines a text post`() {
        // A text post's page, reduced to what matters: its type and its own address.
        val html = fixture("reddit_image.html")
            .replace("&quot;type&quot;:&quot;image&quot;}", "&quot;type&quot;:&quot;text&quot;}")
            .replace("https://i.redd.it/f58v4g8mwh551.jpg", "https://www.reddit.com/r/pics/comments/haucpf/x/")

        assertTrue(RedditResolver.parse(html).isFailure)
    }

    @Test
    fun `declines the bot challenge Reddit shows instead of a page`() {
        val html = "<html><body><form><input name=\"solution\"></form></body></html>"

        assertTrue(RedditResolver.parse(html).isFailure)
    }

    @Test
    fun `reads the real address off a page asked for at the wrong subreddit`() {
        assertEquals(
            "https://www.reddit.com/r/comics/comments/1wmu6f7/the_next_episode/",
            RedditResolver.canonicalOf(fixture("reddit_unsupported.html")),
        )
    }

    // --- the whole resolution, against a fake Reddit ---------------------

    @Test
    fun `a share link is followed to its post and the gallery original is used`() = runTest {
        val seen = mutableListOf<String>()
        val http = fake(seen) { method, url ->
            when {
                url == "https://www.reddit.com/r/comics/s/Ab3dEf9Hij" ->
                    redirect("https://www.reddit.com/r/comics/comments/1wmu6f7/the_next_episode/?share_id=x")
                url == "https://embed.reddit.com/r/comics/comments/1wmu6f7/" ->
                    page(fixture("reddit_gallery.html"))
                method == "HEAD" && url == "https://i.redd.it/qlmiuotnsyqh1.jpg" -> ok()
                else -> notFound()
            }
        }

        val post = RedditResolver(http).resolve("https://www.reddit.com/r/comics/s/Ab3dEf9Hij").getOrThrow()

        assertEquals("https://i.redd.it/qlmiuotnsyqh1.jpg", post.mediaUrl)
        assertEquals("The Next Episode", post.fields["title"])
        assertTrue("the post page itself is never fetched", seen.none { it.contains("www.reddit.com/r/comics/comments") })
    }

    @Test
    fun `a gallery keeps the preview when the original is not there`() = runTest {
        val http = fake { _, url ->
            if (url == "https://embed.reddit.com/r/comics/comments/1wmu6f7/") page(fixture("reddit_gallery.html"))
            else notFound()
        }

        val post = RedditResolver(http).resolve("https://www.reddit.com/r/comics/comments/1wmu6f7/").getOrThrow()

        assertTrue(post.mediaUrl, post.mediaUrl.startsWith("https://preview.redd.it/"))
    }

    @Test
    fun `a link without a subreddit is looked up once, then fetched where it lives`() = runTest {
        val seen = mutableListOf<String>()
        val http = fake(seen) { _, url ->
            when (url) {
                "https://embed.reddit.com/r/all/comments/1wmu6f7/" -> page(fixture("reddit_unsupported.html"))
                "https://embed.reddit.com/r/comics/comments/1wmu6f7/" -> page(fixture("reddit_gallery.html"))
                else -> notFound()
            }
        }

        val post = RedditResolver(http).resolve("https://redd.it/1wmu6f7").getOrThrow()

        assertEquals("The Next Episode", post.fields["title"])
        assertEquals(2, seen.count { it.startsWith("GET https://embed.reddit.com/") })
    }

    @Test
    fun `a blocked embed page fails rather than attaching nothing`() = runTest {
        val http = fake { _, _ -> status(403) }

        assertTrue(RedditResolver(http).resolve("https://www.reddit.com/r/memes/comments/1wn0qai/").isFailure)
    }

    // --- fake network ---------------------------------------------------

    private class Reply(val code: Int, val body: String = "", val location: String? = null)

    private fun page(html: String) = Reply(200, html)
    private fun ok() = Reply(200)
    private fun notFound() = Reply(404)
    private fun status(code: Int) = Reply(code)
    private fun redirect(to: String) = Reply(307, location = to)

    private fun fake(
        seen: MutableList<String> = mutableListOf(),
        route: (method: String, url: String) -> Reply,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            seen += "${request.method} $url"
            val reply = route(request.method, url)
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

    // --- source fields ---------------------------------------------------

    @Test
    fun `sends the subreddit alongside the title`() {
        val fields = RedditResolver.parse(fixture("reddit_image.html")).getOrThrow().fields

        assertEquals("pics", fields["subreddit"])
        assertTrue(ContentSource.REDDIT.fields.map { it.name }.containsAll(fields.keys))
    }

    private companion object {
        val NO_HTTP = OkHttpClient()
    }
}
