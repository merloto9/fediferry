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
 * One hashtag in the list the user keeps in Settings. Templates pick a subset
 * of these, and the editor offers all of them.
 */
@kotlinx.serialization.Serializable
@Entity(tableName = "hashtags")
data class Hashtag(
    /** With its #, as it is posted. */
    @PrimaryKey val tag: String,
    val sortOrder: Int = 0,
)

/**
 * How many sent posts carried a hashtag. Counted for every hashtag a post went
 * out with, on the list or not, so the editor can offer the ones used most
 * first. Kept apart from the posts, so removing old posts keeps the counts.
 */
@kotlinx.serialization.Serializable
@Entity(tableName = "hashtag_usage")
data class HashtagUsage(
    /** The tag's [Hashtags.key]: lower case, so #Meme and #meme count together. */
    @PrimaryKey val key: String,
    val uses: Int,
)
