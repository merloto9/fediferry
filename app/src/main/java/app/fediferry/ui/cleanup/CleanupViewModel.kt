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
package app.fediferry.ui.cleanup

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fediferry.data.model.CleanupProfile
import app.fediferry.data.model.Item
import app.fediferry.data.model.ProfileRule
import app.fediferry.di.ServiceLocator
import app.fediferry.media.cleanup.CleanupRule
import app.fediferry.media.cleanup.Region
import app.fediferry.media.cleanup.TreatmentKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** A rule being edited on screen, before it is committed to the image. */
data class DraftRule(
    val id: String = UUID.randomUUID().toString(),
    val region: Region,
    val treatment: TreatmentKind,
) {
    fun toCleanupRule() = CleanupRule(region, treatment)
}

data class CleanupState(
    val item: Item? = null,
    val sourcePath: String? = null,
    val rules: List<DraftRule> = emptyList(),
    val treatment: TreatmentKind = TreatmentKind.FILL,
    val profiles: List<CleanupProfile> = emptyList(),
    val profileId: String? = null,
    val rememberForProfile: Boolean = false,
    val preview: Bitmap? = null,
    val previewing: Boolean = false,
    val applying: Boolean = false,
    val loaded: Boolean = false,
    val message: String? = null,
) {
    val profileName: String?
        get() = profiles.firstOrNull { it.id == profileId }?.name
}

class CleanupViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.items(app)
    private val cleanupDao = ServiceLocator.database(app).cleanup()
    private val settings = ServiceLocator.settings(app)

    private val _state = MutableStateFlow(CleanupState())
    val state: StateFlow<CleanupState> = _state.asStateFlow()

    private var previewJob: Job? = null

    fun load(itemId: String) = viewModelScope.launch {
        val item = repo.byId(itemId)
        val profiles = cleanupDao.profiles()
        // The profile used last is almost always the one wanted again: most
        // people screenshot the same one or two apps.
        val remembered = settings.current().lastCleanupProfileId
        val profileId = profiles.firstOrNull { it.id == remembered }?.id
            ?: cleanupDao.defaultProfile()?.id
            ?: profiles.firstOrNull()?.id

        val saved = profileId?.let { cleanupDao.enabledRules(it) }.orEmpty()

        _state.update {
            it.copy(
                item = item,
                sourcePath = item?.mediaPath,
                profiles = profiles,
                profileId = profileId,
                rules = saved.map { rule ->
                    DraftRule(
                        region = Region(rule.left, rule.top, rule.right, rule.bottom),
                        treatment = rule.treatment,
                    )
                },
                loaded = true,
            )
        }
        refreshPreview()
    }

    fun setTreatment(treatment: TreatmentKind) = _state.update { it.copy(treatment = treatment) }

    fun addRule(region: Region) {
        if (!region.isSane()) return
        _state.update { it.copy(rules = it.rules + DraftRule(region = region, treatment = it.treatment)) }
        refreshPreview()
    }

    fun removeRule(id: String) {
        _state.update { it.copy(rules = it.rules.filterNot { rule -> rule.id == id }) }
        refreshPreview()
    }

    fun clearRules() {
        _state.update { it.copy(rules = emptyList()) }
        refreshPreview()
    }

    fun selectProfile(id: String) = viewModelScope.launch {
        val saved = cleanupDao.enabledRules(id)
        _state.update { s ->
            s.copy(
                profileId = id,
                rules = saved.map {
                    DraftRule(region = Region(it.left, it.top, it.right, it.bottom), treatment = it.treatment)
                },
            )
        }
        refreshPreview()
    }

    fun createProfile(name: String) = viewModelScope.launch {
        if (name.isBlank()) return@launch
        val profile = CleanupProfile(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            isDefault = cleanupDao.profiles().isEmpty(),
        )
        cleanupDao.upsertProfile(profile)
        _state.update { it.copy(profiles = cleanupDao.profiles(), profileId = profile.id) }
    }

    fun setRememberForProfile(remember: Boolean) =
        _state.update { it.copy(rememberForProfile = remember) }

    private fun refreshPreview() {
        val s = _state.value
        val path = s.sourcePath ?: return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            _state.update { it.copy(previewing = true) }
            val bitmap = app.fediferry.media.cleanup.BitmapCleaner.preview(
                path,
                s.rules.map { it.toCleanupRule() },
            )
            _state.update { it.copy(preview = bitmap, previewing = false) }
        }
    }

    /**
     * Writes the rules into the image, and — when asked — into the profile, so
     * the next screenshot from the same place is cleaned without any drawing.
     */
    fun apply(onDone: (String) -> Unit) = viewModelScope.launch {
        val s = _state.value
        val item = s.item ?: return@launch

        if (s.rememberForProfile) saveToProfile(s)
        s.profileId?.let { settings.setLastCleanupProfile(it) }

        if (s.rules.isEmpty()) {
            onDone(item.id)
            return@launch
        }

        _state.update { it.copy(applying = true) }
        val app = getApplication<Application>()
        repo.applyCleanup(
            item = item,
            rules = s.rules.map { it.toCleanupRule() },
            provider = ServiceLocator.imageEditProvider(app),
            polarity = ServiceLocator.maskPolarity(app),
            instruction = settings.current().imageInstruction,
        ).fold(
            onSuccess = { onDone(it.id) },
            onFailure = { e ->
                _state.update { it.copy(applying = false, message = "Could not clean up: ${e.message}") }
            },
        )
    }

    private suspend fun saveToProfile(s: CleanupState) {
        val profileId = s.profileId ?: return
        cleanupDao.deleteRulesOf(profileId)
        cleanupDao.upsertRules(
            s.rules.mapIndexed { index, rule ->
                ProfileRule(
                    id = UUID.randomUUID().toString(),
                    profileId = profileId,
                    left = rule.region.left,
                    top = rule.region.top,
                    right = rule.region.right,
                    bottom = rule.region.bottom,
                    treatment = rule.treatment,
                    sortOrder = index,
                )
            },
        )
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
