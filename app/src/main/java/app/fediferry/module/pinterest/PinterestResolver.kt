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
 * Video pins are declined rather than resolved. Their `og:image` is a cover
 * frame, and posting a still of a video without saying so is the failure the
 * 9GAG resolver was written to avoid. Declining leaves the screenshot path,
 * exactly as an unrecognised link would.
 */
class PinterestResolver(private val http: OkHttpClient) : LinkResolver {

    override val source = ContentSource.PINTEREST

    override fun handles(url: String): Boolean = linkOf(url) != null

    override suspend fun resolve(url: String): Result<ResolvedPost> =
        withContext(Dispatchers.IO) {
            runCatching {
                val link = linkOf(url) ?: error("Not a Pinterest pin link")
                val request = Request.Builder()
                    .url(link)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html")
                    .get()
                    .build()

                // A pin.it link redirects to the pin page; OkHttp follows it.
                val html = http.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Pinterest returned ${response.code}" }
                    response.peekBody(MAX_HTML_BYTES).string()
                }
                parse(html).getOrThrow()
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

        /** The URL to fetch, picked out of whatever text came with the share. */
        fun linkOf(url: String): String? =
            (PIN.find(url.trim()) ?: SHORT.find(url.trim()))?.value

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
