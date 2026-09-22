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

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AltTextMode {
    /** Emit no `description` at all. */
    NONE,

    /** Always use [Template.staticAltText]. */
    STATIC,

    /** Ask the configured vision endpoint; fall back to nothing on failure. */
    VISION,
}

/**
 * A named bundle of posting defaults — in practice a topic, with its hashtags.
 * Body text carries the built-in `{link}`, `{tags}` and `{date}` placeholders
 * and any the user defined; see [app.fediferry.template.TemplateEngine].
 */
@Entity(tableName = "templates")
data class Template(
    @PrimaryKey val id: String,
    val name: String,
    val body: String,
    val tags: String = "",
    val visibility: Visibility = Visibility.PUBLIC,
    val contentWarning: String? = null,
    val altTextMode: AltTextMode = AltTextMode.NONE,
    val staticAltText: String? = null,
    val accountId: String? = null,
    val isDefault: Boolean = false,
    val sortOrder: Int = 0,
    /**
     * Sources whose data this template ignores: their user-defined placeholders
     * come out empty. Stored as the excluded ones so a source added later is on
     * for every template from the start.
     */
    @ColumnInfo(defaultValue = "") val excludedSources: Set<ContentSource> = emptySet(),
    /**
     * Whether a post starts with its source's own hashtags — whatever the
     * source's `{tags}` recipe yields — ticked beside [tags]. Each post can
     * still change it in the editor.
     */
    @ColumnInfo(defaultValue = "1") val addSourceHashtags: Boolean = true,
) {
    fun usesSource(source: ContentSource): Boolean = source !in excludedSources

    /** [tags] holds the template's picks from the hashtag list, space-separated. */
    val hashtagList: List<String> get() = Hashtags.parse(tags)

    companion object {
        const val DEFAULT_ID = "default"

        /** Seeded on first run so a fresh install can post immediately. */
        fun seed() = Template(
            id = DEFAULT_ID,
            name = "Meme",
            body = "{tags}\n\nvia {link}",
            tags = "#meme",
            visibility = Visibility.PUBLIC,
            altTextMode = AltTextMode.NONE,
            isDefault = true,
        )
    }
}
