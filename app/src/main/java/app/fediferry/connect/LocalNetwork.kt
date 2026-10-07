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
package app.fediferry.connect

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.fediferry.client.ServerClient

/**
 * Android 17 keeps apps out of the local network unless they are allowed in:
 * a FediFerry server at home (192.168.…, a `.local` name, the emulator's host
 * 10.0.2.2) is unreachable without [PERMISSION], while one on the internet is
 * not affected.
 */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** Whether [address] points into a private network. */
    fun isLocal(address: String): Boolean {
        val host = ServerClient.normalise(address)?.host ?: return false
        if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")) return true
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        val (a, b) = parts
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
    }

    /** Whether this Android has the restriction at all; older ones do not know the permission. */
    fun exists(context: Context): Boolean =
        runCatching { context.packageManager.getPermissionInfo(PERMISSION, 0) }.isSuccess

    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** True when connecting to [address] needs the permission first. */
    fun mustAsk(context: Context, address: String): Boolean =
        isLocal(address) && exists(context) && !granted(context)
}
