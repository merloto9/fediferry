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

import app.fediferry.mastodon.Pkce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {

    @Test
    fun `the challenge is the verifier's SHA-256, base64url without padding`() {
        // Worked out separately, with Python's hashlib and base64.urlsafe_b64encode.
        assertEquals("YsACIAlNs4BZ-lC8EHEiYfN0mBRGfnhIor2v04yH9bU", Pkce.challenge("Fz3x0Z9r_PkCE-verifier-for-fediferry-tests-01"))
    }

    @Test
    fun `a verifier is new each time and fits the spec`() {
        val a = Pkce.verifier()
        assertNotEquals(a, Pkce.verifier())
        assertEquals(43, a.length)
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }
}
