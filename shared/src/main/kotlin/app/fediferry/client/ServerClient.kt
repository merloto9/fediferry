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
package app.fediferry.client

import app.fediferry.api.Api
import app.fediferry.api.ApiErrorBody
import app.fediferry.api.Changes
import app.fediferry.api.DeviceInfo
import app.fediferry.api.DeviceRegistration
import app.fediferry.api.Health
import app.fediferry.api.ProjectInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A failed call: [status] 0 means the server was not reached at all. */
class ServerException(val status: Int, val code: String, val args: Map<String, String> = emptyMap(), cause: Throwable? = null) :
    Exception(code, cause) {
    val unreachable: Boolean get() = status == 0
}

/**
 * Talks to a FediFerry server for one project. The token is sent with every
 * call but [health] and never logged.
 */
class ServerClient(
    baseUrl: String,
    private val token: String,
    private val deviceId: String?,
    http: OkHttpClient,
) {
    private val base: HttpUrl = normalise(baseUrl)
        ?: throw ServerException(0, "client.bad_address", mapOf("address" to baseUrl))

    // Long-polls wait on the server; the read timeout must outlast them.
    private val http = http.newBuilder().readTimeout(LONG_POLL_SECONDS + 15L, TimeUnit.SECONDS).build()

    suspend fun health(): Health = get("health", auth = false)

    suspend fun project(): ProjectInfo = get("project")

    suspend fun registerDevice(device: DeviceRegistration): DeviceInfo = post("devices", json.encodeToString(device))

    suspend fun devices(): List<DeviceInfo> = get("devices")

    suspend fun changes(since: Long, waitSeconds: Int = LONG_POLL_SECONDS): Changes =
        get("changes", auth = true, query = mapOf("since" to since.toString(), "wait" to waitSeconds.toString()))

    private suspend inline fun <reified T> get(path: String, auth: Boolean = true, query: Map<String, String> = emptyMap()): T =
        call(request(path, auth, query).get().build())

    private suspend inline fun <reified T> post(path: String, body: String): T =
        call(request(path, auth = true).post(body.toRequestBody(JSON_TYPE)).build())

    private fun request(path: String, auth: Boolean, query: Map<String, String> = emptyMap()): Request.Builder {
        val url = base.newBuilder().addPathSegments(Api.PREFIX.trimStart('/')).addPathSegments(path)
            .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        return Request.Builder().url(url).apply {
            if (auth) header("Authorization", "Bearer $token")
            deviceId?.let { header(Api.DEVICE_HEADER, it) }
        }
    }

    private suspend inline fun <reified T> call(request: Request): T = withContext(Dispatchers.IO) {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw ServerException(0, "client.unreachable", mapOf("reason" to (e.message ?: e.javaClass.simpleName)), e)
        }
        response.use { decode<T>(it) }
    }

    private inline fun <reified T> decode(response: Response): T {
        val body = response.body.string()
        if (!response.isSuccessful) {
            val error = runCatching { json.decodeFromString<ApiErrorBody>(body) }.getOrNull()
            throw ServerException(response.code, error?.code ?: "http.${response.code}", error?.args.orEmpty())
        }
        return try {
            json.decodeFromString(body)
        } catch (e: Exception) {
            throw ServerException(response.code, "client.unreadable", cause = e)
        }
    }

    companion object {
        const val LONG_POLL_SECONDS = 25
        private val JSON_TYPE = "application/json".toMediaType()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** "fediferry.example.org" or "http://10.0.2.2:8080/" → a base URL; null when it is not one. */
        fun normalise(address: String): HttpUrl? {
            val trimmed = address.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            return withScheme.toHttpUrlOrNull()
        }
    }
}
