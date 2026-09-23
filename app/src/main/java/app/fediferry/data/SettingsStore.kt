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
import app.fediferry.ui.theme.ColorSource
import app.fediferry.ui.theme.ContrastLevel
import app.fediferry.ui.theme.ThemeMode
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
    /**
     * Record what the app does to a file that can be exported and sent on.
     * Off by default: it is for chasing a problem, not for everyday running.
     * Events only — never tokens, never post text.
     */
    val debugLogging: Boolean = false,
    /**
     * After a post goes out, add any hashtag it carried that is not on the
     * hashtag list yet. Off by default: the list is the user's to curate, and a
     * source's tags would otherwise pile up in it.
     */
    val rememberSentHashtags: Boolean = false,

    // --- appearance ------------------------------------------------------
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /**
     * The app's own colours by default. Wallpaper colours are opt-in: on some
     * phones they come out red, or too close together to read.
     */
    val colorSource: ColorSource = ColorSource.APP,
    val contrastLevel: ContrastLevel = ContrastLevel.SYSTEM,

    // --- the image model used by the "Erase with AI" treatment -----------
    val imageEndpoint: String = "",
    val imageModel: String = "",
    val imageApiKey: String = "",
    val imageInstruction: String = "",
    /** Name of an [app.fediferry.media.cleanup.EditWireFormat]. */
    val imageWireFormat: String = "MULTIPART",
    /** Name of an [app.fediferry.media.cleanup.MaskPolarity]. */
    val imageMaskPolarity: String = "TRANSPARENT_HOLE",
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
        val debugLogging = booleanPreferencesKey("debug_logging")
        val rememberSentHashtags = booleanPreferencesKey("remember_sent_hashtags")
        val themeMode = stringPreferencesKey("theme_mode")
        val colorSource = stringPreferencesKey("color_source")
        val contrastLevel = stringPreferencesKey("contrast_level")
        val imageEndpoint = stringPreferencesKey("image_endpoint")
        val imageModel = stringPreferencesKey("image_model")
        val imageApiKey = stringPreferencesKey("image_api_key")
        val imageInstruction = stringPreferencesKey("image_instruction")
        val imageWireFormat = stringPreferencesKey("image_wire_format")
        val imageMaskPolarity = stringPreferencesKey("image_mask_polarity")
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
            debugLogging = p[Keys.debugLogging] ?: false,
            rememberSentHashtags = p[Keys.rememberSentHashtags] ?: false,
            themeMode = enumOr(p[Keys.themeMode], ThemeMode.SYSTEM),
            colorSource = enumOr(p[Keys.colorSource], ColorSource.APP),
            contrastLevel = enumOr(p[Keys.contrastLevel], ContrastLevel.SYSTEM),
            imageEndpoint = p[Keys.imageEndpoint].orEmpty(),
            imageModel = p[Keys.imageModel].orEmpty(),
            imageApiKey = p[Keys.imageApiKey].orEmpty(),
            imageInstruction = p[Keys.imageInstruction].orEmpty(),
            imageWireFormat = p[Keys.imageWireFormat] ?: "MULTIPART",
            imageMaskPolarity = p[Keys.imageMaskPolarity] ?: "TRANSPARENT_HOLE",
        )
    }

    suspend fun current(): Settings = settings.first()

    /**
     * Forgets the single model each kind used to be set to, key included,
     * once [AiModels] has moved them into its list. The key must not stay
     * behind in plain settings after moving to encrypted storage.
     */
    suspend fun clearLegacyModels() = edit {
        listOf(
            Keys.visionEndpoint, Keys.visionModel, Keys.visionApiKey,
            Keys.imageEndpoint, Keys.imageModel, Keys.imageApiKey,
            Keys.imageWireFormat, Keys.imageMaskPolarity,
        ).forEach { key -> it.remove(key) }
    }

    suspend fun setUndoDelay(seconds: Int) = edit { it[Keys.undoDelay] = seconds.coerceIn(0, 60) }
    suspend fun setVisionPrompt(v: String) = edit { it[Keys.visionPrompt] = v }
    suspend fun setImageInstruction(v: String) = edit { it[Keys.imageInstruction] = v }

    suspend fun setLastCleanupProfile(id: String) = edit { it[Keys.lastCleanupProfile] = id }
    suspend fun setResolveLinks(enabled: Boolean) = edit { it[Keys.resolveLinks] = enabled }
    suspend fun setAutoCrop(enabled: Boolean) = edit { it[Keys.autoCrop] = enabled }
    suspend fun setDebugLogging(enabled: Boolean) = edit { it[Keys.debugLogging] = enabled }
    suspend fun setRememberSentHashtags(enabled: Boolean) = edit { it[Keys.rememberSentHashtags] = enabled }
    suspend fun setThemeMode(v: ThemeMode) = edit { it[Keys.themeMode] = v.name }
    suspend fun setColorSource(v: ColorSource) = edit { it[Keys.colorSource] = v.name }
    suspend fun setContrastLevel(v: ContrastLevel) = edit { it[Keys.contrastLevel] = v.name }
    suspend fun setPurgeAfterDays(days: Int) = edit { it[Keys.purgeAfterDays] = days.coerceAtLeast(0) }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}

/** A stored enum name back to the enum; a name that no longer exists falls back. */
private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: fallback
