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
import androidx.room.Query
import androidx.room.Transaction
import app.fediferry.data.model.HashtagUsage

@Dao
interface HashtagUsageDao {
    @Query("SELECT * FROM hashtag_usage")
    suspend fun all(): List<HashtagUsage>

    @Query("INSERT OR IGNORE INTO hashtag_usage (`key`, uses) VALUES (:key, 0)")
    suspend fun ensure(key: String)

    @Query("UPDATE hashtag_usage SET uses = uses + 1 WHERE `key` = :key")
    suspend fun bump(key: String)

    /** One more post carried each of [keys]. */
    @Transaction
    suspend fun countUse(keys: Collection<String>) {
        keys.forEach {
            ensure(it)
            bump(it)
        }
    }
}
