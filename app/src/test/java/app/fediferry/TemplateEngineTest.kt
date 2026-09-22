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

import app.fediferry.data.model.ContentSource
import app.fediferry.data.model.Item
import app.fediferry.data.model.PlaceholderKey
import app.fediferry.data.model.Template
import app.fediferry.template.TemplateEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TemplateEngineTest {

    private fun template(body: String, tags: String = "#meme") =
        Template.seed().copy(body = body, tags = tags)

    @Test
    fun `fills link and tags`() {
        val out = TemplateEngine.render(
            template("{tags}\n\nvia {link}"),
            emptyList(),
            TemplateEngine.Inputs(link = "https://instagram.com/p/abc"),
        )
        assertEquals("#meme\n\nvia https://instagram.com/p/abc", out)
    }

    @Test
    fun `drops the attribution line when no link was shared`() {
        // Instagram often sends only an image; a dangling "via" must not ship.
        val out = TemplateEngine.render(
            template("{tags}\n\nvia {link}"),
            emptyList(),
            TemplateEngine.Inputs(link = null),
        )
        assertEquals("#meme", out)
    }

    @Test
    fun `a placeholder nobody defined is left as typed`() {
        // {caption} is a user-defined key now; with none defined it is an
        // authoring mistake like any other, and must show rather than vanish.
        val out = TemplateEngine.render(template("{caption}", tags = ""), emptyList(), TemplateEngine.Inputs())
        assertEquals("{caption}", out)
    }

    @Test
    fun `renders the date placeholder`() {
        val out = TemplateEngine.render(
            template("{date}", tags = ""),
            emptyList(),
            TemplateEngine.Inputs(now = Instant.parse("2026-01-15T10:00:00Z")),
        )
        assertTrue(out.startsWith("2026-01-1"))
    }

    @Test
    fun `leaves unknown placeholders alone`() {
        val out = TemplateEngine.render(
            template("{nope}", tags = ""),
            emptyList(),
            TemplateEngine.Inputs(),
        )
        assertEquals("{nope}", out)
    }

    /**
     * The rule the pairing code uses to decide whether a draft's body is still
     * the template's own output, and therefore safe to re-render once a link
     * turns up. Hand-edited text must survive.
     */
    @Test
    fun `an unedited body matches the link-less render exactly`() {
        val t = template("{tags}\n\nvia {link}")
        val unlinked = TemplateEngine.render(t, emptyList(), TemplateEngine.Inputs(link = null))
        assertEquals("#meme", unlinked)

        val relinked = TemplateEngine.render(
            t,
            emptyList(),
            TemplateEngine.Inputs(link = "https://instagram.com/p/abc"),
        )
        assertEquals("#meme\n\nvia https://instagram.com/p/abc", relinked)
        assertTrue(relinked != unlinked)
    }

    // --- user-defined placeholders ---------------------------------------

    private val caption = PlaceholderKey.seed()

    private fun from(source: ContentSource?, vararg fields: Pair<String, String>) =
        TemplateEngine.Inputs(link = "https://example.com/p/1", source = source, fields = fields.toMap())

    @Test
    fun `caption is the title on 9GAG and Reddit and the text on YouTube`() {
        val t = template("{caption}", tags = "")

        assertEquals("A joke", TemplateEngine.render(t, listOf(caption), from(ContentSource.NINEGAG, "title" to "A joke")))
        assertEquals("A joke", TemplateEngine.render(t, listOf(caption), from(ContentSource.REDDIT, "title" to "A joke")))
        assertEquals("Hello", TemplateEngine.render(t, listOf(caption), from(ContentSource.YOUTUBE, "text" to "Hello")))
    }

    @Test
    fun `caption no longer fills from Pinterest, and its line drops out`() {
        val out = TemplateEngine.render(
            template("{caption}\n\n{tags}"),
            listOf(caption),
            from(ContentSource.PINTEREST, "title" to "This Pin was discovered by someone", "description" to "Pinterest filler"),
        )

        assertEquals("#meme", out)
    }

    @Test
    fun `a template that does not read a source leaves its placeholders empty`() {
        val t = template("{caption}\n\n{tags}\nvia {link}").copy(excludedSources = setOf(ContentSource.NINEGAG))

        val out = TemplateEngine.render(t, listOf(caption), from(ContentSource.NINEGAG, "title" to "A joke"))

        assertEquals("#meme\nvia https://example.com/p/1", out)
    }

    @Test
    fun `a screenshot has no source, so every user placeholder is empty`() {
        val out = TemplateEngine.render(template("{caption}\n{tags}"), listOf(caption), TemplateEngine.Inputs())

        assertEquals("#meme", out)
    }

    @Test
    fun `a recipe can combine several fields`() {
        val key = PlaceholderKey("k", "where", mapOf(ContentSource.REDDIT.name to "r/{subreddit}: {title}"))

        val out = TemplateEngine.render(
            template("{where}", tags = ""),
            listOf(key),
            from(ContentSource.REDDIT, "subreddit" to "memes", "title" to "A joke"),
        )

        assertEquals("r/memes: A joke", out)
    }

    @Test
    fun `a field the post did not have empties its recipe line rather than leaking`() {
        val key = PlaceholderKey("k", "about", mapOf(ContentSource.NINEGAG.name to "{title}\nby {author}"))

        val out = TemplateEngine.render(
            template("{about}", tags = ""),
            listOf(key),
            from(ContentSource.NINEGAG, "title" to "A joke"),
        )

        assertEquals("A joke", out)
        assertTrue("{author}" !in out)
    }

    @Test
    fun `a field the source never sends is shown, as an authoring mistake`() {
        val key = PlaceholderKey("k", "odd", mapOf(ContentSource.PINTEREST.name to "{subreddit}"))

        val value = TemplateEngine.valueOf(key, template(""), from(ContentSource.PINTEREST))

        assertEquals("{subreddit}", value)
    }

    @Test
    fun `a built-in wins over a user key with the same name`() {
        val clash = PlaceholderKey("k", "link", mapOf(ContentSource.REDDIT.name to "{title}"))

        val out = TemplateEngine.render(
            template("{link}", tags = ""),
            listOf(clash),
            from(ContentSource.REDDIT, "title" to "A joke"),
        )

        assertEquals("https://example.com/p/1", out)
    }

    @Test
    fun `an item's stored source data renders the same as it did on arrival`() {
        val item = Item(
            id = "i",
            templateId = "t",
            sourceUrl = "https://9gag.com/gag/abc",
            origin = ContentSource.NINEGAG,
            sourceFields = mapOf("title" to "A joke"),
        )

        val out = TemplateEngine.render(
            template("{caption}\n\nvia {link}", tags = ""),
            listOf(caption),
            TemplateEngine.inputsOf(item),
        )

        assertEquals("A joke\n\nvia https://9gag.com/gag/abc", out)
    }

    @Test
    fun `finds the placeholders nothing fills`() {
        assertEquals(
            listOf("capton", "nope"),
            TemplateEngine.unknownIn("{capton} {tags} {nope} {capton}", PlaceholderKey.BUILT_IN + "caption"),
        )
    }

    @Test
    fun `names that would clash or cannot be typed are refused`() {
        assertTrue(PlaceholderKey.isValidName("caption"))
        assertTrue(PlaceholderKey.isValidName("sub_2"))
        assertTrue(!PlaceholderKey.isValidName("link"))
        assertTrue(!PlaceholderKey.isValidName("two words"))
        assertTrue(!PlaceholderKey.isValidName(""))
    }
}
