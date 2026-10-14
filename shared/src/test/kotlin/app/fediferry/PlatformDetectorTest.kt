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

import app.fediferry.api.Platform
import app.fediferry.platform.PlatformDetector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlatformDetectorTest {

    private fun obj(text: String) = Json.decodeFromString<JsonObject>(text)

    @Test
    fun `the newest nodeinfo document is the one read`() = assertEquals(
        "https://mastodon.social/nodeinfo/2.1",
        PlatformDetector.documentUrl(
            obj(
                """{"links":[{"rel":"http://nodeinfo.diaspora.software/ns/schema/2.0","href":"https://mastodon.social/nodeinfo/2.0"},
                   {"rel":"http://nodeinfo.diaspora.software/ns/schema/2.1","href":"https://mastodon.social/nodeinfo/2.1"}]}""",
            ),
        ),
    )

    @Test
    fun `the software's own name says the platform`() {
        val name = PlatformDetector.softwareName(obj("""{"version":"2.0","software":{"name":"pixelfed","version":"0.12"}}"""))
        assertEquals("pixelfed", name)
        assertEquals(Platform.PIXELFED, Platform.ofSoftware(name!!))
        assertEquals(Platform.MASTODON, Platform.ofSoftware("Mastodon"))
        assertNull(Platform.ofSoftware("misskey"))
    }

    @Test
    fun `an index without nodeinfo says nothing`() = assertNull(PlatformDetector.documentUrl(obj("""{"links":[]}""")))

    @Test
    fun `only Mastodon can be connected so far`() =
        assertEquals(listOf(Platform.MASTODON), Platform.entries.filter { it.supported })
}
