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

import app.fediferry.data.model.ContentWarningPresets
import app.fediferry.data.model.ContentWarningPresets.Preset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentWarningPresetsTest {

    private val violence = ContentWarningPresets.all.first { it.de == "Gewaltdarstellung" }
    private val gore = ContentWarningPresets.all.first { it.de == "Blut, Gore" }

    @Test
    fun `preset reads German first`() {
        assertEquals("Gewaltdarstellung / Depiction of violence", violence.text)
    }

    @Test
    fun `first pick fills an empty field`() {
        assertEquals(violence.text, ContentWarningPresets.toggle("", violence))
        assertEquals(violence.text, ContentWarningPresets.toggle(null, violence))
    }

    @Test
    fun `second pick appends rather than replaces`() {
        val both = ContentWarningPresets.toggle(violence.text, gore)
        assertEquals("${violence.text}, ${gore.text}", both)
        assertTrue(ContentWarningPresets.isApplied(both, violence))
        assertTrue(ContentWarningPresets.isApplied(both, gore))
    }

    @Test
    fun `picking an applied preset takes it back out`() {
        val both = ContentWarningPresets.toggle(violence.text, gore)

        assertEquals(gore.text, ContentWarningPresets.toggle(both, violence))
        assertEquals(violence.text, ContentWarningPresets.toggle(both, gore))
        assertEquals("", ContentWarningPresets.toggle(violence.text, violence))
    }

    @Test
    fun `a preset with a comma of its own survives the round trip`() {
        // "Blut, Gore / Blood, gore" must not be read as two separate warnings.
        val line = ContentWarningPresets.toggle(ContentWarningPresets.toggle("", gore), violence)
        assertEquals(violence.text, ContentWarningPresets.toggle(line, gore))
    }

    @Test
    fun `hand-written text is kept when a preset is added or removed`() {
        val mine = "Mein eigener Hinweis / My own warning"

        val added = ContentWarningPresets.toggle(mine, violence)
        assertEquals("$mine, ${violence.text}", added)
        assertEquals(mine, ContentWarningPresets.toggle(added, violence))
        assertFalse(ContentWarningPresets.isApplied(mine, violence))
    }

    @Test
    fun `a trailing separator left by an edit does not survive`() {
        assertEquals("Etwas, ${violence.text}", ContentWarningPresets.toggle("Etwas, ", violence))
    }

    @Test
    fun `presets are unique and bilingual`() {
        val texts = ContentWarningPresets.all.map(Preset::text)
        assertEquals(texts.size, texts.toSet().size)
        assertTrue(ContentWarningPresets.all.none { it.de.isBlank() || it.en.isBlank() })
    }
}
