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

import app.fediferry.mastodon.MastodonText
import org.junit.Assert.assertEquals
import org.junit.Test

class MastodonTextTest {

    @Test
    fun `plain text counts every character`() = assertEquals(5, MastodonText.length("hello"))

    @Test
    fun `a link counts as 23 however long it is`() =
        assertEquals(4 + 23, MastodonText.length("via https://www.pinterest.com/pin/123456789012345678/"))

    @Test
    fun `a remote mention counts only its user name`() = assertEquals(6, MastodonText.length("@alice@example.social"))

    @Test
    fun `an emoji counts once`() = assertEquals(1, MastodonText.length("🐸"))
}
