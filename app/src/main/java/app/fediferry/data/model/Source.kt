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

/** Where a followed feed comes from. Only YouTube for now; the column exists so
 *  adding another does not need a migration. */
enum class SourceKind { YOUTUBE }

/**
 * A feed the user follows in the Sources space.
 *
 * Only the subscription is stored. Posts are fetched live and never persisted —
 * they are someone else's content, and only the picture actually chosen becomes
 * an item.
 */
@Entity(tableName = "sources")
data class Source(
    @PrimaryKey val id: String,
    val kind: SourceKind,
    /** The channel handle without the @. */
    val handle: String,
    val displayName: String,
    val addedAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0,
) {
    val url: String get() = "https://www.youtube.com/@$handle/posts"
}
