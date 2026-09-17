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
package app.fediferry.mastodon

import app.fediferry.data.model.Visibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * The slice of the Mastodon API this app needs.
 *
 * Request and response bodies are never logged: they carry post text and, on the
 * token endpoints, credentials.
 */
class MastodonClient(private val http: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    // --- OAuth ------------------------------------------------------------

    /** `POST /api/v1/apps` — once per instance, result cached in `instance_apps`. */
    suspend fun registerApp(
        instance: String,
        clientName: String,
        redirectUri: String,
        scopes: String,
        website: String?,
    ): AppRegistration = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_name", clientName)
            .add("redirect_uris", redirectUri)
            .add("scopes", scopes)
            .apply { if (website != null) add("website", website) }
            .build()

        val request = Request.Builder()
            .url(base(instance).addPathSegments("api/v1/apps").build())
            .post(form)
            .build()

        decode(execute(request))
    }

    fun authorizeUrl(
        instance: String,
        clientId: String,
        redirectUri: String,
        scopes: String,
        state: String,
    ): String = base(instance)
        .addPathSegments("oauth/authorize")
        .addQueryParameter("client_id", clientId)
        .addQueryParameter("redirect_uri", redirectUri)
        .addQueryParameter("response_type", "code")
        .addQueryParameter("scope", scopes)
        .addQueryParameter("state", state)
        .build()
        .toString()

    /** `POST /oauth/token` — exchanges the authorization code for an access token. */
    suspend fun exchangeCode(
        instance: String,
        clientId: String,
        clientSecret: String,
        redirectUri: String,
        code: String,
        scopes: String,
    ): TokenResponse = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("redirect_uri", redirectUri)
            .add("code", code)
            .add("scope", scopes)
            .build()

        val request = Request.Builder()
            .url(base(instance).addPathSegments("oauth/token").build())
            .post(form)
            .build()

        decode(execute(request))
    }

    suspend fun verifyCredentials(instance: String, token: String): CredentialAccount =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(base(instance).addPathSegments("api/v1/accounts/verify_credentials").build())
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            decode(execute(request))
        }

    // --- Posting ----------------------------------------------------------

    /**
     * `POST /api/v2/media`, then polls `GET /api/v1/media/:id` until the instance
     * finishes processing. A 202 means "accepted, not ready"; attaching such an
     * id to a status immediately is what produces posts with a missing image.
     */
    suspend fun uploadMedia(
        instance: String,
        token: String,
        bytes: ByteArray,
        mimeType: String,
        fileName: String,
        description: String?,
    ): MediaAttachment = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                fileName,
                bytes.toRequestBody(mimeType.toMediaTypeOrFallback()),
            )
            .apply { if (!description.isNullOrBlank()) addFormDataPart("description", description) }
            .build()

        val request = Request.Builder()
            .url(base(instance).addPathSegments("api/v2/media").build())
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        val response = execute(request, acceptCodes = setOf(200, 202))
        val attachment: MediaAttachment = decode(response)

        if (response.code == 202) awaitMediaReady(instance, token, attachment.id) else attachment
    }

    private suspend fun awaitMediaReady(
        instance: String,
        token: String,
        mediaId: String,
    ): MediaAttachment {
        var wait = INITIAL_MEDIA_POLL_MS
        repeat(MEDIA_POLL_ATTEMPTS) {
            delay(wait)
            val request = Request.Builder()
                .url(base(instance).addPathSegments("api/v1/media/$mediaId").build())
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            val response = execute(request, acceptCodes = setOf(200, 206))
            if (response.code == 200) return decode(response)
            response.close()
            wait = (wait * 2).coerceAtMost(MAX_MEDIA_POLL_MS)
        }
        throw MastodonException(
            "Instance is still processing the attachment",
            retryable = true,
        )
    }

    /**
     * `POST /api/v1/statuses`. [idempotencyKey] is derived from the item id, so a
     * worker retry after an ambiguous failure cannot produce a second post.
     */
    suspend fun postStatus(
        instance: String,
        token: String,
        text: String,
        mediaIds: List<String>,
        visibility: Visibility,
        contentWarning: String?,
        idempotencyKey: String,
        scheduledAtIso: String? = null,
    ): PostedStatus = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("status", text)
            .add("visibility", visibility.api)
            .apply {
                mediaIds.forEach { add("media_ids[]", it) }
                if (!contentWarning.isNullOrBlank()) add("spoiler_text", contentWarning)
                if (scheduledAtIso != null) add("scheduled_at", scheduledAtIso)
            }
            .build()

        val request = Request.Builder()
            .url(base(instance).addPathSegments("api/v1/statuses").build())
            .header("Authorization", "Bearer $token")
            .header("Idempotency-Key", idempotencyKey)
            .post(form)
            .build()

        decode(execute(request))
    }

    // --- plumbing ---------------------------------------------------------

    private fun base(instance: String): HttpUrl.Builder {
        val host = instance.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        require(host.isNotBlank()) { "instance host is blank" }
        return "https://$host".toHttpUrl().newBuilder()
    }

    private fun execute(request: Request, acceptCodes: Set<Int> = setOf(200)): Response {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw MastodonException("Network error: ${e.message}", retryable = true, cause = e)
        }
        if (response.code in acceptCodes) return response

        val code = response.code
        response.close()
        throw MastodonException(
            message = describe(code),
            code = code,
            retryable = code == 429 || code in 500..599 || code == 408,
        )
    }

    private inline fun <reified T> decode(response: Response): T = response.use {
        val body = it.body.string()
        try {
            json.decodeFromString<T>(body)
        } catch (e: Exception) {
            // The body itself is not included: it may contain post text.
            throw MastodonException("Unreadable response from instance", it.code, cause = e)
        }
    }

    private fun describe(code: Int): String = when (code) {
        401 -> "Not authorised — reconnect the account"
        403 -> "The instance refused this post"
        404 -> "Endpoint not found — is this a Mastodon instance?"
        413 -> "Attachment too large for this instance"
        422 -> "The instance rejected the post contents"
        429 -> "Rate limited by the instance"
        in 500..599 -> "Instance error ($code)"
        else -> "Unexpected response ($code)"
    }

    private fun String.toMediaTypeOrFallback() =
        runCatching { toMediaType() }.getOrElse { "application/octet-stream".toMediaType() }

    private companion object {
        const val INITIAL_MEDIA_POLL_MS = 700L
        const val MAX_MEDIA_POLL_MS = 5_000L
        const val MEDIA_POLL_ATTEMPTS = 8
    }
}
