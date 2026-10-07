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

import app.fediferry.api.ChannelCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Signing in to a Mastodon instance and reading what it can take, without
 * anything Android: the FediFerry server does this for its projects. Errors
 * are [MastodonException]s whose message is a stable code.
 */
class MastodonAccounts(private val http: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Registration(val client_id: String, val client_secret: String)

    @Serializable
    private data class Token(val access_token: String)

    suspend fun registerApp(instance: String, clientName: String, redirectUri: String, scopes: String, website: String?): Registration =
        post(instance, "api/v1/apps", FormBody.Builder()
            .add("client_name", clientName)
            .add("redirect_uris", redirectUri)
            .add("scopes", scopes)
            .apply { website?.let { add("website", it) } }
            .build())

    fun authorizeUrl(instance: String, clientId: String, redirectUri: String, scopes: String, state: String): String =
        base(instance).addPathSegments("oauth/authorize")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("scope", scopes)
            .addQueryParameter("state", state)
            .build().toString()

    suspend fun exchangeCode(instance: String, clientId: String, clientSecret: String, redirectUri: String, code: String, scopes: String): String =
        post<Token>(instance, "oauth/token", FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("redirect_uri", redirectUri)
            .add("code", code)
            .add("scope", scopes)
            .build()).access_token

    suspend fun verifyCredentials(instance: String, token: String): CredentialAccount =
        get(instance, "api/v1/accounts/verify_credentials", token)

    /** What the instance accepts, from `/api/v2/instance`; Mastodon's defaults where it does not say. */
    suspend fun capabilities(instance: String, now: Long = System.currentTimeMillis()): ChannelCapabilities =
        parseCapabilities(get<JsonObject>(instance, "api/v2/instance", null), now)

    private suspend inline fun <reified T> get(instance: String, path: String, token: String?): T = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(base(instance).addPathSegments(path).build())
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .get().build()
        call(request)
    }

    private suspend inline fun <reified T> post(instance: String, path: String, body: FormBody): T = withContext(Dispatchers.IO) {
        call(Request.Builder().url(base(instance).addPathSegments(path).build()).post(body).build())
    }

    private inline fun <reified T> call(request: Request): T {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw MastodonException("mastodon.unreachable", retryable = true, cause = e)
        }
        response.use {
            val text = it.body.string()
            if (!it.isSuccessful) {
                throw MastodonException(
                    when (it.code) {
                        401, 403 -> "mastodon.unauthorized"
                        404 -> "mastodon.not_found"
                        422 -> "mastodon.rejected"
                        429 -> "mastodon.rate_limited"
                        else -> "mastodon.http_${it.code}"
                    },
                    it.code,
                    retryable = it.code == 429 || it.code >= 500,
                )
            }
            return try {
                json.decodeFromString(text)
            } catch (e: Exception) {
                throw MastodonException("mastodon.unreadable", it.code, cause = e)
            }
        }
    }

    companion object {
        /** "@me@mastodon.social", "https://mastodon.social/" → "mastodon.social". */
        fun normaliseInstance(raw: String): String = raw.trim()
            .removePrefix("https://").removePrefix("http://")
            .trimEnd('/').substringBefore('/')
            .removePrefix("@").substringAfterLast('@')
            .lowercase()

        private fun base(instance: String): HttpUrl.Builder {
            val host = normaliseInstance(instance)
            require(host.isNotBlank()) { "instance host is blank" }
            return "https://$host".toHttpUrl().newBuilder()
        }

        /** Reads an `/api/v2/instance` reply; every value it lacks keeps its default. */
        fun parseCapabilities(instance: JsonObject, now: Long): ChannelCapabilities {
            val defaults = ChannelCapabilities()
            val config = instance["configuration"]?.jsonObject
            val statuses = config?.get("statuses")?.jsonObject
            val media = config?.get("media_attachments")?.jsonObject
            val polls = config?.get("polls")?.jsonObject
            fun JsonObject?.int(key: String) = this?.get(key)?.jsonPrimitive?.intOrNull
            fun JsonObject?.long(key: String) = this?.get(key)?.jsonPrimitive?.longOrNull
            val mimes = media?.get("supported_mime_types")?.jsonArray?.map { it.jsonPrimitive.content }
            val apiVersion = instance["api_versions"]?.jsonObject?.get("mastodon")?.jsonPrimitive?.intOrNull ?: 0
            return ChannelCapabilities(
                maxCharacters = statuses.int("max_characters") ?: defaults.maxCharacters,
                maxMediaAttachments = statuses.int("max_media_attachments") ?: defaults.maxMediaAttachments,
                imageMimeTypes = mimes?.filter { it.startsWith("image/") }?.takeIf { it.isNotEmpty() } ?: defaults.imageMimeTypes,
                maxImageBytes = media.long("image_size_limit") ?: defaults.maxImageBytes,
                videoAllowed = mimes?.any { it.startsWith("video/") } ?: defaults.videoAllowed,
                maxVideoBytes = media.long("video_size_limit") ?: defaults.maxVideoBytes,
                altTextMaxLength = media.int("description_limit") ?: defaults.altTextMaxLength,
                contentWarning = true,
                textOnly = true,
                pollMaxOptions = polls.int("max_options") ?: defaults.pollMaxOptions,
                pollMaxCharactersPerOption = polls.int("max_characters_per_option") ?: defaults.pollMaxCharactersPerOption,
                pollWithMedia = apiVersion >= 10,
                readAt = now,
            )
        }
    }
}
