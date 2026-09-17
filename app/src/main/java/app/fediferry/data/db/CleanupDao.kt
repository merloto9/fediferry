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
import app.fediferry.data.model.CleanupProfile
import app.fediferry.data.model.ProfileRule
import kotlinx.coroutines.flow.Flow

@Dao
interface CleanupDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: CleanupProfile)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: ProfileRule)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRules(rules: List<ProfileRule>)

    @Query("SELECT * FROM cleanup_profiles ORDER BY name")
    fun observeProfiles(): Flow<List<CleanupProfile>>

    @Query("SELECT * FROM cleanup_profiles ORDER BY name")
    suspend fun profiles(): List<CleanupProfile>

    @Query("SELECT * FROM cleanup_profiles WHERE id = :id")
    suspend fun profile(id: String): CleanupProfile?

    @Query("SELECT * FROM cleanup_profiles WHERE isDefault = 1 LIMIT 1")
    suspend fun defaultProfile(): CleanupProfile?

    @Query("UPDATE cleanup_profiles SET isDefault = (id = :id)")
    suspend fun setDefaultProfile(id: String)

    @Query("SELECT * FROM cleanup_rules WHERE profileId = :profileId ORDER BY sortOrder, id")
    fun observeRules(profileId: String): Flow<List<ProfileRule>>

    @Query("SELECT * FROM cleanup_rules WHERE profileId = :profileId AND enabled = 1 ORDER BY sortOrder, id")
    suspend fun enabledRules(profileId: String): List<ProfileRule>

    @Query("SELECT * FROM cleanup_rules ORDER BY profileId, sortOrder")
    fun observeAllRules(): Flow<List<ProfileRule>>

    @Query("DELETE FROM cleanup_rules WHERE id = :id")
    suspend fun deleteRule(id: String)

    @Query("UPDATE cleanup_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setRuleEnabled(id: String, enabled: Boolean)

    @Transaction
    suspend fun deleteProfile(id: String) {
        deleteRulesOf(id)
        deleteProfileRow(id)
    }

    @Query("DELETE FROM cleanup_rules WHERE profileId = :profileId")
    suspend fun deleteRulesOf(profileId: String)

    @Query("DELETE FROM cleanup_profiles WHERE id = :id")
    suspend fun deleteProfileRow(id: String)
}
