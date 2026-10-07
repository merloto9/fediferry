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
package app.fediferry

import app.fediferry.alt.VisionAltTextProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The replies below are shaped like Gemini's OpenAI-compatible endpoint. A
 * reasoning model spends part of max_tokens thinking, and a budget sized for
 * the answer alone came back as half a sentence with finish_reason "length" —
 * which used to be saved as the description.
 */
class VisionReplyTest {

    private fun reply(content: String, finish: String) =
        """{"choices":[{"index":0,"message":{"role":"assistant","content":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(content))}},"finish_reason":"$finish"}]}"""

    // --- reading a reply ---------------------------------------------------

    @Test
    fun `a finished reply is the description`() {
        val r = VisionAltTextProvider.parse(reply("A cat in a box. The caption reads: \"If I fits\".", "stop"))

        assertEquals("A cat in a box. The caption reads: \"If I fits\".", r.text)
        assertTrue(!r.cutOff)
    }

    @Test
    fun `a reply the model had to stop is known to be cut off`() {
        val r = VisionAltTextProvider.parse(reply("A cat sitting in a", "length"))

        assertTrue(r.cutOff)
    }

    @Test
    fun `a model that thought until the limit and wrote nothing is cut off, not an error`() {
        assertTrue(VisionAltTextProvider.parse(reply("", "length")).cutOff)
    }

    @Test
    fun `content sent as a list of parts is read whole`() {
        val body = """{"choices":[{"message":{"content":[
            {"type":"text","text":"A cat in a box. "},
            {"type":"text","text":"The caption reads: \"If I fits\"."}]},"finish_reason":"stop"}]}"""

        assertEquals("A cat in a box. The caption reads: \"If I fits\".", VisionAltTextProvider.parse(body).text)
    }

    @Test
    fun `an empty finished reply is an error`() {
        assertTrue(runCatching { VisionAltTextProvider.parse(reply("  ", "stop")) }.isFailure)
    }

    // --- the whole request, against a fake endpoint ------------------------

    @Test
    fun `a cut-off answer is asked again with more room, and the full one is used`() = runTest {
        val budgets = mutableListOf<Int>()
        val http = fake(budgets) { attempt ->
            if (attempt == 0) reply("A cat sitting", "length") else reply("A cat sitting in a cardboard box.", "stop")
        }

        val text = provider(http).describe(ByteArray(8), "image/png").getOrThrow()

        assertEquals("A cat sitting in a cardboard box.", text)
        assertEquals(listOf(VisionAltTextProvider.FIRST_BUDGET, VisionAltTextProvider.RETRY_BUDGET), budgets)
    }

    @Test
    fun `an answer cut off even with more room fails instead of saving a fragment`() = runTest {
        val http = fake { reply("A cat sitting", "length") }

        val result = provider(http).describe(ByteArray(8), "image/png")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!, "cut off" in result.exceptionOrNull()!!.message!!)
    }

    @Test
    fun `a finished answer is asked for once, with room to think`() = runTest {
        val budgets = mutableListOf<Int>()
        val http = fake(budgets) { reply("A cat.", "stop") }

        provider(http).describe(ByteArray(8), "image/png").getOrThrow()

        assertEquals(listOf(VisionAltTextProvider.FIRST_BUDGET), budgets)
        assertTrue("a thinking model needs more than the old 300", VisionAltTextProvider.FIRST_BUDGET >= 2048)
    }

    // --- Mastodon's limit ----------------------------------------------------

    @Test
    fun `a description that fits is left alone`() {
        assertEquals("A cat.", VisionAltTextProvider.fitForMastodon("A cat."))
    }

    @Test
    fun `a description too long for Mastodon ends at a whole sentence`() {
        val text = "A cat sits in a box. ".repeat(100).trim()

        val fitted = VisionAltTextProvider.fitForMastodon(text)

        assertTrue(fitted.length <= VisionAltTextProvider.MASTODON_DESCRIPTION_LIMIT)
        assertTrue(fitted, fitted.endsWith("box."))
    }

    // --- fake endpoint -------------------------------------------------------

    private fun provider(http: OkHttpClient) = VisionAltTextProvider(
        client = http,
        endpoint = "https://llm.example/v1/chat/completions",
        model = "gemini-3.8-flash",
        apiKey = "k",
        prompt = "Describe this image.",
    )

    private fun fake(budgets: MutableList<Int> = mutableListOf(), answer: (attempt: Int) -> String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val sent = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                budgets += Json.parseToJsonElement(sent).jsonObject["max_tokens"]!!.jsonPrimitive.int
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(answer(budgets.size - 1).toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
}
