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

import app.fediferry.data.db.Converters
import app.fediferry.data.model.ContentSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ConvertersTest {

    private val c = Converters()

    @Test
    fun `source data survives the round trip, quotes and newlines included`() {
        val fields = mapOf("title" to "A \"joke\"\nwith two lines", "hashtags" to "#meme #funny")

        assertEquals(fields, c.stringToStringMap(c.stringMapToString(fields)))
    }

    @Test
    fun `unreadable source data degrades to none`() {
        assertEquals(emptyMap<String, String>(), c.stringToStringMap("not json"))
        assertEquals(emptyMap<String, String>(), c.stringToStringMap("{}"))
    }

    @Test
    fun `excluded sources survive the round trip`() {
        val sources = setOf(ContentSource.PINTEREST, ContentSource.NINEGAG)

        assertEquals("NINEGAG,PINTEREST", c.sourcesToString(sources))
        assertEquals(sources, c.stringToSources(c.sourcesToString(sources)))
    }

    @Test
    fun `a source that no longer exists is dropped, not fatal`() {
        assertEquals(setOf(ContentSource.REDDIT), c.stringToSources("REDDIT,MYSPACE"))
        assertEquals(emptySet<ContentSource>(), c.stringToSources(""))
    }
}
