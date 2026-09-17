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
package app.fediferry.ui.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fediferry.data.model.Account
import app.fediferry.data.model.Item
import app.fediferry.data.model.Template
import app.fediferry.data.model.Visibility
import app.fediferry.di.ServiceLocator
import app.fediferry.template.TemplateEngine
import app.fediferry.work.PostScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditorState(
    val item: Item? = null,
    val templates: List<Template> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val altTextBusy: Boolean = false,
    val message: String? = null,
    val loaded: Boolean = false,
)

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.items(app)
    private val db = ServiceLocator.database(app)

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    fun load(itemId: String) = viewModelScope.launch {
        _state.update {
            it.copy(
                item = repo.byId(itemId),
                templates = repo.templates(),
                accounts = db.accounts().all(),
                loaded = true,
            )
        }
    }

    private fun edit(block: (Item) -> Item) = _state.update { s ->
        s.copy(item = s.item?.let(block))
    }

    fun setBody(text: String) = edit { it.copy(bodyText = text) }
    fun setAltText(text: String) = edit { it.copy(altText = text, altTextFailed = false) }
    fun setContentWarning(text: String) =
        edit { it.copy(contentWarning = text.ifBlank { null }) }
    fun setVisibility(visibility: Visibility) = edit { it.copy(visibility = visibility) }
    fun setAccount(accountId: String) = edit { it.copy(accountId = accountId) }

    /** Re-renders the body from another template, discarding manual edits. */
    fun applyTemplate(template: Template) = edit { item ->
        item.copy(
            templateId = template.id,
            bodyText = TemplateEngine.render(
                template,
                TemplateEngine.Inputs(link = item.sourceUrl),
            ),
            visibility = template.visibility,
            contentWarning = template.contentWarning,
            accountId = template.accountId ?: item.accountId,
        )
    }

    /**
     * Asks the template's provider for a description. A failure surfaces as a
     * message here rather than blocking anything — the user can still send.
     */
    fun regenerateAltText() = viewModelScope.launch {
        val item = _state.value.item ?: return@launch
        _state.update { it.copy(altTextBusy = true, message = null) }

        val bytes = repo.mediaBytes(item)
        if (bytes == null) {
            _state.update { it.copy(altTextBusy = false, message = "No image to describe") }
            return@launch
        }

        val template = repo.resolveTemplate(item.templateId)
        val provider = ServiceLocator.altTextProvider(getApplication(), template)
        val result = provider.describe(bytes, item.mimeType ?: "image/jpeg")

        _state.update { s ->
            s.copy(
                altTextBusy = false,
                item = result.getOrNull()?.let { s.item?.copy(altText = it, altTextFailed = false) }
                    ?: s.item,
                message = result.exceptionOrNull()?.let { "Alt text failed: ${it.message}" },
            )
        }
    }

    /** Puts the untouched screenshot back after a crop. */
    fun revertCrop() = viewModelScope.launch {
        val item = _state.value.item ?: return@launch
        val restored = repo.revertCrop(item)
        _state.update { it.copy(item = restored, message = "Original screenshot restored") }
    }

    fun saveDraft(onDone: () -> Unit) = viewModelScope.launch {
        _state.value.item?.let { repo.update(it) }
        onDone()
    }

    fun send(onDone: () -> Unit) = viewModelScope.launch {
        val item = _state.value.item ?: return@launch
        if (item.accountId == null && db.accounts().defaultAccount() == null) {
            _state.update { it.copy(message = "Connect a Mastodon account first") }
            return@launch
        }
        repo.update(item)
        repo.markQueued(item.id)
        // Sent from the editor: the user already had their chance to change their
        // mind, so no undo window here.
        PostScheduler.enqueue(getApplication(), item.id)
        onDone()
    }

    fun discard(onDone: () -> Unit) = viewModelScope.launch {
        _state.value.item?.let { repo.delete(it.id) }
        onDone()
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
