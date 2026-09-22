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
package app.fediferry.module.ninegag

import app.fediferry.data.model.ContentSource
import app.fediferry.data.model.SourceField
import app.fediferry.module.SourceModule
import okhttp3.OkHttpClient

object NineGagModule : SourceModule {
    override val source = ContentSource.NINEGAG
    override val name = "9GAG"
    override val summary =
        "Fetches the picture or video behind a shared post — animated posts as the video, not a still."
    override val recognises = listOf("9gag.com/gag/…")
    override val fields = listOf(
        SourceField("title", "The post's title — usually the joke itself."),
        SourceField("description", "The text under the title. Most posts leave it empty."),
        SourceField("hashtags", "The post's tags as hashtags, e.g. #meme #funny."),
        SourceField("section", "The section or user page the post was made in."),
        SourceField("author", "The poster's 9GAG username."),
        SourceField("alt", "9GAG's own description of the picture."),
    )
    override val defaultRecipes = mapOf("caption" to "{title}")

    override fun resolver(http: OkHttpClient) = NineGagResolver(http)
}
