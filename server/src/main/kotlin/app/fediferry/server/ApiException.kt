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
package app.fediferry.server

import app.fediferry.api.ApiErrorBody
import app.fediferry.api.ErrorCodes
import io.ktor.http.HttpStatusCode

/**
 * A failure the client should hear about, as a stable [code] it can word in
 * its own language. Never carries a token or post text.
 */
class ApiException(
    val status: HttpStatusCode,
    val code: String,
    val args: Map<String, String> = emptyMap(),
) : Exception(code) {
    val body: ApiErrorBody get() = ApiErrorBody(code, args)

    companion object {
        fun notFound(what: String) = ApiException(HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND, mapOf("what" to what))
        fun badRequest(reason: String) = ApiException(HttpStatusCode.BadRequest, ErrorCodes.BAD_REQUEST, mapOf("reason" to reason))
    }
}
