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
package app.fediferry.module.reddit

import app.fediferry.BuildConfig
import app.fediferry.data.model.ContentSource
import app.fediferry.link.LinkResolver
import app.fediferry.link.ResolvedPost
import app.fediferry.link.fieldsOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Resolves a Reddit post link to the picture or video behind it.
 *
 * Reads the post's embed page on `embed.reddit.com` — the surface Reddit
 * publishes for showing a post on other sites — and needs no account. Reddit's
 * JSON API and its ordinary post pages both refuse anonymous requests: the API
 * with a 403, the pages with a JavaScript challenge meant to keep programs out.
 * Neither is worked around. The embed page answers because answering strangers
 * is its job.
 *
 * The share sheet usually hands over a `/r/<sub>/s/<code>` link, which only
 * redirects to the post; the redirect is followed without reading the page it
 * lands on. The embed page must be asked for at the post's own subreddit, so a
 * link without one — `redd.it/<id>` — is looked up once at a placeholder,
 * whose error page names the right address.
 *
 * - An image post attaches the original from `i.redd.it`.
 * - A gallery attaches its first picture, since an item holds one. The page
 *   shows only previews; the original shares their media id and is used once a
 *   HEAD request confirms it exists, the largest preview otherwise.
 * - A GIF comes across as the mp4 Reddit converts it to, a fraction of the size
 *   and still animated.
 * - A video attaches Reddit's packaged mp4, which carries the sound. The
 *   `v.redd.it` stream alone is silent, and posting a video with its sound gone
 *   without saying so is the failure the 9GAG resolver was written to avoid; a
 *   video without a packaged copy is declined instead.
 * - Text and link posts have nothing to attach and are declined, leaving the
 *   screenshot path.
 */
class RedditResolver(private val http: OkHttpClient) : LinkResolver {

    override val source = ContentSource.REDDIT

    /** For the share link's redirect, which is wanted as an address, not a page. */
    private val noRedirects: OkHttpClient by lazy {
        http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

    override fun handles(url: String): Boolean = linkOf(url) != null

    override suspend fun resolve(url: String): Result<ResolvedPost> =
        withContext(Dispatchers.IO) {
            runCatching {
                val link = linkOf(url) ?: error("Not a Reddit post link")
                directMediaOf(link)?.let { return@runCatching it }

                val target = postOf(link)
                    ?: postOf(followShareLink(link))
                    ?: error("Reddit share link did not lead to a post")

                var page = embed(target)
                // Asked at the wrong subreddit, the embed page renders nothing
                // but still names the post's real address.
                val canonical = canonicalOf(page)?.let(::postOf)
                if (canonical != null && !canonical.owner.equals(target.owner, ignoreCase = true)) {
                    page = embed(canonical)
                }

                val post = parse(page).getOrThrow()
                val original = post.takeIf { it.mimeType.startsWith("image/") }
                    ?.let { originalOf(it.mediaUrl) }
                if (original != null && exists(original)) post.copy(mediaUrl = original) else post
            }
        }

    private fun embed(post: PostRef): String {
        val request = Request.Builder()
            .url("https://embed.reddit.com/${post.path}")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html")
            .get()
            .build()
        return http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Reddit returned ${response.code}" }
            response.peekBody(MAX_HTML_BYTES).string()
        }
    }

    /** The address a share link redirects to, without fetching what is there. */
    private fun followShareLink(link: String): String {
        var current = link
        repeat(MAX_REDIRECTS) {
            val request = Request.Builder()
                .url(current)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            val next = noRedirects.newCall(request).execute().use { response ->
                if (!response.isRedirect) return current
                response.header("Location")?.let { response.request.url.resolve(it)?.toString() }
            } ?: return current
            if (postOf(next) != null) return next
            current = next
        }
        return current
    }

    private fun exists(url: String): Boolean = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .head()
            .build()
        http.newCall(request).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    /**
     * A post, by where it lives — `r/<sub>` or `user/<name>` — and its id. The
     * owner is null when the link did not say.
     */
    data class PostRef(val owner: String?, val id: String) {
        /** The embed page's path. Any subreddit does for looking up the real one. */
        val path: String get() = "${owner ?: "r/$PLACEHOLDER_SUBREDDIT"}/comments/$id/"
    }

