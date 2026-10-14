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
package app.fediferry.channels

import android.content.res.Resources
import app.fediferry.R

/** Channel errors in words; anything else keeps its code. */
object ChannelMessages {
    fun describe(resources: Resources, code: String, args: Map<String, String>): String = when (code) {
        "platform.unsupported" -> resources.getString(R.string.channel_error_unsupported, args["platform"].orEmpty())
        "platform.unknown" -> resources.getString(R.string.channel_error_unknown, args["software"].orEmpty())
        "platform.unreachable", "platform.undetected" -> resources.getString(R.string.channel_error_undetected, args["instance"].orEmpty())
        "channel.account_in_use" -> resources.getString(R.string.channel_error_in_use, args["channel"].orEmpty())
        else -> resources.getString(R.string.channels_failed, code)
    }
}
