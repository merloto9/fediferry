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
package app.fediferry.module.pinterest

import app.fediferry.data.model.ContentSource
import app.fediferry.link.CleanedLink
import app.fediferry.link.LinkResolver
import app.fediferry.link.ResolvedPost
import app.fediferry.link.fieldsOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Resolves a Pinterest pin link to the picture behind it.
 *
 * Reads the pin page's Open Graph tags — the surface Pinterest publishes for
 * link previews — rather than any internal endpoint, and needs no account: a
 * public pin answers an anonymous request. Signing in would mean keeping a
 * Pinterest session in the app for no gain the anonymous path does not already
 * give, and it is automated access under a real account that Pinterest's terms
 * actually forbid.
 *
 * `og:image` points at a 736-pixel-wide copy. The same picture is served under
 * `originals/` at full size, so that URL is used instead — but only when the
 * page itself names it, rather than guessing at a URL that may not exist.
 *
 * A shared link carries who shared it. The app shares
 * `pin.it/<code>`, which redirects to `/pin/<id>/sent/?invite_code=…&sender=…`,
 * where `sender` is the sharer's Pinterest user id. [cleanLink] follows the
 * redirect without loading the page, rebuilds the plain `/pin/<id>/` address,
 * and loads that to check it is the same pin before it replaces the link.
 *
 * Video pins are declined rather than resolved. Their `og:image` is a cover
 * frame, and posting a still of a video without saying so is the failure the
 * 9GAG resolver was written to avoid. Declining leaves the screenshot path,
 * exactly as an unrecognised link would.
 */
class PinterestResolver(private val http: OkHttpClient) : LinkResolver {

    override val source = ContentSource.PINTEREST

    override fun handles(url: String): Boolean = linkOf(url) != null

    override val cleansLinks = true

    /** For the short link's redirects, wanted as addresses, not as pages. */
    private val noRedirects: OkHttpClient by lazy {
        http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

    /**
     * The last pin page loaded, so checking a cleaned link and then fetching
     * its picture downloads the page — well over a megabyte — once.
     */
    @Volatile private var lastPage: Pair<String, String>? = null

    override suspend fun cleanLink(url: String): CleanedLink = withContext(Dispatchers.IO) {
        val link = linkOf(url) ?: return@withContext CleanedLink.Unchanged
        runCatching {
            val pinLink = if (SHORT.matches(link)) followShortLink(link) else link
            val id = pinIdOf(pinLink)
                ?: return@runCatching CleanedLink.MayIdentify("the short link led nowhere Pinterest-shaped")
            val clean = canonicalOf(id)
            if (url.trim() == clean) return@runCatching CleanedLink.Unchanged
            val html = page(clean)
            if (isPinPage(html, id)) CleanedLink.Clean(clean)
            else CleanedLink.MayIdentify("the plain pin address did not show the same pin")
        }.getOrElse { CleanedLink.MayIdentify(it.message ?: it.javaClass.simpleName) }
    }

    /** Follows a pin.it link's redirects to the pin address, without loading any page. */
    private fun followShortLink(link: String): String {
        var current = link
        repeat(MAX_REDIRECTS) {
            if (pinIdOf(current) != null) return current
            val request = Request.Builder().url(current).header("User-Agent", USER_AGENT).get().build()
            current = noRedirects.newCall(request).execute().use { response ->
                if (!response.isRedirect) return current
                response.header("Location")?.let { response.request.url.resolve(it)?.toString() }
            } ?: return current
        }
        return current
    }

    private fun page(url: String): String {
        lastPage?.let { (cachedUrl, html) -> if (cachedUrl == url) return html }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html")
            .get()
            .build()
        val html = http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Pinterest returned ${response.code}" }
            response.peekBody(MAX_HTML_BYTES).string()
        }
        lastPage = url to html
        return html
    }

    override suspend fun resolve(url: String): Result<ResolvedPost> =
        withContext(Dispatchers.IO) {
            runCatching {
                val link = linkOf(url) ?: error("Not a Pinterest pin link")
                // A pin.it link redirects to the pin page; OkHttp follows it.
                parse(page(link)).getOrThrow()
            }
        }

