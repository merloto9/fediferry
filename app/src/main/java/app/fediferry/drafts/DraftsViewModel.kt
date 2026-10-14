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
import app.fediferry.backend.PostBackend
import app.fediferry.backend.ScreenCache
import app.fediferry.client.ServerException
import app.fediferry.di.ServiceLocator
import app.fediferry.upload.PendingShare
import app.fediferry.upload.UploadWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DraftsState(
    val drafts: List<PostDto> = emptyList(),
    val channels: List<ChannelDto> = emptyList(),
    val loadedOnce: Boolean = false,
    /** Why the server could not be read, as an error code; null when it could. */
    val error: String? = null,
)

/**
 * The project's drafts on the server, newest change first, and the shares
 * still on this phone on their way to becoming drafts. It follows the change
 * feed, so a draft another phone starts or edits shows up here within
 * moments — with who is editing it.
 */
class DraftsViewModel(app: Application) : AndroidViewModel(app) {

    private val backend: PostBackend? get() = ServiceLocator.backend(getApplication())

    /** This phone's id on the server, to tell its own locks from others'. */
    val deviceId: String get() = backend?.deviceId.orEmpty()

    /** Shares not on the server yet; each becomes a draft once it is up. */
    val pending: StateFlow<List<PendingShare>> = ServiceLocator.pendingShares(app).all

    // What this tab showed last, at once; it is read again in the background.
    private val _state = MutableStateFlow(ScreenCache.get<DraftsState>(ServiceLocator.backend(app), SCREEN)?.copy(error = null) ?: DraftsState())
    val state: StateFlow<DraftsState> = _state

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private var follow: Job? = null

    init {
        viewModelScope.launch {
            _state.collect { if (it.loadedOnce) ScreenCache.put(ServiceLocator.backend(getApplication()), SCREEN, it) }
        }
    }

    fun start() {
        refresh()
        if (follow?.isActive == true) return
        val source = backend ?: return
        follow = viewModelScope.launch {
            // Back in reach after a gap: read again rather than wait for the next change.
            launch {
                var wasOk = source.reachable.value.ok
                source.reachable.collect { r -> if (r.ok && !wasOk) refresh(); wasOk = r.ok }
            }
            source.changes().collect { types -> if ("post" in types || "channel" in types) refresh() }
        }
    }

    fun stop() {
        follow?.cancel()
        follow = null
    }

    fun refresh() = viewModelScope.launch {
        val api = backend ?: return@launch
        runCatching { api.posts(PostStages.DRAFT) to api.channels() }
            .onSuccess { (drafts, channels) ->
                _state.update { it.copy(drafts = drafts.sortedByDescending { d -> d.updatedAt }, channels = channels, loadedOnce = true, error = null) }
            }
            .onFailure { e -> _state.update { it.copy(loadedOnce = true, error = (e as? ServerException)?.code ?: "client.unknown") } }
    }

    fun delete(ids: Collection<String>) = viewModelScope.launch {
        val api = backend ?: return@launch
        ids.forEach { id ->
            runCatching { api.deletePost(id) }.onFailure { e -> _messages.tryEmit((e as? ServerException)?.code ?: "client.unknown") }
        }
        refresh()
    }

    /** Tries waiting shares again now, e.g. after the network came back. */
    fun retryUploads() = UploadWorker.enqueue(getApplication())

    fun discardPending(share: PendingShare) = ServiceLocator.pendingShares(getApplication()).remove(share)

    fun thumbnailUrl(assetId: String): String? = backend?.thumbnailUrl(assetId)

    override fun onCleared() = stop()

    private companion object {
        const val SCREEN = "drafts"
    }
}
