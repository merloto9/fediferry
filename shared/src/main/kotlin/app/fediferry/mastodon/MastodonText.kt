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
package app.fediferry.mastodon

/**
 * Counts characters the way Mastodon does: every link counts as 23, however
 * long, and a mention of someone on another server counts only its user name.
 */
object MastodonText {
    const val LINK_LENGTH = 23

    private val link = Regex("""https?://[^\s<>"]+""")
    private val remoteMention = Regex("""(?<![\w/])@(\w+)@[\w.-]+\w""")

    fun length(text: String): Int {
        val counted = text
            .replace(link) { "x".repeat(LINK_LENGTH) }
            .replace(remoteMention) { "@" + it.groupValues[1] }
        return counted.codePointCount(0, counted.length)
    }
}
