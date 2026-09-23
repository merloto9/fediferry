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
package app.fediferry.ai

import android.content.Context
import app.fediferry.alt.VisionAltTextProvider
import app.fediferry.data.model.AiKind
import app.fediferry.data.model.AiModel
import app.fediferry.di.ServiceLocator
import app.fediferry.media.cleanup.EraseRequest
import app.fediferry.media.cleanup.MaskPolarity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** What a model test found out, ready to show. */
data class ModelTestReport(
    val ok: Boolean,
    /** One line: it works, or why not. */
    val summary: String,
    /** Everything else worth knowing, label to value, in reading order. */
    val details: List<Pair<String, String>>,
)

/**
 * Tries a model once with a tiny real request, through the same code the app
 * uses, and reports what came back: the answer, how long it took, the model
 * the server says it ran, token use, the rate limits the server announces,
 * and whether the model is in the server's own list.
 *
 * Whatever the server does not say is simply left out. The API key is never
 * part of a report.
 */
object ModelTester {

    /** One exchange with the server, as it went over the wire. */
    private data class Exchange(val code: Int, val millis: Long, val headers: Map<String, String>, val body: String)

    suspend fun test(context: Context, model: AiModel): ModelTestReport = withContext(Dispatchers.IO) {
        if (!model.hasValidEndpoint) {
            return@withContext ModelTestReport(
                ok = false,
                summary = "\"${model.endpoint}\" is not a web address. It should look like " +
                    "https://api.example.com/v1/chat/completions, with no spaces.",
                details = emptyList(),
            )
        }
        val exchanges = mutableListOf<Exchange>()
        val client = ServiceLocator.http().newBuilder()
            .callTimeout(90, TimeUnit.SECONDS)
            .addNetworkInterceptor(Interceptor { chain ->
                val started = System.nanoTime()
                val response = chain.proceed(chain.request())
                val body = runCatching { response.peekBody(MAX_CAPTURE).string() }.getOrDefault("")
                synchronized(exchanges) {
                    exchanges += Exchange(
                        code = response.code,
                        millis = (System.nanoTime() - started) / 1_000_000,
                        headers = response.headers.toMultimap().mapValues { it.value.joinToString(", ") },
                        body = body,
                    )
                }
                response
            })
            .build()

        val apiKey = ServiceLocator.aiModels(context).apiKey(model)
        val details = mutableListOf<Pair<String, String>>()
        val outcome: Result<String> = when (model.kind) {
            AiKind.ALT_TEXT -> VisionAltTextProvider(client, model.endpoint, model.model, apiKey, TEST_PROMPT)
                .describe(ModelTestImages.picture, "image/png")
                .map { answer -> details += "Answer" to answer; "It works." }

            AiKind.IMAGE_EDIT -> {
                val mask = if (ServiceLocator.maskPolarityOf(model) == MaskPolarity.WHITE_ON_BLACK) {
                    ModelTestImages.maskWhite
                } else {
                    ModelTestImages.maskTransparent
                }
                ServiceLocator.imageEditProvider(context, model, client)
                    .erase(EraseRequest(ModelTestImages.picture, "image/png", mask, TEST_INSTRUCTION))
                    .map { bytes -> details += "Answer" to "an edited picture, ${bytes.size / 1024} KB"; "It works." }
            }
        }

        val last = synchronized(exchanges) { exchanges.lastOrNull() }
        if (last != null) {
            details += "Response time" to "${last.millis} ms" + if (exchanges.size > 1) " (asked ${exchanges.size} times)" else ""
            details += replyDetails(last.body)
            details += rateLimits(last.headers)
        }
        details += modelListing(client, model, apiKey)

        ModelTestReport(
            ok = outcome.isSuccess,
            summary = outcome.getOrElse { explain(it, model) },
            details = details,
        )
    }

