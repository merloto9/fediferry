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
package app.fediferry.module.youtube

import app.fediferry.data.model.ContentSource
import app.fediferry.data.model.SourceField
import app.fediferry.link.LinkResolver
import app.fediferry.module.SourceModule
import okhttp3.OkHttpClient

object YouTubeModule : SourceModule {
    override val source = ContentSource.YOUTUBE
    override val name = "YouTube"
    override val summary =
        "Follows channels in the Sources space; picking a community post's picture opens it in the editor."
    override val recognises = listOf("Channels added in Sources — not shared links")
    override val fields = listOf(
        SourceField("text", "The community post's text."),
        SourceField("channel", "The channel's name."),
    )
    override val defaultRecipes = mapOf("caption" to "{text}")

    /** Reached from the Sources space, never from a shared link. */
    override fun resolver(http: OkHttpClient): LinkResolver? = null
}
