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

import app.fediferry.ai.ModelTester
import app.fediferry.data.AiModels
import app.fediferry.data.Settings
import app.fediferry.data.model.AiKind
import app.fediferry.data.model.AiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModelsTest {

    private fun model(id: String, default: Boolean = false, name: String = id) =
        AiModel(id = id, kind = AiKind.ALT_TEXT, name = name, endpoint = "https://x/v1/chat/completions", model = "m-$id", isDefault = default)

    // --- which model is used ------------------------------------------------

    @Test
    fun `the picked model wins, then the default, then the first`() {
        val models = listOf(model("a"), model("b", default = true), model("c"))

        assertEquals("c", AiModel.pick(models, "c")!!.id)
        assertEquals("b", AiModel.pick(models)!!.id)
        assertEquals("b", AiModel.pick(models, "deleted")!!.id)
        assertEquals("a", AiModel.pick(listOf(model("a"), model("c")))!!.id)
        assertNull(AiModel.pick(emptyList()))
    }

    @Test
    fun `a model without a name is still called something`() {
        assertEquals("gemini-3.8-flash", model("a", name = "").copy(model = "gemini-3.8-flash").displayName)
        assertEquals("x", model("a", name = "").copy(model = "").displayName)
    }

    // --- the move from the old single-model settings ------------------------

    @Test
    fun `the old settings become one default model per kind, keys and all`() {
        val old = Settings(
            visionEndpoint = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            visionModel = "gemini-3.8-flash",
            visionApiKey = "secret-1",
            imageEndpoint = "https://api.example.com/v1/images/edits",
            imageModel = "gpt-image-1",
            imageApiKey = "secret-2",
            imageWireFormat = "JSON_BASE64",
            imageMaskPolarity = "WHITE_ON_BLACK",
        )

        val moved = AiModels.legacyModels(old)

        val (alt, altKey) = moved.single { it.first.kind == AiKind.ALT_TEXT }
        assertEquals("gemini-3.8-flash", alt.model)
        assertEquals(old.visionEndpoint, alt.endpoint)
        assertTrue(alt.isDefault)
        assertEquals("secret-1", altKey)
        val (image, imageKey) = moved.single { it.first.kind == AiKind.IMAGE_EDIT }
        assertEquals("JSON_BASE64", image.wireFormat)
        assertEquals("WHITE_ON_BLACK", image.maskPolarity)
        assertEquals("secret-2", imageKey)
    }

    @Test
    fun `nothing set up means nothing to move`() {
        assertTrue(AiModels.legacyModels(Settings()).isEmpty())
    }

    // --- what a test reads from the server ----------------------------------

    @Test
    fun `the model list sits beside the endpoint`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/models",
            ModelTester.listingUrl("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"),
        )
        assertEquals("https://api.example.com/v1/models", ModelTester.listingUrl("https://api.example.com/v1/images/edits/"))
        assertNull(ModelTester.listingUrl("https://sd.local/sdapi/v1/img2img"))
    }

    @Test
    fun `reads model ids from a listing, Google's prefixed ones included`() {
        val body = """{"object":"list","data":[{"id":"models/gemini-3.8-flash"},{"id":"gpt-4o-mini"}]}"""

        assertEquals(listOf("models/gemini-3.8-flash", "gpt-4o-mini"), ModelTester.modelIds(body))
    }

    @Test
    fun `reports the model, the tokens with thinking, and why it stopped`() {
        val body = """{"model":"gemini-3.8-flash","choices":[{"message":{"content":"A red circle on blue."},
            "finish_reason":"stop"}],"usage":{"prompt_tokens":270,"completion_tokens":612,"total_tokens":882,
            "completion_tokens_details":{"reasoning_tokens":604}}}"""

        val details = ModelTester.replyDetails(body).toMap()

        assertEquals("gemini-3.8-flash", details["Model the server ran"])
        assertEquals("270 in · 612 out · 604 of them thinking · 882 in all", details["Tokens"])
        assertEquals("it was done", details["Stopped because"])
    }

    @Test
    fun `reads OpenAI-style rate limit headers`() {
        val headers = mapOf(
            "X-RateLimit-Limit-Requests" to "500",
            "X-RateLimit-Remaining-Requests" to "499",
            "X-RateLimit-Reset-Requests" to "120ms",
            "X-RateLimit-Limit-Tokens" to "30000",
            "X-RateLimit-Remaining-Tokens" to "29118",
            "X-RateLimit-Reset-Tokens" to "1.764s",
        )

        val limits = ModelTester.rateLimits(headers).toMap()

        assertEquals("499 of 500 left, resets in 120ms", limits["Requests"])
        assertEquals("29118 of 30000 left, resets in 1.764s", limits["Tokens"])
    }

    @Test
    fun `reads a refusal's retry-after and the IETF draft headers`() {
        val limits = ModelTester.rateLimits(
            mapOf("retry-after" to "30", "RateLimit-Limit" to "60", "RateLimit-Remaining" to "0", "RateLimit-Reset" to "30"),
        ).toMap()

        assertEquals("30s", limits["Try again after"])
        assertEquals("0 of 60 left, resets in 30s", limits["Rate limit"])
    }

    @Test
    fun `a server that says nothing about limits adds nothing`() {
        assertTrue(ModelTester.rateLimits(mapOf("content-type" to "application/json")).isEmpty())
    }

    @Test
    fun `autocorrect's spaces are taken out of an endpoint`() {
        val typed = "https://generative language.googleapis.com/v1beta/openai/chat/completions "

        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            AiModel.clean(typed),
        )
        assertTrue(!AiModel.isValidEndpoint("https://generative language.googleapis.com/v1"))
        assertTrue(AiModel.isValidEndpoint(AiModel.clean(typed)))
        assertTrue(!AiModel.isValidEndpoint("generativelanguage.googleapis.com"))
    }

    @Test
    fun `network failures are put in words`() {
        val m = model("a").copy(endpoint = "https://generativelanguage.oogleapis.com/v1beta/openai/chat/completions")

        assertEquals(
            "There is no server called generativelanguage.oogleapis.com — check the address.",
            ModelTester.explain(java.net.UnknownHostException("nope"), m),
        )
        assertTrue(ModelTester.explain(java.io.IOException("x", java.net.ConnectException("refused")), m).startsWith("Could not reach"))
        assertEquals("quota exceeded", ModelTester.explain(IllegalStateException("quota exceeded"), m))
    }
}

