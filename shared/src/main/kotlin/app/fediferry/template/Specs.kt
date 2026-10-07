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
package app.fediferry.template

import app.fediferry.data.model.ContentSource

/**
 * What [TemplateEngine] needs of a template. The app's stored templates and
 * the server's copies of them both provide it, so both render the same way.
 */
interface TemplateSpec {
    val body: String
    val hashtagList: List<String>
    val addSourceHashtags: Boolean
    fun usesSource(source: ContentSource): Boolean
}

/** What [TemplateEngine] needs of a user-defined placeholder. */
interface PlaceholderSpec {
    /** Used in a template body as `{name}`. */
    val name: String
    /** Whether this is the reserved `{tags}`, filled with the post's hashtags at the end. */
    val isTags: Boolean
    fun recipeFor(source: ContentSource): String

    companion object {
        const val TAGS = "tags"
    }
}
