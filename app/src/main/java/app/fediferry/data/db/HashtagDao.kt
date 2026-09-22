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
import app.fediferry.data.model.Hashtag
import kotlinx.coroutines.flow.Flow

@Dao
interface HashtagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: Hashtag)

    @Query("SELECT * FROM hashtags ORDER BY sortOrder, tag COLLATE NOCASE")
    fun observeAll(): Flow<List<Hashtag>>

    @Query("SELECT * FROM hashtags ORDER BY sortOrder, tag COLLATE NOCASE")
    suspend fun all(): List<Hashtag>

    @Query("DELETE FROM hashtags WHERE tag = :tag")
    suspend fun delete(tag: String)

    @Query("SELECT COUNT(*) FROM hashtags")
    suspend fun count(): Int
}
