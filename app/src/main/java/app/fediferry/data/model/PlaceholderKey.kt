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
package app.fediferry.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A placeholder the user defines, such as `{caption}`, and how to fill it for
 * each source.
 *
 * Each mapping is a small recipe over that source's raw fields — `{title}`, or
 * `r/{subreddit}: {title}` — in the same brace syntax as a template body. A
 * source with no recipe, or a blank one, leaves the placeholder empty, and an
 * empty placeholder drops its line from the post exactly as a missing link does.
 */
@Entity(tableName = "placeholder_keys")
data class PlaceholderKey(
    @PrimaryKey val id: String,
    /** Used in a template body as `{name}`. Letters, digits and underscores. */
    val name: String,
    /** Recipe per [ContentSource.name]. */
    val mappings: Map<String, String> = emptyMap(),
    val sortOrder: Int = 0,
) {
    fun recipeFor(source: ContentSource): String = mappings[source.name].orEmpty()

    companion object {
        const val CAPTION_ID = "caption"

        /** Placeholders the engine fills itself; a user key cannot take these names. */
        val BUILT_IN = setOf("link", "tags", "date")

        private val NAME = Regex("""\w+""")

        fun isValidName(name: String): Boolean = NAME.matches(name) && name !in BUILT_IN

        /**
         * What `{caption}` meant before placeholders could be defined: the post's
         * title on 9GAG and Reddit, and a community post's text on YouTube.
         *
         * Pinterest is deliberately left out. Its title is too often generic
         * filler, which prefilled every post with it.
         */
        fun seed() = PlaceholderKey(
            id = CAPTION_ID,
            name = "caption",
            mappings = mapOf(
                ContentSource.NINEGAG.name to "{title}",
                ContentSource.REDDIT.name to "{title}",
                ContentSource.YOUTUBE.name to "{text}",
            ),
        )
    }
}
