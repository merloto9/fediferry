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

/**
 * The stable id of a source module — what items and templates store — and the
 * service's name, which is a product name and the same in every language.
 * Everything else about a source lives in its module.
 *
 * Stored by [name], so the order here can change freely.
 */
enum class ContentSource(
    val label: String,
    /** The raw values the source delivers, by the names placeholder recipes use. */
    val fieldNames: List<String>,
) {
    NINEGAG("9GAG", listOf("title", "description", "hashtags", "section", "author", "alt")),
    PINTEREST("Pinterest", listOf("title", "description")),
    REDDIT("Reddit", listOf("title", "subreddit")),
    YOUTUBE("YouTube", listOf("text", "channel")),
    ;

    companion object {
        fun fromName(name: String?): ContentSource? = entries.firstOrNull { it.name == name }
    }
}
