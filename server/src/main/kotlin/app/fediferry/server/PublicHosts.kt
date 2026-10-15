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

import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Keeps the server's own requests on the public internet. A project client
 * names the instances (and, through shared links, the pages) the server
 * contacts; without this, it could point the server at its own network —
 * the database next door, a cloud metadata address — with a token attached.
 *
 * `FEDIFERRY_ALLOW_PRIVATE_HOSTS=true` lifts it, for an instance run in the
 * same private network as the server.
 */
internal object PublicHosts {

    val allowPrivate: Boolean = System.getenv("FEDIFERRY_ALLOW_PRIVATE_HOSTS").equals("true", ignoreCase = true)

    private val LABEL = Regex("""[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?""")
    private val PRIVATE_SUFFIXES = listOf(".local", ".localhost", ".internal", ".lan", ".home", ".home.arpa", ".corp", ".intranet")

    /**
     * Whether [host] may name an instance: a host name with at least one dot,
     * no port, no address literal, and none of the names kept for private
     * networks. Checked before anything is sent.
     */
    fun isInstanceName(host: String): Boolean {
        if (allowPrivate) return host.isNotBlank() && '/' !in host
        if (host.length > 253 || '.' !in host) return false
        val labels = host.split('.')
        if (labels.any { !it.matches(LABEL) }) return false
        // An address written as a name (127.0.0.1, 10.1.2.3) is not one.
        if (labels.all { it.all(Char::isDigit) }) return false
        if (labels.last().all(Char::isDigit)) return false
        return PRIVATE_SUFFIXES.none { host == it.removePrefix(".") || host.endsWith(it) }
    }

    /** Whether the server may connect to [address]: anything but loopback, private, link-local and the like. */
    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
        val b = address.address
        return when (address) {
            is Inet4Address -> !(
                (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64 || // 100.64.0.0/10, carrier-grade NAT
                    (b[0].toInt() and 0xff) == 0 || // 0.0.0.0/8
                    (b[0].toInt() and 0xff) >= 240 // reserved and broadcast
                )
            is Inet6Address -> (b[0].toInt() and 0xfe) != 0xfc // fc00::/7, unique local
            else -> true
        }
    }

    /**
     * Resolves as usual, then refuses a name whose addresses are not public,
     * at the moment of connecting — so a name that points somewhere private,
     * or is pointed there later, is refused too.
     */
    val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            if (allowPrivate) return all
            val public = all.filter(::isPublic)
            if (public.isEmpty()) throw UnknownHostException("$hostname resolves to no public address")
            return public
        }
    }
}
