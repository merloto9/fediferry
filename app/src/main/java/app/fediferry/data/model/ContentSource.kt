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

/** One raw value a source delivers, as a placeholder mapping refers to it. */
data class SourceField(val name: String, val description: String)

/**
 * Where an item's picture and text were fetched from.
 *
 * Each source delivers its own raw fields — whatever that service actually
 * publishes about a post — and a [PlaceholderKey] says, per source, how those
 * fields become a placeholder's value. Keeping the two apart is the point: a
 * field that is useful on one service is boilerplate on another, and only the
 * user can say which is which for the way they post.
 *
 * Stored by [name], so the order and labels here can change freely.
 */
enum class ContentSource(val label: String, val fields: List<SourceField>) {
    NINEGAG(
        "9GAG",
        listOf(
            SourceField("title", "The post's title — usually the joke itself."),
            SourceField("description", "The text under the title. Most posts leave it empty."),
            SourceField("hashtags", "The post's tags as hashtags, e.g. #meme #funny."),
            SourceField("section", "The section or user page the post was made in."),
            SourceField("author", "The poster's 9GAG username."),
            SourceField("alt", "9GAG's own description of the picture."),
        ),
    ),
    PINTEREST(
        "Pinterest",
        listOf(
            SourceField("title", "The pin's title, without the keyword tail Pinterest adds for search engines."),
            SourceField("description", "The pin's description — often Pinterest's own stock sentence rather than the pinner's."),
        ),
    ),
    REDDIT(
        "Reddit",
        listOf(
            SourceField("title", "The post's title."),
            SourceField("subreddit", "The subreddit it was posted in, without the r/."),
        ),
    ),
    YOUTUBE(
        "YouTube",
        listOf(
            SourceField("text", "The community post's text."),
            SourceField("channel", "The channel's name."),
        ),
    ),
    ;

    companion object {
        fun fromName(name: String?): ContentSource? = entries.firstOrNull { it.name == name }
    }
}
