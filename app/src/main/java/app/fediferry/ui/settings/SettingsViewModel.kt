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
package app.fediferry.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fediferry.data.Settings
import app.fediferry.data.model.Account
import app.fediferry.data.model.CleanupProfile
import app.fediferry.data.model.ProfileRule
import app.fediferry.data.model.AltTextMode
import app.fediferry.data.model.Template
import app.fediferry.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class SettingsState(
    val accounts: List<Account> = emptyList(),
    val templates: List<Template> = emptyList(),
    val cleanupProfiles: List<CleanupProfile> = emptyList(),
    val cleanupRules: List<ProfileRule> = emptyList(),
    val settings: Settings = Settings(),
    val connecting: Boolean = false,
    val message: String? = null,
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val db = ServiceLocator.database(app)
    private val settingsStore = ServiceLocator.settings(app)
    private val auth = ServiceLocator.auth(app)
    private val cleanupDao = ServiceLocator.database(app).cleanup()

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                cleanupDao.observeProfiles(),
                cleanupDao.observeAllRules(),
            ) { profiles, rules -> profiles to rules }
                .collect { (profiles, rules) ->
                    _state.update { it.copy(cleanupProfiles = profiles, cleanupRules = rules) }
                }
        }
        viewModelScope.launch {
            combine(
                db.accounts().observeAll(),
                db.templates().observeAll(),
                settingsStore.settings,
            ) { accounts, templates, settings ->
                Triple(accounts, templates, settings)
            }.collect { (accounts, templates, settings) ->
                _state.update {
                    it.copy(accounts = accounts, templates = templates, settings = settings)
                }
            }
        }
    }

    fun connect(instance: String) = viewModelScope.launch {
        if (instance.isBlank()) {
            _state.update { it.copy(message = "Enter an instance host, e.g. mastodon.social") }
            return@launch
        }
        _state.update { it.copy(connecting = true, message = null) }
        runCatching { auth.beginAuthorization(instance) }
            .onFailure { e ->
                _state.update { it.copy(message = "Could not reach $instance: ${e.message}") }
            }
        _state.update { it.copy(connecting = false) }
    }

    fun disconnect(accountId: String) = viewModelScope.launch { auth.disconnect(accountId) }

    fun makeDefaultAccount(accountId: String) =
        viewModelScope.launch { db.accounts().setDefault(accountId) }

    fun saveTemplate(template: Template) =
        viewModelScope.launch { db.templates().upsert(template) }

    fun newTemplate() = viewModelScope.launch {
        db.templates().upsert(
            Template(
                id = UUID.randomUUID().toString(),
                name = "New template",
                body = "{tags}\n\nvia {link}",
                altTextMode = AltTextMode.NONE,
                sortOrder = _state.value.templates.size,
            ),
        )
    }

    fun deleteTemplate(id: String) = viewModelScope.launch { db.templates().delete(id) }

    fun makeDefaultTemplate(id: String) =
        viewModelScope.launch { db.templates().setDefault(id) }

    fun setUndoDelay(seconds: Int) = persist { settingsStore.setUndoDelay(seconds) }
    fun setVisionEndpoint(v: String) = persist { settingsStore.setVisionEndpoint(v) }
    fun setVisionModel(v: String) = persist { settingsStore.setVisionModel(v) }
    fun setVisionApiKey(v: String) = persist { settingsStore.setVisionApiKey(v) }
    fun setVisionPrompt(v: String) = persist { settingsStore.setVisionPrompt(v) }
    fun setResolveLinks(enabled: Boolean) =
        persist { settingsStore.setResolveLinks(enabled) }
    fun setAutoCrop(enabled: Boolean) = persist { settingsStore.setAutoCrop(enabled) }
    fun setPurgeAfterDays(days: Int) = persist { settingsStore.setPurgeAfterDays(days) }

    fun setImageEndpoint(v: String) = persist { settingsStore.setImageEndpoint(v) }
    fun setImageModel(v: String) = persist { settingsStore.setImageModel(v) }
    fun setImageApiKey(v: String) = persist { settingsStore.setImageApiKey(v) }
    fun setImageInstruction(v: String) =
        persist { settingsStore.setImageInstruction(v) }
    fun setImageWireFormat(v: String) =
        persist { settingsStore.setImageWireFormat(v) }
    fun setImageMaskPolarity(v: String) =
        persist { settingsStore.setImageMaskPolarity(v) }

    fun setDefaultCleanupProfile(id: String) =
        persist { cleanupDao.setDefaultProfile(id) }

    fun clearDefaultCleanupProfile() =
        persist { cleanupDao.setDefaultProfile("") }

    fun deleteCleanupProfile(id: String) =
        persist { cleanupDao.deleteProfile(id) }

    fun setCleanupRuleEnabled(id: String, enabled: Boolean) =
        persist { cleanupDao.setRuleEnabled(id, enabled) }

    fun deleteCleanupRule(id: String) =
        persist { cleanupDao.deleteRule(id) }

    /**
     * Runs a save on the application scope rather than this ViewModel's.
     *
     * Leaving the settings screen cancels viewModelScope, and a DataStore write
     * in flight goes with it — so the value typed a moment earlier is simply
     * lost. Persisting must outlive the screen that asked for it.
     */
    private fun persist(block: suspend () -> Unit) {
        ServiceLocator.appScope.launch { block() }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
