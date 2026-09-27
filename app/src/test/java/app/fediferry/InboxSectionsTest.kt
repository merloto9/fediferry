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

import app.fediferry.data.model.InboxStack
import app.fediferry.data.model.Item
import app.fediferry.ui.inbox.InboxSection
import org.junit.Assert.assertEquals
import org.junit.Test

class InboxSectionsTest {

    private fun item(id: String, stack: String? = null) = Item(id = id, templateId = "t", stackId = stack)

    private val research = InboxStack("r", "To research", sortOrder = 0)
    private val ready = InboxStack("w", "Ready to post", sortOrder = 1)

    @Test
    fun `New comes first, then every stack in its order, posts kept in theirs`() {
        val sections = InboxSection.of(
            listOf(item("a", "w"), item("b"), item("c", "r"), item("d", "w"), item("e")),
            listOf(research, ready),
        )

        assertEquals(listOf(null, "To research", "Ready to post"), sections.map { it.stack?.name })
        assertEquals(listOf("b", "e"), sections[0].items.map { it.id })
        assertEquals(listOf("c"), sections[1].items.map { it.id })
        assertEquals(listOf("a", "d"), sections[2].items.map { it.id })
    }

    @Test
    fun `a post whose stack is gone is back in New, not lost`() {
        val sections = InboxSection.of(listOf(item("a", "deleted")), listOf(ready))

        assertEquals(listOf("a"), sections[0].items.map { it.id })
    }

    @Test
    fun `an empty stack still shows, ready to be moved onto`() {
        val sections = InboxSection.of(emptyList(), listOf(ready))

        assertEquals(2, sections.size)
        assertEquals(emptyList<Item>(), sections[1].items)
    }
}
