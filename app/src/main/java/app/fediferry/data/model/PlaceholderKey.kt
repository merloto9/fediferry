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
import app.fediferry.module.Modules

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

    val isTags: Boolean get() = id == TAGS_ID

    companion object {
        const val CAPTION_ID = "caption"

        const val TAGS_ID = "tags"

        /**
         * The post's hashtags. Reserved: it exists on every install, cannot be
         * renamed or deleted, and is filled when the post is sent rather than
         * when the draft is written, so hashtags stay editable until then. Its
         * recipes say which hashtags each source adds.
         */
        const val TAGS = "tags"

        /** Placeholders the engine fills itself from the share. */
        val BUILT_IN = setOf("link", "date")

        /** Names no user-defined placeholder can take. */
        val RESERVED = BUILT_IN + TAGS

        private val NAME = Regex("""\w+""")

        fun isValidName(name: String): Boolean = NAME.matches(name) && name !in RESERVED

        /**
         * What `{caption}` meant before placeholders could be defined, as each
         * module's default recipe says: the title on 9GAG and Reddit, a
         * community post's text on YouTube, and nothing from Pinterest.
         */
        fun seed() = PlaceholderKey(
            id = CAPTION_ID,
            name = "caption",
            mappings = defaultMappings("caption"),
        )

        /** The reserved `{tags}`: every source's hashtags recipe, empty to start. */
        fun tagsSeed() = PlaceholderKey(
            id = TAGS_ID,
            name = TAGS,
            mappings = defaultMappings(TAGS),
            sortOrder = -1,
        )

        private fun defaultMappings(name: String): Map<String, String> =
            Modules.all.mapNotNull { m -> m.defaultRecipes[name]?.let { m.source.name to it } }.toMap()
    }
}
