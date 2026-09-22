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
package app.fediferry.link

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Resolves a 9gag.com/gag/<id> link to the media behind it.
 *
 * Uses 9GAG's own post endpoint rather than the page's Open Graph tags. That is
 * a deliberate trade: `og:image` is simpler and more clearly a public surface,
 * but for an animated post it returns a *still frame* and the page carries no
 * `og:video`, so there is no way to even tell that the animation was lost. The
 * endpoint reports the post type and links the mp4, so a GIF meme stays a GIF.
 */
class NineGagResolver(private val http: OkHttpClient) : LinkResolver {

    override val serviceName = "9GAG"

    override fun handles(url: String): Boolean = idOf(url) != null

    override suspend fun resolve(url: String): Result<ResolvedPost> =
        withContext(Dispatchers.IO) {
            runCatching {
                val id = idOf(url) ?: error("Not a 9GAG post link")
                val request = Request.Builder()
                    .url("https://9gag.com/v1/post?id=$id")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()

                val body = http.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "9GAG returned ${response.code}" }
                    response.body.string()
                }
                parse(body).getOrThrow()
            }
        }

    companion object {
        private const val USER_AGENT = "FediFerry (+https://github.com/merloto9/fediferry)"

        private val json = Json { ignoreUnknownKeys = true }

        /** Matches 9gag.com/gag/<id>, with or without host prefix, path or query. */
        private val LINK = Regex(
            """https?://(?:www\.|m\.)?9gag\.com/gag/([A-Za-z0-9]+)""",
            RegexOption.IGNORE_CASE,
        )

        fun idOf(url: String): String? = LINK.find(url.trim())?.groupValues?.get(1)

        /**
         * Pulls the best media out of a post payload.
         *
         * Pure, so the awkward cases — an animated post, a post whose media is
         * hosted elsewhere — are covered by tests against captured responses
         * rather than by hitting the network.
         */
        fun parse(payload: String): Result<ResolvedPost> = runCatching {
            val post = json.parseToJsonElement(payload)
                .jsonObject["data"]?.jsonObject?.get("post")?.jsonObject
                ?: error("9GAG returned no post")

            val images = post["images"]?.jsonObject ?: error("9GAG returned no media")
            fun url(key: String): String? =
                images[key]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNullSafe()
                    ?.takeIf { it.isNotBlank() }

            val type = post["type"]?.jsonPrimitive?.contentOrNullSafe()
            val caption = post["title"]?.jsonPrimitive?.contentOrNullSafe()?.takeIf { it.isNotBlank() }

            // An animated post's still frames are the wrong thing to post, so the
            // mp4 wins when there is one.
            val video = url("image460sv")
            val still = url("image700") ?: url("image460")

            val media = when {
                video != null -> ResolvedPost(video, "video/mp4", caption)
                still != null -> ResolvedPost(still, mimeOf(still), caption)
                else -> error("9GAG post $type has no media to attach")
            }
            media
        }

        private fun mimeOf(url: String): String = when {
            url.endsWith(".png", ignoreCase = true) -> "image/png"
            url.endsWith(".webp", ignoreCase = true) -> "image/webp"
            url.endsWith(".gif", ignoreCase = true) -> "image/gif"
            url.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
            else -> "image/jpeg"
        }

        private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
            runCatching { content }.getOrNull()
    }
}
