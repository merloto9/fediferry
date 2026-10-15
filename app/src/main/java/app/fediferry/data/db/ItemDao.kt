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
import androidx.room.Update
import app.fediferry.data.model.Item
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: Item)

    @Update
    suspend fun update(item: Item)

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun byId(id: String): Item?

    @Query("SELECT * FROM items WHERE id = :id")
    fun observe(id: String): Flow<Item?>

    /** The inbox: everything not yet successfully posted, newest first. */
    @Query("SELECT * FROM items WHERE status != 'POSTED' ORDER BY createdAt DESC")
    fun observeInbox(): Flow<List<Item>>

    /** Posts that went out; left only by a version that kept them. */
    @Query("SELECT * FROM items WHERE status = 'POSTED'")
    suspend fun posted(): List<Item>

    /**
     * An unsent draft holding these exact bytes, for folding a repeated share
     * into rather than duplicating it.
     *
     * Deliberately drafts only. Matching a posted item makes re-sharing
     * something reopen the post that already went out; matching a queued one
     * lets it be edited while it is on its way to the instance. Either is worse
     * than an extra draft.
     */
    @Query("SELECT * FROM items WHERE mediaHash = :hash AND status = 'DRAFT' LIMIT 1")
    suspend fun draftByMediaHash(hash: String): Item?

    /**
     * Any item whose picture or untouched original is this file. Files are
     * named by content, so an item's original can be another item's picture
     * though their hashes differ.
     */
    @Query("SELECT COUNT(*) > 0 FROM items WHERE mediaPath = :path OR originalMediaPath = :path")
    suspend fun anyUsingPath(path: String): Boolean

    /**
     * The newest draft that carries a permalink but no image yet — an Instagram
     * share waiting for the screenshot that goes with it.
     */
    @Query(
        """
        SELECT * FROM items
        WHERE status = 'DRAFT' AND mediaPath IS NULL AND sourceUrl IS NOT NULL
          AND createdAt >= :since
        ORDER BY createdAt DESC LIMIT 1
        """,
    )
    suspend fun latestAwaitingMedia(since: Long): Item?

    /** The newest draft that carries an image but no permalink yet. */
    @Query(
        """
        SELECT * FROM items
        WHERE status = 'DRAFT' AND mediaPath IS NOT NULL AND sourceUrl IS NULL
          AND createdAt >= :since
        ORDER BY createdAt DESC LIMIT 1
        """,
    )
    suspend fun latestAwaitingLink(since: Long): Item?

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun delete(id: String)

    /** Everything not posted yet, for copying to a server as drafts. */
    @Query("SELECT * FROM items WHERE status != 'POSTED' ORDER BY createdAt")
    suspend fun unposted(): List<Item>
}