    /** Checks the server's model list, where it has one, for the model's name. */
    private fun modelListing(client: okhttp3.OkHttpClient, model: AiModel, apiKey: String): List<Pair<String, String>> {
        val url = listingUrl(model.endpoint) ?: return emptyList()
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val ids = modelIds(response.body.string())
                if (ids.isEmpty()) return emptyList()
                val listed = ids.any { it == model.model || it.substringAfterLast('/') == model.model }
                listOf(
                    "In the server's model list" to if (listed) {
                        "yes"
                    } else {
                        "no — ${ids.size} models are offered; check the spelling of \"${model.model}\""
                    },
                )
            }
        }.getOrDefault(emptyList())
    }

    /** A failure in words, for the ones the network library words for itself. */
    fun explain(error: Throwable, model: AiModel): String {
        val host = runCatching { java.net.URI(model.endpoint).host }.getOrNull() ?: model.endpoint
        val root = generateSequence(error) { it.cause }.last()
        return when (root) {
            is java.net.UnknownHostException -> "There is no server called $host — check the address."
            is java.net.ConnectException, is java.net.NoRouteToHostException ->
                "Could not reach $host — check the address and the phone's connection."
            is java.net.SocketTimeoutException, is java.io.InterruptedIOException ->
                "$host did not answer in time."
            is javax.net.ssl.SSLException -> "The secure connection to $host failed: ${root.message}"
            else -> error.message ?: error.javaClass.simpleName
        }
    }

    // --- pure, and tested ---------------------------------------------------

    /** `…/v1/chat/completions` or `…/v1/images/edits` → `…/v1/models`. */
    fun listingUrl(endpoint: String): String? {
        val trimmed = endpoint.trim().trimEnd('/')
        val base = listOf("/chat/completions", "/images/edits", "/images/generations")
            .firstOrNull { trimmed.endsWith(it) }
            ?.let { trimmed.removeSuffix(it) }
            ?: return null
        return "$base/models"
    }

    fun modelIds(body: String): List<String> = runCatching {
        json.parseToJsonElement(body).jsonObject["data"]!!.jsonArray
            .mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }
    }.getOrDefault(emptyList())

    /** The model the server says it ran, token use and why it stopped, where the reply says. */
    fun replyDetails(body: String): List<Pair<String, String>> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        root["model"]?.jsonPrimitive?.contentOrNull?.let { out += "Model the server ran" to it }
        (root["usage"] as? JsonObject)?.let { usage ->
            fun n(key: String) = usage[key]?.jsonPrimitive?.contentOrNull
            val thinking = (usage["completion_tokens_details"] as? JsonObject)
                ?.get("reasoning_tokens")?.jsonPrimitive?.contentOrNull
            val parts = listOfNotNull(
                n("prompt_tokens")?.let { "$it in" },
                n("completion_tokens")?.let { "$it out" },
                thinking?.takeIf { it != "0" }?.let { "$it of them thinking" },
                n("total_tokens")?.let { "$it in all" },
            )
            if (parts.isNotEmpty()) out += "Tokens" to parts.joinToString(" · ")
        }
        runCatching { root["choices"]!!.jsonArray.first().jsonObject["finish_reason"]!!.jsonPrimitive.contentOrNull }
            .getOrNull()
            ?.let { out += "Stopped because" to if (it == "stop") "it was done" else it }
        return out
    }

    /**
     * The rate limits a server announces in its headers, named for people.
     * OpenAI and most compatible servers use `x-ratelimit-*`; the IETF draft
     * uses `ratelimit-*`; a refusal may carry `retry-after`.
     */
    fun rateLimits(headers: Map<String, String>): List<Pair<String, String>> {
        val h = headers.mapKeys { it.key.lowercase() }
        val out = mutableListOf<Pair<String, String>>()
        fun pair(label: String, remaining: String?, limit: String?, reset: String?) {
            if (remaining == null && limit == null) return
            val value = buildString {
                append(remaining ?: "?")
                if (limit != null) append(" of $limit")
                append(" left")
                if (reset != null) append(", resets in $reset")
            }
            out += label to value
        }
        pair("Requests", h["x-ratelimit-remaining-requests"], h["x-ratelimit-limit-requests"], h["x-ratelimit-reset-requests"])
        pair("Tokens", h["x-ratelimit-remaining-tokens"], h["x-ratelimit-limit-tokens"], h["x-ratelimit-reset-tokens"])
        pair("Rate limit", h["ratelimit-remaining"], h["ratelimit-limit"], h["ratelimit-reset"]?.let { "${it}s" })
        h["retry-after"]?.let { out += "Try again after" to "${it}s" }
        // Anything else rate-limit shaped, shown as the server named it.
        val shown = setOf(
            "x-ratelimit-remaining-requests", "x-ratelimit-limit-requests", "x-ratelimit-reset-requests",
            "x-ratelimit-remaining-tokens", "x-ratelimit-limit-tokens", "x-ratelimit-reset-tokens",
            "ratelimit-remaining", "ratelimit-limit", "ratelimit-reset", "retry-after",
        )
        h.filterKeys { (it.contains("ratelimit") || it.contains("quota")) && it !in shown }
            .toSortedMap()
            .forEach { (k, v) -> out += k to v }
        return out
    }

    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_CAPTURE = 256L * 1024
    private const val TEST_PROMPT = "Describe this picture in at most eight words."
    private const val TEST_INSTRUCTION = "Remove the white label and fill in the background."
}
