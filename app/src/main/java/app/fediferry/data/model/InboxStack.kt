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
 * A stack in the inbox: a named pile posts are put on while they wait — "To
 * research", "For the weekend", "Ready to post". A post sits on one stack at
 * most; posts on none are the inbox's "New" section, where shares arrive.
 *
 * Stacks are stages as much as topics: a post moves from one to the next as
 * it gets done, and a whole stack can be posted at once.
 */
@Entity(tableName = "stacks")
data class InboxStack(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Int = 0,
    /** Folded shut in the inbox, showing only its name and how many it holds. */
    val collapsed: Boolean = false,
)
