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

import app.fediferry.api.ImportAccountRequest
import app.fediferry.api.AltSuggestion
import app.fediferry.api.CreatePost
import app.fediferry.api.DeriveRequest
import app.fediferry.api.LockDto
import app.fediferry.api.MediaAssetDto
import app.fediferry.api.PostDto
import app.fediferry.api.PostPatch
import app.fediferry.api.CompleteRequest
import app.fediferry.api.ChannelPatch
import app.fediferry.api.ChannelDto
import app.fediferry.api.AuthorizeResponse
import app.fediferry.api.AuthorizeRequest
import kotlinx.serialization.json.JsonObject
import app.fediferry.api.SettingsEntryDto
import app.fediferry.api.SecretInput
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import app.fediferry.api.TagDto
import app.fediferry.api.LibraryPage
import app.fediferry.api.LibraryItemPatch
import app.fediferry.api.LibraryItemDto
import app.fediferry.api.IngestResult
import app.fediferry.api.FolderInput
import app.fediferry.api.FolderDto
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

    // --- library -------------------------------------------------------------

    /**
     * Sends one share. [shareId] is made on the phone when the share arrives, so
     * a retried upload is recognised and returns the same item.
     */
    suspend fun ingest(
        shareId: String,
        mode: String,
        link: String?,
        text: String?,
        file: ByteArray?,
        mime: String?,
        capturedAt: Long,
    ): IngestResult {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            addFormDataPart("mode", mode)
            addFormDataPart("capturedAt", capturedAt.toString())
            link?.let { addFormDataPart("link", it) }
            text?.let { addFormDataPart("text", it) }
            if (file != null) {
                val type = (mime ?: "application/octet-stream").toMediaTypeOrNull()
                addFormDataPart("file", "share", file.toRequestBody(type))
            }
        }.build()
        return call(request("ingest/$shareId", auth = true).put(body).build())
    }

    suspend fun library(
        folder: String? = null,
        unsorted: Boolean = false,
        tag: String? = null,
        query: String? = null,
        before: Long? = null,
        limit: Int = 60,
    ): LibraryPage = get(
        "library/items",
        query = buildMap {
            folder?.let { put("folder", it) }
            if (unsorted) put("unsorted", "true")
            tag?.let { put("tag", it) }
            query?.takeIf { it.isNotBlank() }?.let { put("q", it) }
            before?.let { put("before", it.toString()) }
            put("limit", limit.toString())
        },
    )

    suspend fun libraryItem(id: String): LibraryItemDto = get("library/items/$id")

    suspend fun patchLibraryItem(id: String, patch: LibraryItemPatch): LibraryItemDto =
        send("library/items/$id", "PATCH", json.encodeToString(patch))

    suspend fun deleteLibraryItem(id: String) = sendNoContent("library/items/$id", "DELETE")

    suspend fun folders(): List<FolderDto> = get("library/folders")

    suspend fun createFolder(input: FolderInput): FolderDto = post("library/folders", json.encodeToString(input))

    suspend fun updateFolder(id: String, input: FolderInput): FolderDto =
        send("library/folders/$id", "PATCH", json.encodeToString(input))

    suspend fun deleteFolder(id: String) = sendNoContent("library/folders/$id", "DELETE")

    suspend fun tags(): List<TagDto> = get("tags")

    // --- posts -----------------------------------------------------------------

    suspend fun posts(stage: String): List<PostDto> = get("posts", query = mapOf("stage" to stage))

    suspend fun post(id: String): PostDto = get("posts/$id")

    suspend fun createPost(request: CreatePost): PostDto = post("posts", json.encodeToString(request))

    /** Changes a draft this device holds the lock on; [version] is the one the change was made on. */
    suspend fun patchPost(id: String, version: Long, patch: PostPatch): PostDto =
        call(request("posts/$id", auth = true).header("If-Match", version.toString())
            .method("PATCH", json.encodeToString(patch).toRequestBody(JSON_TYPE)).build())

    suspend fun deletePost(id: String) = sendNoContent("posts/$id", "DELETE")

    /** Takes or renews the edit lock; [force] takes it from another device. */
    suspend fun lockPost(id: String, force: Boolean = false): LockDto =
        call(request("posts/$id/lock", auth = true, query = if (force) mapOf("force" to "true") else emptyMap())
            .post("".toRequestBody(JSON_TYPE)).build())

    suspend fun unlockPost(id: String) = sendNoContent("posts/$id/lock", "DELETE")

    suspend fun suggestAlt(postId: String, position: Int): AltSuggestion = post("posts/$postId/media/$position/alt-suggestion", "{}")

    /** A new picture made from [assetId]: cropped and/or cleaned. The original stays as it is. */
    suspend fun derive(assetId: String, request: DeriveRequest): MediaAssetDto = post("media/$assetId/derive", json.encodeToString(request))

    // --- channels --------------------------------------------------------------

    suspend fun channels(): List<ChannelDto> = get("channels")

    suspend fun patchChannel(id: String, patch: ChannelPatch): ChannelDto =
        send("channels/${encode(id)}", "PATCH", json.encodeToString(patch))

    suspend fun deleteChannel(id: String) = sendNoContent("channels/${encode(id)}", "DELETE")

    suspend fun refreshCapabilities(id: String): ChannelDto = send("channels/${encode(id)}/refresh-capabilities", "POST", "{}")

    suspend fun authorizeMastodon(instance: String, redirectUri: String): AuthorizeResponse =
        post("channels/mastodon/authorize", json.encodeToString(AuthorizeRequest(instance, redirectUri)))

    suspend fun completeMastodon(code: String, state: String): ChannelDto =
        post("channels/mastodon/complete", json.encodeToString(CompleteRequest(code, state)))

    suspend fun importMastodon(instance: String, accessToken: String, channelId: String?): ChannelDto =
        post("channels/mastodon/import", json.encodeToString(ImportAccountRequest(instance, accessToken, channelId)))

    // --- project settings ------------------------------------------------------

    suspend fun settings(kind: String): List<SettingsEntryDto> = get("settings/$kind")

    suspend fun putSetting(kind: String, id: String, data: JsonObject): SettingsEntryDto =
        send("settings/$kind/${encode(id)}", "PUT", json.encodeToString(data))

    suspend fun deleteSetting(kind: String, id: String) = sendNoContent("settings/$kind/${encode(id)}", "DELETE")

    /** Names of the secrets the server holds; never their values. */
    suspend fun secretIds(): List<String> = get("secrets")

    suspend fun putSecret(id: String, value: String) =
        sendNoContentWithBody("secrets/${encode(id)}", "PUT", json.encodeToString(SecretInput(value)))

    suspend fun deleteSecret(id: String) = sendNoContent("secrets/${encode(id)}", "DELETE")

    /** An id as one path segment: hashtags and account ids may hold characters a path cannot. */
    private fun encode(id: String): String = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    private suspend fun sendNoContentWithBody(path: String, method: String, body: String) {
        withContext(Dispatchers.IO) {
            val response = try {
                http.newCall(request(path, auth = true).method(method, body.toRequestBody(JSON_TYPE)).build()).execute()
            } catch (e: IOException) {
                throw ServerException(0, "client.unreachable", mapOf("reason" to (e.message ?: e.javaClass.simpleName)), e)
            }
            response.use { if (!it.isSuccessful) decode<Unit>(it) }
        }
    }

    /** The address of a stored file; fetching it needs the project token, as every call does. */
    fun mediaUrl(assetId: String): String = url("media/$assetId").toString()

    fun thumbnailUrl(assetId: String, width: Int = 400): String = url("media/$assetId/thumb", mapOf("w" to width.toString())).toString()

    /** Whether [url] points at this server, so a shared HTTP client knows to send the token there. */
    fun isServerUrl(url: HttpUrl): Boolean = url.host == base.host && url.port == base.port

    private suspend inline fun <reified T> send(path: String, method: String, body: String): T =
        call(request(path, auth = true).method(method, body.toRequestBody(JSON_TYPE)).build())

    private suspend fun sendNoContent(path: String, method: String) {
        withContext(Dispatchers.IO) {
            val response = try {
                http.newCall(request(path, auth = true).method(method, null).build()).execute()
            } catch (e: IOException) {
                throw ServerException(0, "client.unreachable", mapOf("reason" to (e.message ?: e.javaClass.simpleName)), e)
            }
            response.use { if (!it.isSuccessful) decode<Unit>(it) }
        }
    }

    private suspend inline fun <reified T> get(path: String, auth: Boolean = true, query: Map<String, String> = emptyMap()): T =
        call(request(path, auth, query).get().build())

    private suspend inline fun <reified T> post(path: String, body: String): T =
        call(request(path, auth = true).post(body.toRequestBody(JSON_TYPE)).build())

    private fun url(path: String, query: Map<String, String> = emptyMap()): HttpUrl =
        base.newBuilder().addPathSegments(Api.PREFIX.trimStart('/')).addEncodedPathSegments(path)
            .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()

    private fun request(path: String, auth: Boolean, query: Map<String, String> = emptyMap()): Request.Builder {
        val url = url(path, query)
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
