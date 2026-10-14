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
package app.fediferry.channels

import android.app.Application
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fediferry.api.ChannelDto
import app.fediferry.api.ChannelPatch
import app.fediferry.api.PostSettings
import app.fediferry.backend.PostBackend
import app.fediferry.client.ServerException
import app.fediferry.data.model.AiKind
import app.fediferry.data.model.PlaceholderKey
import app.fediferry.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChannelSettingsState(
    /** The channel as stored. */
    val channel: ChannelDto? = null,
    val loaded: Boolean = false,
    /** Name and post settings as edited, not saved yet. */
    val name: String = "",
    val post: PostSettings = PostSettings(),
    val busy: Boolean = false,
    /** The project's placeholders, hashtags and alt-text models, for the post settings. */
    val placeholderNames: Set<String> = PlaceholderKey.RESERVED,
    val hashtags: List<String> = emptyList(),
    val visionConfigured: Boolean = false,
    /** An error code with its details, for the screen to word. */
    val message: Pair<String, Map<String, String>>? = null,
    /** Set once the channel is removed, so the screen closes. */
    val removed: Boolean = false,
) {
    val unsaved: Boolean get() = channel != null && (name.trim() != channel.name || post != channel.post)
}

/**
 * One channel's page: its name, the account it posts as, what its instance
 * takes, and how its posts start.
 */
class ChannelSettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val backend: PostBackend? get() = ServiceLocator.backend(getApplication())
    private val db = ServiceLocator.database(app)
    private var channelId: String? = null

    private val _state = MutableStateFlow(ChannelSettingsState())
    val state: StateFlow<ChannelSettingsState> = _state

    fun load(id: String) {
        if (channelId == id) return
        channelId = id
        viewModelScope.launch {
            _state.update {
                it.copy(
                    placeholderNames = PlaceholderKey.RESERVED + db.placeholderKeys().all().map { k -> k.name },
                    hashtags = db.hashtags().all().map { h -> h.tag },
                    visionConfigured = ServiceLocator.aiModels(getApplication()).ofKind(AiKind.ALT_TEXT).isNotEmpty(),
                )
            }
            refresh()
        }
    }

    /** Reads the channel again — after a sign-in in the browser, say. Unsaved edits stay. */
    fun refresh() = viewModelScope.launch {
        val api = backend ?: return@launch
        val id = channelId ?: return@launch
        runCatching { api.channels().firstOrNull { it.id == id } }
            .onSuccess { channel ->
                _state.update { s ->
                    if (channel == null) return@update s.copy(loaded = true, removed = s.channel != null)
                    val fresh = s.channel == null || !s.unsaved
                    s.copy(channel = channel, loaded = true, name = if (fresh) channel.name else s.name, post = if (fresh) channel.post else s.post)
                }
            }
            .onFailure { fail(it) }
    }

    fun setName(name: String) = _state.update { it.copy(name = name) }

    fun setPost(post: PostSettings) = _state.update { it.copy(post = post) }

    fun save() = change { api, id -> api.patchChannel(id, ChannelPatch(name = _state.value.name.trim(), post = _state.value.post)) }

    fun makeDefault() = change { api, id -> api.patchChannel(id, ChannelPatch(isDefault = true)) }

    fun refreshLimits() = change { api, id -> api.refreshLimits(id) }

    /** Opens the instance's sign-in page; the account lands on this channel when the browser comes back. */
    fun connect(instance: String) = viewModelScope.launch {
        val app = getApplication<Application>()
        val api = backend ?: return@launch
        val id = channelId ?: return@launch
        _state.update { it.copy(busy = true) }
        runCatching { api.connectAccount(instance.trim(), id) }
            .onSuccess { auth ->
                PendingChannelSignIn.remember(app, auth.state)
                CustomTabsIntent.Builder().build().apply { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    .launchUrl(app, auth.url.toUri())
            }
            .onFailure { fail(it) }
        _state.update { it.copy(busy = false) }
    }

    fun remove() = viewModelScope.launch {
        val api = backend ?: return@launch
        val id = channelId ?: return@launch
        runCatching { api.deleteChannel(id) }
            .onSuccess { _state.update { it.copy(removed = true) } }
            .onFailure { fail(it) }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Saves a change and shows the channel as it is now; edits not part of it stay. */
    private fun change(block: suspend (PostBackend, String) -> ChannelDto) = viewModelScope.launch {
        val api = backend ?: return@launch
        val id = channelId ?: return@launch
        _state.update { it.copy(busy = true) }
        runCatching { block(api, id) }
            .onSuccess { channel ->
                _state.update { s ->
                    val saved = s.name.trim() == channel.name && s.post == channel.post
                    s.copy(channel = channel, busy = false, name = if (saved) channel.name else s.name)
                }
            }
            .onFailure { _state.update { s -> s.copy(busy = false) }; fail(it) }
    }

    private fun fail(e: Throwable) {
        val error = e as? ServerException
        _state.update { it.copy(message = (error?.code ?: e.javaClass.simpleName) to error?.args.orEmpty()) }
    }
}
