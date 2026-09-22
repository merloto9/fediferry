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

import app.fediferry.data.model.Hashtags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HashtagsTest {

    @Test
    fun `however a tag is typed, it is stored one way`() {
        assertEquals("#meme", Hashtags.normalize("meme"))
        assertEquals("#meme", Hashtags.normalize("#meme"))
        assertEquals("#meme", Hashtags.normalize("  ##meme! "))
        assertEquals("#Überraschung", Hashtags.normalize("Überraschung"))
        assertEquals("#dark_humor", Hashtags.normalize("#dark_humor"))
    }

    @Test
    fun `nothing usable is no tag`() {
        assertNull(Hashtags.normalize(""))
        assertNull(Hashtags.normalize("#"))
        assertNull(Hashtags.normalize("!!"))
    }

    @Test
    fun `parses a list once each, ignoring case, in order`() {
        assertEquals(listOf("#meme", "#cats", "#dogs"), Hashtags.parse("#meme, cats #Meme  #dogs"))
        assertEquals(emptyList<String>(), Hashtags.parse(null))
    }

    @Test
    fun `a union keeps the first list's order and spelling`() {
        assertEquals(listOf("#a", "#B", "#c"), Hashtags.union(listOf("#a", "#B"), listOf("#b", "#c")))
    }
}
