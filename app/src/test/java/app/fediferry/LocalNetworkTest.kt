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

import app.fediferry.connect.LocalNetwork
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkTest {

    @Test
    fun `private addresses and home names count as local`() {
        listOf(
            "http://10.0.2.2:8088", "192.168.1.20", "http://172.16.0.5", "172.31.255.1",
            "169.254.3.4", "fediferry.local", "nas.lan", "box.home.arpa",
        ).forEach { assertTrue(it, LocalNetwork.isLocal(it)) }
    }

    @Test
    fun `the internet does not`() {
        listOf("fediferry.example.org", "https://8.8.8.8", "172.32.0.1", "192.169.1.1", "", "not a url at all")
            .forEach { assertFalse(it, LocalNetwork.isLocal(it)) }
    }
}
