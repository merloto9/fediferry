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
package app.fediferry.alt

import app.fediferry.net.ApiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/**
 * Calls a user-configured, OpenAI-compatible chat-completions endpoint.
 *
 * The vendor is a setting, not a compile-time choice — any server speaking that
 * shape (a hosted API, a local Ollama, an LLM gateway) works. The posting path
 * only ever sees [AltTextProvider].
 *
 * The token budget is generous on purpose. Reasoning models — Gemini 2.5 and
 * 3, OpenAI's o-series — spend part of `max_tokens` thinking before they
 * write, and Gemini 3 cannot be told not to. A budget sized for the answer
 * alone left them room for half a sentence, which then went out as the
 * description. A reply the model had to stop is therefore never used: it is
 * asked again with more room, and failing that, reported as cut off.
 */
class VisionAltTextProvider(
    private val client: OkHttpClient,
    private val endpoint: String,
    private val model: String,
    private val apiKey: String,
    private val prompt: String,
) : AltTextProvider {

    override suspend fun describe(image: ByteArray, mimeType: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(endpoint.isNotBlank()) { "no vision endpoint configured" }

                val dataUri = "data:${mimeType.ifBlank { "image/jpeg" }};base64," +
                    Base64.getEncoder().encodeToString(image)

                var reply = ask(dataUri, FIRST_BUDGET)
                if (reply.cutOff) reply = ask(dataUri, RETRY_BUDGET)
                check(!reply.cutOff) {
                    "the model ran out of room and its description was cut off — " +
                        "try again, or a model that thinks less"
                }
                fitForMastodon(reply.text)
            }
        }

    private fun ask(dataUri: String, maxTokens: Int): Reply {
        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", prompt)
                        })
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", dataUri) })
                        })
                    })
                })
            })
        }

        val request = Request.Builder()
            .url(endpoint)
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .post(payload.toString().toRequestBody(JSON))
            .build()

        return client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) error(describeFailure(response.code, body))
            parse(body)
        }
    }

    /** What the model said, and whether it had to stop before it was done. */
    data class Reply(val text: String, val cutOff: Boolean)

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        /** Room for a model's thinking as well as the sentence or two it writes. */
        const val FIRST_BUDGET = 2048

        /** The one second chance, for a model that thought for longer. */
        const val RETRY_BUDGET = 8192

        /** Mastodon refuses a media description longer than this, failing the upload. */
        const val MASTODON_DESCRIPTION_LIMIT = 1500

        /**
         * Reads a chat-completions reply. `content` is usually one string, but
         * some compatible servers send a list of parts; every text part counts.
         * A `finish_reason` of `length` means the model hit the token limit.
         */
        fun parse(body: String): Reply {
            val choice = json.parseToJsonElement(body)
                .jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: error("vision endpoint returned no answer")
            val text = when (val content = choice["message"]?.jsonObject?.get("content")) {
                is JsonPrimitive -> content.contentOrNull
                is JsonArray -> content.mapNotNull { part ->
                    (part as? JsonObject)
                        ?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull in setOf(null, "text", "output_text") }
                        ?.get("text")?.jsonPrimitive?.contentOrNull
                }.joinToString("")
                else -> null
            }.orEmpty().trim()
            val finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
            // A model cut off before it wrote anything is cut off too, not "empty".
            val cutOff = finish == "length" || finish == "max_tokens"
            if (!cutOff && text.isBlank()) error("vision endpoint returned an empty description")
            return Reply(text, cutOff)
        }

        /**
         * Keeps a description within what Mastodon accepts, ending at the last
         * whole sentence that fits rather than mid-word.
         */
        fun fitForMastodon(text: String, limit: Int = MASTODON_DESCRIPTION_LIMIT): String {
            if (text.length <= limit) return text
            val head = text.take(limit)
            val sentenceEnd = Regex("""[.!?…](?=\s|$)""").findAll(head).lastOrNull()?.range?.last
            if (sentenceEnd != null && sentenceEnd > limit / 2) return head.take(sentenceEnd + 1).trim()
            val wordEnd = head.lastIndexOf(' ').takeIf { it > limit / 2 } ?: (limit - 1)
            return head.take(wordEnd).trimEnd() + "…"
        }

        /**
         * Adds the one hint that is specific to this endpoint: the path has to
         * end in /chat/completions, which is the mistake a 404 usually is.
         */
        fun describeFailure(code: Int, body: String): String {
            val base = ApiError.describe("vision endpoint", code, body)
            return if (code == 404) "$base (it must end in /chat/completions)" else base
        }
    }
}
