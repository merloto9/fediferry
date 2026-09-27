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
package app.fediferry.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.fediferry.data.model.InboxStack
import kotlinx.coroutines.flow.Flow

@Dao
interface StackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stack: InboxStack)

    @Query("SELECT * FROM stacks ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeAll(): Flow<List<InboxStack>>

    @Query("SELECT * FROM stacks ORDER BY sortOrder, name COLLATE NOCASE")
    suspend fun all(): List<InboxStack>

    @Query("SELECT COUNT(*) FROM stacks")
    suspend fun count(): Int

    @Query("UPDATE stacks SET collapsed = :collapsed WHERE id = :id")
    suspend fun setCollapsed(id: String, collapsed: Boolean)

    @Query("UPDATE stacks SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM stacks WHERE id = :id")
    suspend fun deleteRow(id: String)

    @Query("UPDATE items SET stackId = NULL WHERE stackId = :id")
    suspend fun releaseItems(id: String)

    /** Deletes a stack; its posts go back to New rather than disappearing with it. */
    @Transaction
    suspend fun delete(id: String) {
        releaseItems(id)
        deleteRow(id)
    }

    /** Puts posts on a stack, or on none when [stackId] is null. */
    @Query("UPDATE items SET stackId = :stackId WHERE id IN (:ids)")
    suspend fun move(ids: Collection<String>, stackId: String?)
}
