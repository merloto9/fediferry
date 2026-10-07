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
import app.fediferry.template.TemplateEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/** Drafts from before 0.12 had their hashtags typed into the text. */
class AdoptHashtagsTest {

    private fun postedBefore(body: String) = body
    private fun postedAfter(body: String): String {
        val (text, tags) = Hashtags.adoptFromText(body)
        return TemplateEngine.finish(text, tags)
    }

    @Test
    fun aHashtagLineBecomesTheListAndTagsTakesItsPlace() {
        val body = "#meme #funny\n\nvia https://9gag.com/gag/x"
        val (text, tags) = Hashtags.adoptFromText(body)
        assertEquals("{tags}\n\nvia https://9gag.com/gag/x", text)
        assertEquals(listOf("#meme", "#funny"), tags)
        assertEquals(postedBefore(body), postedAfter(body))
    }

    @Test
    fun hashtagsInASentenceStayInTheSentence() {
        val body = "Look at this #cat\n#meme"
        val (text, tags) = Hashtags.adoptFromText(body)
        assertEquals("Look at this #cat\n{tags}", text)
        assertEquals(listOf("#meme"), tags)
    }

    @Test
    fun severalHashtagLinesBecomeOne() {
        val (text, tags) = Hashtags.adoptFromText("#a\nhello\n#b #A")
        assertEquals("{tags}\nhello", text)
        assertEquals(listOf("#a", "#b"), tags)
    }

    @Test
    fun withoutHashtagsTagsGoesAtTheEndAndPostsNothing() {
        val body = "Just words\n\nvia https://x.y/z"
        val (text, tags) = Hashtags.adoptFromText(body)
        assertEquals("Just words\n\nvia https://x.y/z\n\n{tags}", text)
        assertEquals(emptyList<String>(), tags)
        assertEquals(postedBefore(body), postedAfter(body))
        assertEquals("{tags}", Hashtags.adoptFromText("").first)
    }

    @Test
    fun aLinkWithAnAnchorIsNotAHashtagLine() {
        val body = "https://example.com/page#section"
        assertEquals(body + "\n\n{tags}", Hashtags.adoptFromText(body).first)
    }

    @Test
    fun aTextThatAlreadyHasTagsIsLeftAlone() {
        assertEquals("x {tags}" to emptyList<String>(), Hashtags.adoptFromText("x {tags}"))
    }
}
