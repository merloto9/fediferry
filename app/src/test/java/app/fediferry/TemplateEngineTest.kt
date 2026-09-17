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
            TemplateEngine.Inputs(link = "https://instagram.com/p/abc"),
        )
        assertEquals("#meme\n\nvia https://instagram.com/p/abc", out)
    }

    @Test
    fun `drops the attribution line when no link was shared`() {
        // Instagram often sends only an image; a dangling "via" must not ship.
        val out = TemplateEngine.render(
            template("{tags}\n\nvia {link}"),
            TemplateEngine.Inputs(link = null),
        )
        assertEquals("#meme", out)
    }

    @Test
    fun `caption degrades to empty rather than leaking the placeholder`() {
        val out = TemplateEngine.render(
            template("{caption}", tags = ""),
            TemplateEngine.Inputs(caption = null),
        )
        assertEquals("", out)
        assertTrue("{caption}" !in out)
    }

    @Test
    fun `renders the date placeholder`() {
        val out = TemplateEngine.render(
            template("{date}", tags = ""),
            TemplateEngine.Inputs(now = Instant.parse("2026-01-15T10:00:00Z")),
        )
        assertTrue(out.startsWith("2026-01-1"))
    }

    @Test
    fun `leaves unknown placeholders alone`() {
        val out = TemplateEngine.render(
            template("{nope}", tags = ""),
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
        val unlinked = TemplateEngine.render(t, TemplateEngine.Inputs(link = null))
        assertEquals("#meme", unlinked)

        val relinked = TemplateEngine.render(
            t,
            TemplateEngine.Inputs(link = "https://instagram.com/p/abc"),
        )
        assertEquals("#meme\n\nvia https://instagram.com/p/abc", relinked)
        assertTrue(relinked != unlinked)
    }
}
