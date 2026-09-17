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
package app.fediferry.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

data class Settings(
    val undoDelaySeconds: Int = 5,
    val visionEndpoint: String = "",
    val visionModel: String = "",
    val visionApiKey: String = "",
    val visionPrompt: String = DEFAULT_VISION_PROMPT,
    val purgePostedAfterDays: Int = 0, // 0 = keep forever
    /**
     * Trim a screenshot to the detected picture without asking, in the modes
     * that do not stop for input. The untouched screenshot is kept either way,
     * so this is always reversible from the editor.
     */
    val autoCrop: Boolean = true,
    /**
     * Fetch the image behind a shared link, for services that publish one.
     * Instagram does not; 9GAG does. Off means every share falls back to the
     * screenshot flow.
     */
    val resolveLinks: Boolean = true,
    /** The cleanup profile used last; the one almost always wanted again. */
    val lastCleanupProfileId: String = "",
) {
    companion object {
        const val DEFAULT_VISION_PROMPT =
            "Describe this image for a blind reader in one or two plain sentences. " +
                "Transcribe any text in the image verbatim. No preamble."
    }
}

class SettingsStore(private val context: Context) {

    private object Keys {
        val undoDelay = intPreferencesKey("undo_delay_seconds")
        val visionEndpoint = stringPreferencesKey("vision_endpoint")
        val visionModel = stringPreferencesKey("vision_model")
        val visionApiKey = stringPreferencesKey("vision_api_key")
        val visionPrompt = stringPreferencesKey("vision_prompt")
        val purgeAfterDays = intPreferencesKey("purge_posted_after_days")
        val autoCrop = booleanPreferencesKey("auto_crop")
        val resolveLinks = booleanPreferencesKey("resolve_links")
        val lastCleanupProfile = stringPreferencesKey("last_cleanup_profile")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            undoDelaySeconds = p[Keys.undoDelay] ?: 5,
            visionEndpoint = p[Keys.visionEndpoint].orEmpty(),
            visionModel = p[Keys.visionModel].orEmpty(),
            visionApiKey = p[Keys.visionApiKey].orEmpty(),
            visionPrompt = p[Keys.visionPrompt] ?: Settings.DEFAULT_VISION_PROMPT,
            purgePostedAfterDays = p[Keys.purgeAfterDays] ?: 0,
            autoCrop = p[Keys.autoCrop] ?: true,
            resolveLinks = p[Keys.resolveLinks] ?: true,
            lastCleanupProfileId = p[Keys.lastCleanupProfile].orEmpty(),
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setUndoDelay(seconds: Int) = edit { it[Keys.undoDelay] = seconds.coerceIn(0, 60) }
    suspend fun setVisionEndpoint(v: String) = edit { it[Keys.visionEndpoint] = v }
    suspend fun setVisionModel(v: String) = edit { it[Keys.visionModel] = v }
    suspend fun setVisionApiKey(v: String) = edit { it[Keys.visionApiKey] = v }
    suspend fun setVisionPrompt(v: String) = edit { it[Keys.visionPrompt] = v }
    suspend fun setLastCleanupProfile(id: String) = edit { it[Keys.lastCleanupProfile] = id }
    suspend fun setResolveLinks(enabled: Boolean) = edit { it[Keys.resolveLinks] = enabled }
    suspend fun setAutoCrop(enabled: Boolean) = edit { it[Keys.autoCrop] = enabled }
    suspend fun setPurgeAfterDays(days: Int) = edit { it[Keys.purgeAfterDays] = days.coerceAtLeast(0) }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
