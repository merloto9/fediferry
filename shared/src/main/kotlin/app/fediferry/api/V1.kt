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
package app.fediferry.api

import kotlinx.serialization.Serializable

/**
 * The contract between the FediFerry server and its clients, version 1, under
 * `/api/v1`. Both sides use these very types, so a change shows up as a
 * compile error on the side that has not caught up.
 *
 * Every call but [Health] carries `Authorization: Bearer <project token>`; the
 * token alone decides which project a call works on. Calls from a device also
 * carry `X-Device-Id` once it has registered.
 */
object Api {
    const val VERSION = 1
    const val PREFIX = "/api/v1"
    const val DEVICE_HEADER = "X-Device-Id"
}

/** `GET /health` — no token needed, so a client can check the address first. */
@Serializable
data class Health(val service: String = "fediferry", val version: String, val api: Int = Api.VERSION)

/** `GET /project` — the project the token belongs to. */
@Serializable
data class ProjectInfo(val id: String, val name: String, val rev: Long)

/** `POST /devices` — a device says who it is; repeating it updates the entry. */
@Serializable
data class DeviceRegistration(val id: String, val name: String, val platform: String, val appVersion: String)

@Serializable
data class DeviceInfo(
    val id: String,
    val name: String,
    val platform: String,
    val appVersion: String,
    val lastSeenAt: Long,
)

/**
 * `GET /changes?since=<rev>&wait=<seconds>` — what changed in the project after
 * [rev]-numbered point `since`. The call waits up to `wait` seconds for
 * something to change, so clients see each other's edits within moments
 * without polling hard. A client keeps [rev] and asks again from there.
 */
@Serializable
data class Changes(val rev: Long, val changed: List<Change>)

@Serializable
data class Change(val rev: Long, val type: String, val id: String, val deleted: Boolean = false)

/**
 * Every error body. [code] is stable and says what went wrong; the client
 * words it in its own language. [args] fill in the details.
 */
@Serializable
data class ApiErrorBody(val code: String, val args: Map<String, String> = emptyMap())

/** The error codes the server sends. */
object ErrorCodes {
    const val UNAUTHORIZED = "auth.unauthorized"
    const val NOT_FOUND = "not_found"
    const val BAD_REQUEST = "bad_request"
    const val DEVICE_REQUIRED = "device.required"
}
