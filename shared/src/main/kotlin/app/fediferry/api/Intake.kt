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
package app.fediferry.api

import kotlinx.serialization.Serializable

@Serializable
data class MediaAssetDto(
    val id: String,
    val sha256: String,
    val mime: String,
    val width: Int? = null,
    val height: Int? = null,
    val bytes: Long,
    /** The asset this one was made from by an edit, if any. */
    val parentId: String? = null,
)

/** How a share was made on the phone. On the server every share becomes a draft; the mode decides what the phone does next. */
object IngestModes {
    const val SAVE = "SAVE"
    const val COMPOSE = "COMPOSE"
    const val POST_NOW = "POST_NOW"
}

@Serializable
data class IngestResult(
    /** The draft the share became or joined; null when a repeated share's draft is gone already, published or deleted. */
    val post: PostDto? = null,
    /** The share completed a draft that had only its link, or only its picture. */
    val paired: Boolean = false,
    val replayed: Boolean = false,
    /** The picture behind a shared link was fetched by the server. */
    val resolved: Boolean = false,
    /** A post with the same picture exists already; that post is returned. */
    val duplicate: Boolean = false,
)
