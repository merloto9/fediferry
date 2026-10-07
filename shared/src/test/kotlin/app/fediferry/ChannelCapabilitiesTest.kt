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

import app.fediferry.mastodon.MastodonAccounts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelCapabilitiesTest {

    @Test
    fun `reads what a Mastodon 4_6 instance says it takes`() {
        // Trimmed from a real /api/v2/instance reply.
        val reply = Json.decodeFromString<JsonObject>(
            """
            {"domain":"mastodon.social","api_versions":{"mastodon":10},
             "configuration":{
               "statuses":{"max_characters":500,"max_media_attachments":4},
               "media_attachments":{"supported_mime_types":["image/jpeg","image/png","image/heic","video/mp4"],
                                    "image_size_limit":16777216,"video_size_limit":103809024,"description_limit":10000},
               "polls":{"max_options":4,"max_characters_per_option":50}}}
            """.trimIndent(),
        )
        val caps = MastodonAccounts.parseCapabilities(reply, now = 42)
        assertEquals(500, caps.maxCharacters)
        assertEquals(10000, caps.altTextMaxLength)
        assertEquals(listOf("image/jpeg", "image/png", "image/heic"), caps.imageMimeTypes)
        assertTrue(caps.videoAllowed)
        assertTrue(caps.pollWithMedia)
        assertEquals(42L, caps.readAt)
    }

    @Test
    fun `an instance that says little keeps Mastodon's defaults`() {
        val caps = MastodonAccounts.parseCapabilities(Json.decodeFromString("""{"configuration":{"statuses":{"max_characters":5000}}}"""), 0)
        assertEquals(5000, caps.maxCharacters)
        assertEquals(4, caps.maxMediaAttachments)
        assertEquals(1500, caps.altTextMaxLength)
        assertFalse(caps.pollWithMedia)
    }

    @Test
    fun `an instance is named the same however it is written`() {
        listOf("mastodon.social", "https://Mastodon.social/", "@me@mastodon.social", "mastodon.social/@me")
            .forEach { assertEquals(it, "mastodon.social", MastodonAccounts.normaliseInstance(it)) }
    }
}
