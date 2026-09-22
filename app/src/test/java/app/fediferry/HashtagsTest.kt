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

    // --- hashtags in a sent post -------------------------------------------

    @Test
    fun `finds the hashtags a post carries, wherever they are written`() {
        val text = "Monday again #meme\n\n#politics, #Überraschung and (#dark_humor)\nvia https://9gag.com/gag/x"

        assertEquals(listOf("#meme", "#politics", "#Überraschung", "#dark_humor"), Hashtags.inText(text))
    }

    @Test
    fun `a link's fragment, a number and a doubled tag are not hashtags`() {
        val text = "https://example.com/page#section #1 #2024 #meme #Meme ##x mail#me"

        assertEquals(listOf("#meme"), Hashtags.inText(text))
    }

    @Test
    fun `no hashtags in a post means none to remember`() {
        assertEquals(emptyList<String>(), Hashtags.inText("Just a joke, no tags"))
    }

    @Test
    fun `only hashtags the list lacks are new, whatever their case`() {
        assertEquals(
            listOf("#cats"),
            Hashtags.newIn("#Meme #cats #politics", known = listOf("#meme", "#politics")),
        )
    }
}

