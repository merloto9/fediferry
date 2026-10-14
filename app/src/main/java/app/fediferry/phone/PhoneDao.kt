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
package app.fediferry.phone

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** The phone's project in the database; [PhoneBackend] is the only caller. */
@Dao
interface PhoneDao {

    // --- channels -----------------------------------------------------------

    @Query("SELECT * FROM phone_channels ORDER BY sortOrder, name")
    suspend fun channels(): List<PhoneChannel>

    @Query("SELECT * FROM phone_channels WHERE id = :id")
    suspend fun channel(id: String): PhoneChannel?

    @Upsert
    suspend fun upsertChannel(channel: PhoneChannel)

    @Query("UPDATE phone_channels SET isDefault = (id = :id)")
    suspend fun setDefaultChannel(id: String)

    @Query("DELETE FROM phone_channels WHERE id = :id")
    suspend fun deleteChannel(id: String)

    // --- posts ----------------------------------------------------------------

    @Query("SELECT * FROM phone_posts WHERE stage IN (:stages) ORDER BY updatedAt DESC")
    suspend fun posts(stages: Collection<String>): List<PhonePost>

    @Query("SELECT * FROM phone_posts")
    suspend fun allPosts(): List<PhonePost>

    @Query("SELECT * FROM phone_posts WHERE id = :id")
    suspend fun post(id: String): PhonePost?

    @Upsert
    suspend fun upsertPost(post: PhonePost)

    @Query("DELETE FROM phone_posts WHERE id = :id")
    suspend fun deletePost(id: String)

    @Query("UPDATE phone_posts SET reviewFolderId = NULL WHERE reviewFolderId = :folderId")
    suspend fun releaseReviewFolder(folderId: String)

    @Query("UPDATE phone_posts SET channelId = NULL WHERE channelId = :channelId AND stage IN ('DRAFT', 'READY')")
    suspend fun releaseChannel(channelId: String)

    // --- pictures ---------------------------------------------------------------

    @Query("SELECT * FROM phone_assets WHERE id = :id")
    suspend fun asset(id: String): PhoneAsset?

    @Query("SELECT * FROM phone_assets WHERE sha256 = :sha LIMIT 1")
    suspend fun assetBySha(sha: String): PhoneAsset?

    @Query("SELECT * FROM phone_assets")
    suspend fun assets(): List<PhoneAsset>

    @Upsert
    suspend fun upsertAsset(asset: PhoneAsset)

    @Query("DELETE FROM phone_assets WHERE id = :id")
    suspend fun deleteAsset(id: String)

    // --- review folders -------------------------------------------------------------

    @Query("SELECT * FROM phone_review_folders ORDER BY sortOrder, name")
    suspend fun reviewFolders(): List<PhoneReviewFolder>

    @Upsert
    suspend fun upsertReviewFolder(folder: PhoneReviewFolder)

    @Query("DELETE FROM phone_review_folders WHERE id = :id")
    suspend fun deleteReviewFolder(id: String)

    // --- schedules --------------------------------------------------------------------

    @Query("SELECT * FROM phone_schedules ORDER BY name")
    suspend fun schedules(): List<PhoneSchedule>

    @Upsert
    suspend fun upsertSchedule(schedule: PhoneSchedule)

    @Query("DELETE FROM phone_schedules WHERE id = :id")
    suspend fun deleteSchedule(id: String)

    @Query("DELETE FROM phone_schedules WHERE channelId = :channelId")
    suspend fun deleteSchedulesOf(channelId: String)
}
