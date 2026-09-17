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
package app.fediferry.media.cleanup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/**
 * Talks to a user-configured image-editing endpoint.
 *
 * java.util.Base64 rather than the Android one, so [decode] is exercised on
 * the host instead of only against a paid endpoint.
 *
 * Unlike chat completions, inpainting has no de facto standard shape, so the
 * wire format is a setting rather than a guess: [EditWireFormat.MULTIPART]
 * covers the OpenAI images/edits family, [EditWireFormat.JSON_BASE64] covers
 * the Stable-Diffusion-derived servers. Either way the vendor is configuration,
 * never a compile-time choice, and nothing downstream of this class knows which
 * one is in use.
 */
class HttpImageEditProvider(
    private val client: OkHttpClient,
    private val endpoint: String,
    private val model: String,
    private val apiKey: String,
    private val wireFormat: EditWireFormat,
) : ImageEditProvider {

    override suspend fun erase(request: EraseRequest): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(endpoint.isNotBlank()) { "no image endpoint configured" }

                val body = when (wireFormat) {
                    EditWireFormat.MULTIPART -> multipartBody(request)
                    EditWireFormat.JSON_BASE64 -> jsonBody(request)
                }

                val http = Request.Builder()
                    .url(endpoint)
                    .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                    .post(body)
                    .build()

                client.newCall(http).execute().use { response ->
                    check(response.isSuccessful) { "image endpoint returned ${response.code}" }
                    val contentType = response.body.contentType()?.toString().orEmpty()
                    val bytes = response.body.bytes()
                    decode(bytes, contentType).getOrThrow()
                }
            }
        }

    private fun multipartBody(request: EraseRequest) = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart(
            "image",
            "image.png",
            request.image.toRequestBody(request.mimeType.toMediaTypeOrPng()),
        )
        .addFormDataPart("mask", "mask.png", request.mask.toRequestBody(PNG))
        .addFormDataPart("prompt", request.instruction)
        .apply { if (model.isNotBlank()) addFormDataPart("model", model) }
        .build()

    private fun jsonBody(request: EraseRequest) = buildJsonObject {
        if (model.isNotBlank()) put("model", model)
        put("prompt", request.instruction)
        put("image", Base64.getEncoder().encodeToString(request.image))
        put("mask", Base64.getEncoder().encodeToString(request.mask))
    }.toString().toRequestBody(JSON_MEDIA)

    private fun String.toMediaTypeOrPng() =
        runCatching { toMediaType() }.getOrElse { PNG }

    companion object {
        private val PNG = "image/png".toMediaType()
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Pulls image bytes out of whatever came back.
         *
         * Services answer in at least three ways: the raw image, JSON holding a
         * base64 string, or JSON holding a list of them. Pure so all three are
         * covered by tests instead of by a paid call.
         */
        fun decode(body: ByteArray, contentType: String): Result<ByteArray> = runCatching {
            check(body.isNotEmpty()) { "image endpoint returned nothing" }

            if (contentType.startsWith("image/")) return@runCatching body

            val text = body.decodeToString()
            val root = runCatching { json.parseToJsonElement(text) }.getOrNull()
                ?: error("image endpoint returned neither an image nor JSON")

            val encoded = firstBase64(root)
                ?: error("image endpoint returned JSON with no image in it")

            // Services sometimes answer with a whole data: URI rather than
            // bare base64, and sometimes wrap lines.
            val cleaned = encoded.substringAfterLast(',').filterNot { it.isWhitespace() }
            Base64.getMimeDecoder().decode(cleaned)
                .also { check(it.isNotEmpty()) { "image endpoint returned an empty image" } }
        }

        /**
         * Finds the base64 payload.
         *
         * A value reached through one of the [KEYS] is taken at its word,
         * however short — a small image really is a short string. Only the
         * last-resort sweep over unrecognised keys needs a length heuristic, to
         * avoid mistaking a model name or a request id for an image.
         */
        private fun firstBase64(element: JsonElement, lenient: Boolean = true): String? =
            when (element) {
                is JsonPrimitive -> element.contentOrNull()
                    ?.takeIf { looksLikeBase64(it, lenient) }

                is JsonArray -> element.firstNotNullOfOrNull { firstBase64(it, lenient) }

                is JsonObject ->
                    KEYS.firstNotNullOfOrNull { key -> element[key]?.let { firstBase64(it, true) } }
                        ?: element.values.firstNotNullOfOrNull { firstBase64(it, false) }
            }

        private const val MIN_KNOWN_KEY_LENGTH = 8
        private const val MIN_SWEPT_LENGTH = 64

        private fun looksLikeBase64(value: String, lenient: Boolean): Boolean {
            val payload = value.substringAfterLast(',').filterNot { it.isWhitespace() }
            val minimum = if (lenient) MIN_KNOWN_KEY_LENGTH else MIN_SWEPT_LENGTH
            if (payload.length < minimum) return false
            return payload.all { it.isLetterOrDigit() || it in "+/=-_" }
        }

        private val KEYS = listOf("b64_json", "image", "images", "data", "output", "result")

        private fun JsonPrimitive.contentOrNull(): String? =
            if (isString) content else null
    }
}
