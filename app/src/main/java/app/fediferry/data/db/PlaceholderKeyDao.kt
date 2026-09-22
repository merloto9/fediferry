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
import app.fediferry.data.model.PlaceholderKey
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaceholderKeyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(key: PlaceholderKey)

    @Query("SELECT * FROM placeholder_keys ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<PlaceholderKey>>

    @Query("SELECT * FROM placeholder_keys ORDER BY sortOrder, name")
    suspend fun all(): List<PlaceholderKey>

    @Query("DELETE FROM placeholder_keys WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM placeholder_keys")
    suspend fun count(): Int
}
