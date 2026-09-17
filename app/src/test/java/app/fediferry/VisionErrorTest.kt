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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A bare "returned 404" cannot tell a mistyped URL from a model the service does
 * not have, which are the only two things it is ever likely to be.
 */
class VisionErrorTest {

    @Test
    fun `a Google style error is quoted back`() {
        val body = """
            {"error":{"code":404,"message":"models/gemini-9 is not found for API version v1beta",
             "status":"NOT_FOUND"}}
        """.trimIndent()
        val msg = VisionAltTextProvider.describeFailure(404, body)
        assertTrue(msg, msg.contains("models/gemini-9 is not found"))
        assertTrue(msg, msg.contains("/chat/completions"))
    }

    @Test
    fun `an OpenAI style error is quoted back`() {
        val body = """{"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}"""
        val msg = VisionAltTextProvider.describeFailure(401, body)
        assertTrue(msg, msg.contains("Incorrect API key provided"))
        assertTrue(msg, msg.contains("check the API key"))
    }

    @Test
    fun `a rate limit says what the limit is about`() {
        val msg = VisionAltTextProvider.describeFailure(429, """{"error":{"message":"Quota exceeded"}}""")
        assertTrue(msg, msg.contains("Quota exceeded"))
        assertTrue(msg, msg.contains("daily cap"))
    }

    @Test
    fun `an empty or unparseable body still gives the code and a hint`() {
        for (body in listOf("", "<html>404</html>", "not json")) {
            val msg = VisionAltTextProvider.describeFailure(404, body)
            assertTrue(msg, msg.contains("404"))
            assertTrue(msg, msg.contains("endpoint URL"))
        }
    }

    @Test
    fun `the body itself is never pasted in wholesale`() {
        // A rejected request can echo the image back; only the error message
        // field is ever quoted.
        val huge = "A".repeat(50_000)
        val body = """{"error":{"message":"too big"},"echo":"$huge"}"""
        val msg = VisionAltTextProvider.describeFailure(400, body)
        assertTrue(msg, msg.contains("too big"))
        assertFalse("the echoed payload leaked", msg.contains("AAAAAAAAAA"))
        assertTrue("message should stay short: ${msg.length}", msg.length < 260)
    }

    @Test
    fun `a very long message is truncated`() {
        val body = """{"error":{"message":"${"x".repeat(1000)}"}}"""
        val msg = VisionAltTextProvider.describeFailure(500, body)
        assertTrue("length ${msg.length}", msg.length < 260)
    }
}
