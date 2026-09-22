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
import app.fediferry.data.model.Hashtags
import app.fediferry.data.model.PlaceholderKey
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
    val placeholderKeys: List<PlaceholderKey> = emptyList(),
    /** The hashtag list from Settings, in its order. */
    val hashtagList: List<String> = emptyList(),
    /** Whether hashtags new to the list join it once the post is sent. */
    val rememberSentHashtags: Boolean = false,
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
                placeholderKeys = db.placeholderKeys().all(),
                hashtagList = db.hashtags().all().map { it.tag },
                rememberSentHashtags = ServiceLocator.settings(getApplication()).current().rememberSentHashtags,
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

    /** Picks or drops one hashtag for this post. */
    fun toggleHashtag(tag: String) = edit { item ->
        val current = item.hashtagList
        val next = if (Hashtags.contains(current, tag)) {
            current.filterNot { it.equals(tag, ignoreCase = true) }
        } else {
            current + tag
        }
        item.copy(hashtags = Hashtags.format(next))
    }

    /** The hashtags this post's source offers, whether or not they are ticked. */
    fun sourceHashtags(): List<String> {
        val s = _state.value
        val item = s.item ?: return emptyList()
        val template = s.templates.firstOrNull { it.id == item.templateId } ?: return emptyList()
        return TemplateEngine.sourceHashtags(template, s.placeholderKeys, TemplateEngine.inputsOf(item))
    }

    /**
     * Adds or takes away the source's own hashtags. Taking them away keeps
     * any the template picked too, and everything else the user ticked.
     */
    fun setAddSourceHashtags(on: Boolean) {
        val fromSource = sourceHashtags()
        val template = _state.value.templates.firstOrNull { it.id == _state.value.item?.templateId }
        edit { item ->
            val current = item.hashtagList
            val next = if (on) {
                Hashtags.union(current, fromSource)
            } else {
                current.filterNot { tag ->
                    Hashtags.contains(fromSource, tag) && !Hashtags.contains(template?.hashtagList.orEmpty(), tag)
                }
            }
            item.copy(addSourceHashtags = on, hashtags = Hashtags.format(next))
        }
    }

    /**
     * Adds a hashtag typed for this post and picks it. It joins the list in
     * Settings only once the post is sent, and only when Settings asks for that.
     */
    fun addHashtag(raw: String): Boolean {
        val tag = Hashtags.normalize(raw) ?: return false
        edit { item -> item.copy(hashtags = Hashtags.format(Hashtags.union(item.hashtagList, listOf(tag)))) }
        return true
    }

    /**
     * Re-renders the body and resets the hashtags from another template,
     * discarding manual edits. The item keeps the data its source sent, so the
     * new template's placeholders — and the source's hashtags — fill from it too.
     */
    fun applyTemplate(template: Template) = edit { item ->
        val keys = _state.value.placeholderKeys
        val inputs = TemplateEngine.inputsOf(item)
        item.copy(
            templateId = template.id,
            bodyText = TemplateEngine.render(template, keys, inputs),
            hashtags = Hashtags.format(TemplateEngine.hashtagsFor(template, keys, inputs)),
            addSourceHashtags = template.addSourceHashtags,
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

    /** Puts the untouched screenshot back, undoing trimming and cleanup alike. */
    fun revertEdits() = viewModelScope.launch {
        val item = _state.value.item ?: return@launch
        val restored = repo.revertEdits(item)
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
