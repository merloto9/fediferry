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
import app.fediferry.data.model.Template
import kotlinx.coroutines.flow.Flow

@Dao
interface TemplateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(template: Template)

    @Query("SELECT * FROM templates ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<Template>>

    @Query("SELECT * FROM templates ORDER BY sortOrder, name")
    suspend fun all(): List<Template>

    @Query("SELECT * FROM templates WHERE id = :id")
    suspend fun byId(id: String): Template?

    @Query("SELECT * FROM templates WHERE isDefault = 1 LIMIT 1")
    suspend fun defaultTemplate(): Template?

    @Query("UPDATE templates SET isDefault = (id = :id)")
    suspend fun setDefault(id: String)

    @Query("DELETE FROM templates WHERE id = :id AND isDefault = 0")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM templates")
    suspend fun count(): Int
}
