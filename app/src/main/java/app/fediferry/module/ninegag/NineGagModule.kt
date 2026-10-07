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

import app.fediferry.R
import app.fediferry.data.model.ContentSource
import app.fediferry.data.model.SourceField
import app.fediferry.module.SourceModule
import okhttp3.OkHttpClient

object NineGagModule : SourceModule {
    override val source = ContentSource.NINEGAG
    override val name = "9GAG"
    override val summary = R.string.extra_module_ninegag_summary
    override val recognises = listOf("9gag.com/gag/…")
    override val fields = listOf(
        SourceField("title", R.string.extra_field_ninegag_title),
        SourceField("description", R.string.extra_field_ninegag_description),
        SourceField("hashtags", R.string.extra_field_ninegag_hashtags),
        SourceField("section", R.string.extra_field_ninegag_section),
        SourceField("author", R.string.extra_field_ninegag_author),
        SourceField("alt", R.string.extra_field_ninegag_alt),
    )
    override val defaultRecipes = mapOf("caption" to "{title}")

    override fun resolver(http: OkHttpClient) = NineGagResolver(http)
}
