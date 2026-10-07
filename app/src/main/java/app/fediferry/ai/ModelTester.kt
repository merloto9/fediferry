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
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import app.fediferry.R
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
 * Words for a report that still need the user's language: a resource with its
 * arguments, or text that stays as the server sent it. Keeps the parsing below
 * pure, so it runs on the JVM without Android resources.
 */
sealed interface ReportText {
    data class Raw(val text: String) : ReportText
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : ReportText
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = emptyList()) : ReportText
    data class Joined(val parts: List<ReportText>, val separator: String = " · ") : ReportText

    fun resolve(context: Context): String = when (this) {
        is Raw -> text
        is Res -> context.getString(id, *args.map { it.resolved(context) }.toTypedArray())
        is Plural -> context.resources.getQuantityString(id, count, *args.map { it.resolved(context) }.toTypedArray())
        is Joined -> parts.joinToString(separator) { it.resolve(context) }
    }

    private fun Any.resolved(context: Context): Any = if (this is ReportText) resolve(context) else this
}

/** One line of a report, label to value, before it is put in words. */
data class ReportDetail(val label: ReportText, val value: ReportText) {
    constructor(@StringRes label: Int, value: ReportText) : this(ReportText.Res(label), value)
    constructor(@StringRes label: Int, value: String) : this(ReportText.Res(label), ReportText.Raw(value))
}

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
                summary = context.getString(R.string.extra_test_bad_endpoint, model.endpoint),
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
        val details = mutableListOf<ReportDetail>()
        val works = ReportText.Res(R.string.extra_test_works)
        val outcome: Result<ReportText> = when (model.kind) {
            AiKind.ALT_TEXT -> VisionAltTextProvider(client, model.endpoint, model.model, apiKey, TEST_PROMPT)
                .describe(ModelTestImages.picture, "image/png")
                .map { answer -> details += ReportDetail(R.string.extra_test_answer, answer); works }

            AiKind.IMAGE_EDIT -> {
                val mask = if (ServiceLocator.maskPolarityOf(model) == MaskPolarity.WHITE_ON_BLACK) {
                    ModelTestImages.maskWhite
                } else {
                    ModelTestImages.maskTransparent
                }
                ServiceLocator.imageEditProvider(context, model, client)
                    .erase(EraseRequest(ModelTestImages.picture, "image/png", mask, TEST_INSTRUCTION))
                    .map { bytes ->
                        details += ReportDetail(R.string.extra_test_answer, ReportText.Res(R.string.extra_test_edited_picture, listOf(bytes.size / 1024)))
                        works
                    }
            }
        }

        val last = synchronized(exchanges) { exchanges.lastOrNull() }
        if (last != null) {
            details += ReportDetail(
                R.string.extra_test_response_time,
                if (exchanges.size > 1) {
                    ReportText.Plural(R.plurals.extra_test_millis_asked, exchanges.size, listOf(last.millis.toInt(), exchanges.size))
                } else {
                    ReportText.Res(R.string.extra_test_millis, listOf(last.millis.toInt()))
                },
            )
            details += replyDetails(last.body)
            details += rateLimits(last.headers)
        }
        details += modelListing(client, model, apiKey)

        ModelTestReport(
            ok = outcome.isSuccess,
            summary = outcome.getOrElse { explain(it, model) }.resolve(context),
            details = details.map { it.label.resolve(context) to it.value.resolve(context) },
        )
    }

    /** Checks the server's model list, where it has one, for the model's name. */
    private fun modelListing(client: okhttp3.OkHttpClient, model: AiModel, apiKey: String): List<ReportDetail> {
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
                    ReportDetail(
                        R.string.extra_test_in_list,
                        if (listed) {
                            ReportText.Res(R.string.extra_test_listed_yes)
                        } else {
                            ReportText.Plural(R.plurals.extra_test_listed_no, ids.size, listOf(ids.size, model.model))
                        },
                    ),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** A failure in words, for the ones the network library words for itself. */
    fun explain(error: Throwable, model: AiModel): ReportText {
        val host = runCatching { java.net.URI(model.endpoint).host }.getOrNull() ?: model.endpoint
        val root = generateSequence(error) { it.cause }.last()
        return when (root) {
            is java.net.UnknownHostException -> ReportText.Res(R.string.extra_test_no_server, listOf(host))
            is java.net.ConnectException, is java.net.NoRouteToHostException ->
                ReportText.Res(R.string.extra_test_unreachable, listOf(host))
            is java.net.SocketTimeoutException, is java.io.InterruptedIOException ->
                ReportText.Res(R.string.extra_test_timeout, listOf(host))
            is javax.net.ssl.SSLException -> ReportText.Res(R.string.extra_test_ssl, listOf(host, root.message.orEmpty()))
            else -> ReportText.Raw(error.message ?: error.javaClass.simpleName)
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
    fun replyDetails(body: String): List<ReportDetail> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val out = mutableListOf<ReportDetail>()
        root["model"]?.jsonPrimitive?.contentOrNull?.let { out += ReportDetail(R.string.extra_test_model_ran, it) }
        (root["usage"] as? JsonObject)?.let { usage ->
            fun n(key: String) = usage[key]?.jsonPrimitive?.contentOrNull
            val thinking = (usage["completion_tokens_details"] as? JsonObject)
                ?.get("reasoning_tokens")?.jsonPrimitive?.contentOrNull
            val parts = listOfNotNull(
                n("prompt_tokens")?.let { ReportText.Res(R.string.extra_test_tokens_in, listOf(it)) },
                n("completion_tokens")?.let { ReportText.Res(R.string.extra_test_tokens_out, listOf(it)) },
                thinking?.takeIf { it != "0" }?.let { ReportText.Res(R.string.extra_test_tokens_thinking, listOf(it)) },
                n("total_tokens")?.let { ReportText.Res(R.string.extra_test_tokens_total, listOf(it)) },
            )
            if (parts.isNotEmpty()) out += ReportDetail(R.string.extra_test_tokens, ReportText.Joined(parts))
        }
        runCatching { root["choices"]!!.jsonArray.first().jsonObject["finish_reason"]!!.jsonPrimitive.contentOrNull }
            .getOrNull()
            ?.let {
                out += ReportDetail(
                    R.string.extra_test_stopped,
                    if (it == "stop") ReportText.Res(R.string.extra_test_stopped_done) else ReportText.Raw(it),
                )
            }
        return out
    }

    /**
     * The rate limits a server announces in its headers, named for people.
     * OpenAI and most compatible servers use `x-ratelimit-*`; the IETF draft
     * uses `ratelimit-*`; a refusal may carry `retry-after`.
     */
    fun rateLimits(headers: Map<String, String>): List<ReportDetail> {
        val h = headers.mapKeys { it.key.lowercase() }
        val out = mutableListOf<ReportDetail>()
        fun pair(@StringRes label: Int, remaining: String?, limit: String?, reset: String?) {
            if (remaining == null && limit == null) return
            val left = remaining ?: "?"
            val value = when {
                limit != null && reset != null -> ReportText.Res(R.string.extra_test_left_of_reset, listOf(left, limit, reset))
                limit != null -> ReportText.Res(R.string.extra_test_left_of, listOf(left, limit))
                reset != null -> ReportText.Res(R.string.extra_test_left_reset, listOf(left, reset))
                else -> ReportText.Res(R.string.extra_test_left, listOf(left))
            }
            out += ReportDetail(label, value)
        }
        pair(R.string.extra_test_requests, h["x-ratelimit-remaining-requests"], h["x-ratelimit-limit-requests"], h["x-ratelimit-reset-requests"])
        pair(R.string.extra_test_tokens, h["x-ratelimit-remaining-tokens"], h["x-ratelimit-limit-tokens"], h["x-ratelimit-reset-tokens"])
        pair(R.string.extra_test_rate_limit, h["ratelimit-remaining"], h["ratelimit-limit"], h["ratelimit-reset"]?.let { "${it}s" })
        h["retry-after"]?.let { out += ReportDetail(R.string.extra_test_retry_after, "${it}s") }
        // Anything else rate-limit shaped, shown as the server named it.
        val shown = setOf(
            "x-ratelimit-remaining-requests", "x-ratelimit-limit-requests", "x-ratelimit-reset-requests",
            "x-ratelimit-remaining-tokens", "x-ratelimit-limit-tokens", "x-ratelimit-reset-tokens",
            "ratelimit-remaining", "ratelimit-limit", "ratelimit-reset", "retry-after",
        )
        h.filterKeys { (it.contains("ratelimit") || it.contains("quota")) && it !in shown }
            .toSortedMap()
            .forEach { (k, v) -> out += ReportDetail(ReportText.Raw(k), ReportText.Raw(v)) }
        return out
    }

    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_CAPTURE = 256L * 1024
    private const val TEST_PROMPT = "Describe this picture in at most eight words."
    private const val TEST_INSTRUCTION = "Remove the white label and fill in the background."
}
