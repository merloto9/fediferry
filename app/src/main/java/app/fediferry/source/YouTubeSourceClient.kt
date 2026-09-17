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
package app.fediferry.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches a channel's community posts.
 *
 * There is no API for this: the YouTube Data API covers videos, channels,
 * playlists and comments and nothing else, so the posts page itself is the only
 * source. That makes every failure here a real possibility rather than a
 * theoretical one, and each is reported distinctly — a channel that does not
 * exist, a channel with posts turned off, and a page whose shape has changed
 * need completely different responses from the user.
 */
class YouTubeSourceClient(private val http: OkHttpClient) {

    suspend fun posts(handle: String): Result<List<SourcePost>> = withContext(Dispatchers.IO) {
        runCatching {
            val normalised = normaliseHandle(handle)
                ?: error("That does not look like a YouTube channel")

            val request = Request.Builder()
                .url("https://www.youtube.com/@$normalised/posts")
                .header("User-Agent", BROWSER_UA)
                // Without this YouTube may serve a consent interstitial instead
                // of the channel, and the blob would be missing for a reason
                // that has nothing to do with the channel.
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "SOCS=CAI;")
                .get()
                .build()

            val html = http.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> error("No channel called @$normalised")
                    !response.isSuccessful -> error("YouTube returned ${response.code}")
                    else -> response.body.string()
                }
            }

            val blob = YouTubePostParser.extractInitialData(html)
                ?: error("YouTube served a page this app cannot read")

            val posts = YouTubePostParser.parse(blob)
            // An empty list is ambiguous on its own, so say which it is.
            if (posts.isEmpty() && !blob.contains("backstagePostRenderer")) {
                error("@$normalised has no community posts, or they are not public")
            }
            posts
        }
    }

    /** Accepts a handle, an @handle, or any channel URL the user might paste. */
    fun normaliseHandle(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        URL_HANDLE.find(trimmed)?.let { return it.groupValues[1] }

        val bare = trimmed.removePrefix("@")
        return bare.takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c in "-_." } }
    }

    private companion object {
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/140.0 Mobile Safari/537.36"

        val URL_HANDLE = Regex("""youtube\.com/@([A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE)
    }
}
