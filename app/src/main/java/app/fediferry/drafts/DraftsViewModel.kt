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
package app.fediferry.drafts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fediferry.api.ChannelDto
import app.fediferry.api.PostDto
import app.fediferry.api.PostStages
import app.fediferry.client.ServerClient
import app.fediferry.client.ServerException
import app.fediferry.di.ServiceLocator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DraftsState(
    val drafts: List<PostDto> = emptyList(),
    val channels: List<ChannelDto> = emptyList(),
    val loadedOnce: Boolean = false,
    /** Why the server could not be read, as an error code; null when it could. */
    val error: String? = null,
)

/**
 * The project's drafts on the server, newest change first. Like the library,
 * it follows the change feed, so a draft another phone starts or edits shows
 * up here within moments — with who is editing it.
 */
class DraftsViewModel(app: Application) : AndroidViewModel(app) {

    private val connections = ServiceLocator.serverConnections(app)
    private val client: ServerClient? get() = connections.current()?.let(connections::client)

    /** This phone's id on the server, to tell its own locks from others'. */
    val deviceId: String = connections.deviceId

    private val _state = MutableStateFlow(DraftsState())
    val state: StateFlow<DraftsState> = _state

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private var follow: Job? = null

    fun start() {
        refresh()
        if (follow?.isActive == true) return
        follow = viewModelScope.launch {
            var rev = runCatching { client?.project()?.rev }.getOrNull() ?: 0L
            while (isActive) {
                val api = client ?: break
                val changes = runCatching { api.changes(rev) }.getOrElse {
                    delay(RETRY_MS)
                    null
                } ?: continue
                if (changes.changed.any { it.type == "post" || it.type == "channel" }) refresh()
                rev = changes.rev
            }
        }
    }

    fun stop() {
        follow?.cancel()
        follow = null
    }

    fun refresh() = viewModelScope.launch {
        val api = client ?: return@launch
        runCatching { api.posts(PostStages.DRAFT) to api.channels() }
            .onSuccess { (drafts, channels) ->
                _state.update { it.copy(drafts = drafts.sortedByDescending { d -> d.updatedAt }, channels = channels, loadedOnce = true, error = null) }
            }
            .onFailure { e -> _state.update { it.copy(loadedOnce = true, error = (e as? ServerException)?.code ?: "client.unknown") } }
    }

    fun delete(ids: Collection<String>) = viewModelScope.launch {
        val api = client ?: return@launch
        ids.forEach { id ->
            runCatching { api.deletePost(id) }.onFailure { e -> _messages.tryEmit((e as? ServerException)?.code ?: "client.unknown") }
        }
        refresh()
    }

    fun thumbnailUrl(assetId: String): String? = client?.thumbnailUrl(assetId)

    override fun onCleared() = stop()

    private companion object {
        const val RETRY_MS = 5_000L
    }
}