    companion object {
        /** Reddit asks for `<platform>:<app id>:<version>` and a way to reach the author. */
        private val USER_AGENT =
            "android:app.fediferry:${BuildConfig.VERSION_NAME} (+https://github.com/merloto9/fediferry)"

        private const val PLACEHOLDER_SUBREDDIT = "all"

        private const val MAX_REDIRECTS = 5

        /** An embed page is a few hundred kilobytes; the tags read sit early in it. */
        private const val MAX_HTML_BYTES = 2L * 1024 * 1024

        private val json = Json { ignoreUnknownKeys = true }

        private const val HOST = """https?://(?:[a-z0-9-]+\.)?reddit\.com"""

        /** /r/<sub>/comments/<id>, and a profile's /user/<name>/comments/<id>. */
        private val POST = Regex(
            """$HOST/(r|u|user)/([A-Za-z0-9_-]+)/comments/([a-z0-9]+)""",
            RegexOption.IGNORE_CASE,
        )

        /** Links naming the post alone: /comments/<id>, /gallery/<id>, redd.it/<id>. */
        private val BARE = Regex(
            """(?:$HOST/(?:comments|gallery)|https?://redd\.it)/([a-z0-9]+)""",
            RegexOption.IGNORE_CASE,
        )

        /** The share sheet's link, which redirects to the post. */
        private val SHARE = Regex(
            """$HOST/(?:r|u|user)/[A-Za-z0-9_-]+/s/[A-Za-z0-9]+""",
            RegexOption.IGNORE_CASE,
        )

        /** A picture linked directly, which needs no page at all. */
        private val DIRECT = Regex(
            """https?://i\.redd\.it/[A-Za-z0-9]+\.(?:jpe?g|png|gif|webp)""",
            RegexOption.IGNORE_CASE,
        )

        /** The URL to work from, picked out of whatever text came with the share. */
        fun linkOf(url: String): String? {
            val text = url.trim()
            return (SHARE.find(text) ?: POST.find(text) ?: BARE.find(text) ?: DIRECT.find(text))?.value
        }

        fun postOf(url: String): PostRef? {
            POST.find(url)?.let { m ->
                val (kind, name, id) = m.destructured
                val owner = if (kind.equals("r", ignoreCase = true)) "r/$name" else "user/$name"
                return PostRef(owner, id.lowercase())
            }
            return BARE.find(url)?.let { PostRef(null, it.groupValues[1].lowercase()) }
        }

        fun directMediaOf(link: String): ResolvedPost? =
            DIRECT.matchEntire(link)?.let { ResolvedPost(link, mimeOf(link)) }

        /** The post's own address, which the page names even when it will not render it. */
        fun canonicalOf(html: String): String? =
            Regex("""<div id="canonical-url-updater"[^>]*\bvalue="([^"]*)"""")
                .find(html)?.groupValues?.get(1)?.let(::unescape)

        /**
         * Pulls the media out of an embed page.
         *
         * Pure, so each post type is covered by tests against captured pages
         * rather than by hitting the network.
         */
        fun parse(html: String): Result<ResolvedPost> = runCatching {
            val screenview = screenviewOf(html)
            val post = screenview?.get("post")?.jsonObject
                ?: error("Reddit page has no post")
            val type = post.string("type")
            val url = post.string("url")
            val fields = fieldsOf(
                "title" to captionOf(html),
                "subreddit" to runCatching { screenview["subreddit"]?.jsonObject?.string("name") }.getOrNull(),
            )

            val media = when (type) {
                "video" -> packagedVideoOf(html)
                    ?.let { ResolvedPost(it, "video/mp4", fields) }
                    ?: error("Reddit video has no copy with its sound")

                "gif" -> gifVideoOf(html)?.let { ResolvedPost(it, "video/mp4", fields) }
                    ?: url?.takeIf { DIRECT.matches(it) }?.let { ResolvedPost(it, "image/gif", fields) }

                "gallery" -> firstGalleryImageOf(html)?.let { ResolvedPost(it, mimeOf(it), fields) }

                else -> null
            }
            media
                // Whatever the type is called, a directly linked picture is the post.
                ?: url?.takeIf { type != "video" && IMAGE_URL.containsMatchIn(it) }
                    ?.let { ResolvedPost(it, mimeOf(it), fields) }
                ?: error("Reddit ${type ?: "unknown"} post has no picture to attach")
        }

        /** Any picture addressed by its extension, `i.redd.it` or elsewhere. */
        private val IMAGE_URL = Regex(
            """^https://[^\s?#]+\.(?:jpe?g|png|gif|webp)(?:[?#]|$)""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * The original behind a gallery preview. Previews are named
         * `<slug>-v0-<media id>.<ext>`, and the original is `i.redd.it/<media id>.<ext>`.
         * The page never names it, so the caller checks it exists before using it.
         *
         * The `-v0-` is required: a GIF's mp4 is served from the same host under
         * the bare media id, and must not be "upgraded" back to the GIF.
         */
        fun originalOf(preview: String): String? =
            GALLERY_PREVIEW.find(preview)
                ?.let { "https://i.redd.it/${it.groupValues[1]}.${it.groupValues[2]}" }

        private val GALLERY_PREVIEW = Regex(
            """^https://preview\.redd\.it/[^/?#]*-v0-([a-z0-9]+)\.(jpe?g|png|gif|webp)\?""",
            RegexOption.IGNORE_CASE,
        )

        /** The small JSON blob Reddit's own analytics read: the post's type and media. */
        private fun screenviewOf(html: String): JsonObject? {
            val raw = Regex("""<shreddit-screenview-data\s+data="([^"]*)"""")
                .find(html)?.groupValues?.get(1) ?: return null
            return runCatching { json.parseToJsonElement(unescape(raw)).jsonObject }.getOrNull()
        }

        /** The post title, which the embed page shows as its only heading. */
        fun captionOf(html: String): String? =
            Regex("""<h1[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")
                ?.let(::unescape)
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        /** The tallest packaged mp4 — the download Reddit muxes the audio into. */
        private fun packagedVideoOf(html: String): String? {
            val raw = playerOf(html)?.let { attribute(it, "packaged-media-json") } ?: return null
            val permutations = runCatching {
                json.parseToJsonElement(raw).jsonObject["playbackMp4s"]!!
                    .jsonObject["permutations"]!!.jsonArray
            }.getOrNull() ?: return null
            return permutations
                .mapNotNull { runCatching { it.jsonObject["source"]!!.jsonObject }.getOrNull() }
                .maxByOrNull { source ->
                    runCatching {
                        source["dimensions"]!!.jsonObject["height"]!!.jsonPrimitive.int
                    }.getOrDefault(0)
                }
                ?.string("url")
        }

        /** The mp4 a GIF post's player streams in its place. */
        private fun gifVideoOf(html: String): String? =
            playerOf(html)
                ?.let { attribute(it, "src") }
                ?.takeIf { it.contains("format=mp4") }

        private fun playerOf(html: String): String? =
            Regex("""<shreddit-player\b[^>]*>""").find(html)?.value

        /** The first picture in a gallery, at the widest size the page offers. */
        private fun firstGalleryImageOf(html: String): String? {
            val start = html.indexOf("<gallery-carousel")
            if (start < 0) return null
            val img = Regex("""<img\b[^>]*>""").find(html, start)?.value ?: return null
            val widest = attribute(img, "srcset")
                ?.split(", ")
                ?.mapNotNull { entry ->
                    val parts = entry.trim().split(" ")
                    val width = parts.getOrNull(1)?.removeSuffix("w")?.toIntOrNull()
                    width?.let { parts[0] to it }
                }
                ?.maxByOrNull { it.second }
                ?.first
            return widest ?: attribute(img, "src")
        }

        /** One attribute of a tag, unescaped. The leading space keeps `src` from matching `data-src`. */
        private fun attribute(tag: String, name: String): String? =
            Regex("""\s${Regex.escape(name)}="([^"]*)"""").find(tag)?.groupValues?.get(1)?.let(::unescape)

        private fun JsonObject.string(key: String): String? =
            runCatching { get(key)?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }

        /** `&amp;` last, so an escaped entity is not unescaped twice. */
        private fun unescape(text: String): String = text
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")

        private fun mimeOf(url: String): String {
            val path = url.substringBefore('?').lowercase()
            return when {
                path.endsWith(".png") -> "image/png"
                path.endsWith(".webp") -> "image/webp"
                path.endsWith(".gif") -> "image/gif"
                else -> "image/jpeg"
            }
        }
    }
}
