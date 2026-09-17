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
package app.fediferry.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns a failed call to a user-configured endpoint into something the user can
 * act on.
 *
 * A bare status code is close to useless here: a 404 from one of these services
 * means either a mistyped URL or a model the service does not have, and those
 * need opposite fixes. The server says which; this passes that on.
 *
 * Only the `message` field of the error object is quoted, never the whole body —
 * a rejected request can echo the image back, and that has no business in a
 * toast.
 */
object ApiError {

    private val json = Json { ignoreUnknownKeys = true }

    /** Longest quoted explanation, so one service's essay cannot fill the screen. */
    private const val MAX_DETAIL = 180

    fun describe(label: String, code: Int, body: String): String {
        val detail = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.let { err ->
                runCatching { err.jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
                    ?: runCatching { err.jsonPrimitive.content }.getOrNull()
            }
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }

        val hint = when (code) {
            404 -> " — check the endpoint URL, and that the model exists"
            401, 403 -> " — check the API key"
            429 -> " — rate limited; a free tier usually has a daily cap"
            else -> ""
        }
        return if (detail != null) {
            "$label returned $code: ${detail.take(MAX_DETAIL)}$hint"
        } else {
            "$label returned $code$hint"
        }
    }
}
