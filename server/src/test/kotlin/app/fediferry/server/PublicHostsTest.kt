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
package app.fediferry.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class PublicHostsTest {

    @Test
    fun `instance names are public host names`() {
        listOf("mastodon.social", "chaos.social", "social.example-instance.org", "xn--80ak6aa92e.com").forEach {
            assertTrue(it, PublicHosts.isInstanceName(it))
        }
        listOf(
            "localhost", "127.0.0.1", "169.254.169.254", "10.0.0.5", "[::1]", "::1",
            "mastodon.social:8443", "intranet.local", "db.internal", "nas.home.arpa", "router.lan", "nodots", "",
        ).forEach { assertFalse(it, PublicHosts.isInstanceName(it)) }
    }

    @Test
    fun `only public addresses are connected to`() {
        listOf("1.1.1.1", "8.8.8.8", "2a00:1450:4001:80b::200e").forEach {
            assertTrue(it, PublicHosts.isPublic(InetAddress.getByName(it)))
        }
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1")
            .forEach { assertFalse(it, PublicHosts.isPublic(InetAddress.getByName(it))) }
    }
}
