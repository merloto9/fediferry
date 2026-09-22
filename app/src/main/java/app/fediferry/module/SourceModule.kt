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
package app.fediferry.module

import app.fediferry.data.model.ContentSource
import app.fediferry.data.model.SourceField
import app.fediferry.link.LinkResolver
import app.fediferry.module.ninegag.NineGagModule
import app.fediferry.module.pinterest.PinterestModule
import app.fediferry.module.reddit.RedditModule
import app.fediferry.module.youtube.YouTubeModule
import okhttp3.OkHttpClient

/**
 * Everything the app knows about one source, in one place: what it recognises,
 * how it fetches, which raw fields it sends, and what those fields fill by
 * default. Each source lives in its own package under `module/`, so adding or
 * changing one never means hunting through the rest of the app.
 *
 * What a user does with the fields — the recipes behind each placeholder — is
 * theirs, and lives in the database. [defaultRecipes] only seeds it.
 */
interface SourceModule {
    /** The stable id items and templates store. */
    val source: ContentSource

    val name: String

    /** One sentence for the Settings list: what sharing from here does. */
    val summary: String

    /** The link shapes it picks up, as the user would recognise them. */
    val recognises: List<String>

    val fields: List<SourceField>

    /** Recipes per placeholder name, for a fresh install or a new placeholder. */
    val defaultRecipes: Map<String, String>

    /**
     * The resolver for shared links, or null when the module is reached some
     * other way — YouTube feeds the Sources space, not the share sheet.
     */
    fun resolver(http: OkHttpClient): LinkResolver?
}

/** The loaded modules, in the order Settings lists them. */
object Modules {
    val all: List<SourceModule> = listOf(NineGagModule, PinterestModule, RedditModule, YouTubeModule)

    fun of(source: ContentSource): SourceModule = all.first { it.source == source }

    fun resolvers(http: OkHttpClient): List<LinkResolver> = all.mapNotNull { it.resolver(http) }
}
