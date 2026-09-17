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
package app.fediferry.source

/**
 * One post from a followed source, as shown in the Sources space.
 *
 * Nothing here is persisted: a browse is a live view of somebody else's feed.
 * Only the picture the user actually chooses becomes an item.
 */
data class SourcePost(
    val id: String,
    val author: String?,
    val text: String?,
    val publishedText: String?,
    /** Largest available URL for each attached image, in the post's own order. */
    val imageUrls: List<String>,
) {
    val permalink: String get() = "https://www.youtube.com/post/$id"
    val hasImages: Boolean get() = imageUrls.isNotEmpty()
}
