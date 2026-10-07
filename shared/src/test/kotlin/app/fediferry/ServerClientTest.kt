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

import app.fediferry.client.ServerClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerClientTest {

    @Test
    fun `an address becomes a base URL, https unless it says otherwise`() {
        assertEquals("https://fediferry.example.org/", ServerClient.normalise("fediferry.example.org")?.toString())
        assertEquals("https://fediferry.example.org/", ServerClient.normalise(" https://fediferry.example.org/ ")?.toString())
        assertEquals("http://10.0.2.2:8088/", ServerClient.normalise("http://10.0.2.2:8088")?.toString())
        // A server behind a sub-path keeps it; the API path is added after it.
        assertEquals("https://host/sub", ServerClient.normalise("host/sub/")?.toString())
        assertNull(ServerClient.normalise(""))
        assertNull(ServerClient.normalise("   "))
    }
}