    companion object {
        private const val USER_AGENT = "FediFerry (+https://github.com/merloto9/fediferry)"

        /**
         * A pin page is well over a megabyte and its meta tags sit near the end
         * of it, so this is generous. Truncating earlier would cost the
         * full-size upgrade or the whole resolution, never a wrong picture.
         */
        private const val MAX_HTML_BYTES = 4L * 1024 * 1024

        /**
         * pinterest.<tld>/pin/<id>, with any country subdomain or prefix, and
         * with the optional `<slug>--` the canonical link carries.
         */
        private val PIN = Regex(
            """https?://(?:[a-z0-9-]+\.)*pinterest\.(?:[a-z]{2,3}\.)?[a-z]{2,3}/pin/(?:[^/?#\s]*--)?[0-9]+/?""",
            RegexOption.IGNORE_CASE,
        )

        /** The share sheet's own short link, which redirects to a pin page. */
        private val SHORT = Regex("""https?://pin\.it/[A-Za-z0-9]+/?""", RegexOption.IGNORE_CASE)

        private const val MAX_REDIRECTS = 5

        /** The URL to fetch, picked out of whatever text came with the share. */
        fun linkOf(url: String): String? =
            (PIN.find(url.trim()) ?: SHORT.find(url.trim()))?.value

        /** The pin's id, from any form of its address — with a slug, a country domain, or /sent/?…. */
        fun pinIdOf(url: String): String? =
            PIN_ID.find(url.trim())?.groupValues?.get(1)

        private val PIN_ID = Regex(
            """pinterest\.(?:[a-z]{2,3}\.)?[a-z]{2,3}/pin/(?:[^/?#\s]*--)?([0-9]+)""",
            RegexOption.IGNORE_CASE,
        )

        /** The address that names the pin and nothing else — no sender, no invite. */
        fun canonicalOf(id: String): String = "https://www.pinterest.com/pin/$id/"

        /**
         * Whether [html] is pin [id]'s own page. A 200 is not enough: Pinterest
         * answers a pin that does not exist with a page too, one without the
         * pin's picture or data. So the page has to carry a picture, and name
         * the pin either in `og:url` or as the pin its data is about.
         *
         * `og:url` alone is not enough: a pin saved from another one (a repin,
         * which most shared memes are) names the pin it was saved from there,
         * and in its canonical link. Its data still says which pin it is.
         * Related pins further down the page carry ids too, so only the page's
         * own pin — the one the `PinResponse` is about — counts.
         */
        fun isPinPage(html: String, id: String): Boolean {
            val hasPicture = meta(html, "og:image")?.startsWith("https://i.pinimg.com/", ignoreCase = true) == true
            return hasPicture && (meta(html, "og:url")?.let(::pinIdOf) == id || mainPinIdOf(html) == id)
        }

        /** The id of the pin a page's data is about, or null when it has none. */
        fun mainPinIdOf(html: String): String? = MAIN_PIN.find(html)?.groupValues?.get(1)

        private val MAIN_PIN = Regex(""""__typename":"PinResponse","data":\{"entityId":"([0-9]+)"""")

        /**
         * Pulls the picture out of a pin page.
         *
         * Pure, so the awkward cases — a video pin, a pin whose original is not
         * published, a title carrying Pinterest's keyword tail — are covered by
         * tests against captured pages rather than by hitting the network.
         */
        fun parse(html: String): Result<ResolvedPost> = runCatching {
            check(!isVideoPin(html)) { "Pinterest video pins carry no postable picture" }

            val preview = meta(html, "og:image")
                ?.takeIf { it.startsWith("https://i.pinimg.com/", ignoreCase = true) }
                ?: error("Pinterest page has no pin image")

            val media = originalOf(preview, html) ?: preview
            ResolvedPost(
                media,
                mimeOf(media),
                fieldsOf("title" to captionOf(html), "description" to meta(html, "og:description")),
            )
        }

        /**
         * The full-size copy of [preview], when the page names one.
         *
         * The original can carry a different extension than the preview — a PNG
         * served as JPEG at 736 pixels — so the extension comes from the page
         * too rather than from the preview URL.
         */
        fun originalOf(preview: String, html: String): String? {
            val stem = SIZED.find(preview)?.groupValues?.get(1) ?: return null
            val named = Regex(
                """i\.pinimg\.com/originals/${Regex.escape(stem)}\.[a-z0-9]{2,4}""",
                RegexOption.IGNORE_CASE,
            ).find(html)?.value ?: return null
            return "https://$named"
        }

        /** i.pinimg.com/<size>/ab/cd/ef/<hash>.<ext>, capturing the path without it. */
        private val SIZED = Regex(
            """^https://i\.pinimg\.com/[^/]+/((?:[0-9a-f]{2}/){3}[0-9a-f]{16,})\.[a-z0-9]{2,4}$""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Whether the page is a video pin.
         *
         * Every image pin carries `"videos":null`; an object there is a video.
         * A false positive costs the resolution and nothing else, so the loose
         * reading is the safe one.
         */
        fun isVideoPin(html: String): Boolean =
            VIDEOS.containsMatchIn(html) || html.contains("\"og:video\"", ignoreCase = true)

        private val VIDEOS = Regex(""""videos"\s*:\s*\{""")

        /**
         * The pin's own title, without the keyword tail Pinterest appends for
         * search engines ("A quilt | Quilt ideas, Quilt patterns").
         */
        fun captionOf(html: String): String? = meta(html, "og:title")
            ?.substringBefore(" | ")
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("Pinterest", ignoreCase = true) }

        /** Reads one meta tag, whichever order its attributes happen to be in. */
        private fun meta(html: String, key: String): String? {
            val tag = Regex(
                """<meta[^>]*(?:name|property)="${Regex.escape(key)}"[^>]*>""",
                RegexOption.IGNORE_CASE,
            ).find(html)?.value ?: return null

            val content = Regex("""\bcontent="([^"]*)"""", RegexOption.IGNORE_CASE)
                .find(tag)?.groupValues?.get(1) ?: return null

            return unescape(content).trim().takeIf { it.isNotBlank() }
        }

        private fun unescape(text: String): String = text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&nbsp;", " ")

        private fun mimeOf(url: String): String = when {
            url.endsWith(".png", ignoreCase = true) -> "image/png"
            url.endsWith(".webp", ignoreCase = true) -> "image/webp"
            url.endsWith(".gif", ignoreCase = true) -> "image/gif"
            else -> "image/jpeg"
        }
    }
}
