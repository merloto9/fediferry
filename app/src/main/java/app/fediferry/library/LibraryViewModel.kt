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
package app.fediferry.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fediferry.api.FolderDto
import app.fediferry.api.FolderInput
import app.fediferry.api.LibraryItemDto
import app.fediferry.api.LibraryItemPatch
import app.fediferry.api.TagDto
import app.fediferry.client.ServerClient
import app.fediferry.client.ServerException
import app.fediferry.connect.ServerConnection
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

/** Which part of the library is shown. */
sealed interface LibraryFilter {
    data object All : LibraryFilter
    data object Unsorted : LibraryFilter
    data class Folder(val id: String) : LibraryFilter
    data class Tag(val name: String) : LibraryFilter
}

data class LibraryState(
    val connection: ServerConnection? = null,
    val items: List<LibraryItemDto> = emptyList(),
    val folders: List<FolderDto> = emptyList(),
    val tags: List<TagDto> = emptyList(),
    val filter: LibraryFilter = LibraryFilter.All,
    val query: String = "",
    val nextBefore: Long? = null,
    val loading: Boolean = true,
    val loadedOnce: Boolean = false,
    /** Why the server could not be read, as an error code; null when it could. */
    val error: String? = null,
)

/**
 * The project's library on the server. It reads the library when the screen
 * opens, then follows the server's change feed so what another phone adds or
 * moves shows up here within moments.
 */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val connections = ServiceLocator.serverConnections(app)
    val pending: StateFlow<List<PendingShare>> = ServiceLocator.pendingShares(app).all

    private val _state = MutableStateFlow(LibraryState(connection = connections.current()))
    val state: StateFlow<LibraryState> = _state

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Error codes of actions that failed, for the screen to word. */
    val messages: SharedFlow<String> = _messages

    private var follow: Job? = null

    private val client: ServerClient? get() = connections.current()?.let(connections::client)

    /** Starts reading and following; called while the screen is visible. */
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
                if (changes.changed.isNotEmpty()) refresh(quiet = true)
                rev = changes.rev
            }
        }
    }

    fun stop() {
        follow?.cancel()
        follow = null
    }

    fun setFilter(filter: LibraryFilter) {
        _state.update { it.copy(filter = filter) }
        refresh()
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        refresh()
    }

    fun refresh(quiet: Boolean = false) = viewModelScope.launch {
        val api = client ?: return@launch
        if (!quiet) _state.update { it.copy(loading = true) }
        val s = _state.value
        runCatching {
            val page = when (val f = s.filter) {
                LibraryFilter.All -> api.library(query = s.query)
                LibraryFilter.Unsorted -> api.library(unsorted = true, query = s.query)
                is LibraryFilter.Folder -> api.library(folder = f.id, query = s.query)
                is LibraryFilter.Tag -> api.library(tag = f.name, query = s.query)
            }
            Triple(page, api.folders(), api.tags())
        }.onSuccess { (page, folders, tags) ->
            _state.update {
                it.copy(
                    items = page.items, nextBefore = page.nextBefore, folders = folders, tags = tags,
                    loading = false, loadedOnce = true, error = null,
                    // A folder deleted on another phone falls back to everything.
                    filter = it.filter.let { f ->
                        if (f is LibraryFilter.Folder && folders.none { d -> d.id == f.id }) LibraryFilter.All else f
                    },
                )
            }
        }.onFailure { e ->
            _state.update { it.copy(loading = false, loadedOnce = true, error = (e as? ServerException)?.code ?: "client.unknown") }
        }
    }

    fun loadMore() = viewModelScope.launch {
        val api = client ?: return@launch
        val s = _state.value
        val before = s.nextBefore ?: return@launch
        runCatching {
            when (val f = s.filter) {
                LibraryFilter.All -> api.library(query = s.query, before = before)
                LibraryFilter.Unsorted -> api.library(unsorted = true, query = s.query, before = before)
                is LibraryFilter.Folder -> api.library(folder = f.id, query = s.query, before = before)
                is LibraryFilter.Tag -> api.library(tag = f.name, query = s.query, before = before)
            }
        }.onSuccess { page ->
            _state.update { it.copy(items = it.items + page.items, nextBefore = page.nextBefore) }
        }
    }

    fun patch(id: String, patch: LibraryItemPatch) = act { it.patchLibraryItem(id, patch) }

    fun move(ids: Collection<String>, folderId: String?) = act { api ->
        ids.forEach { api.patchLibraryItem(it, LibraryItemPatch(folderId = folderId ?: "")) }
    }

    fun delete(ids: Collection<String>) = act { api -> ids.forEach { api.deleteLibraryItem(it) } }

    fun createFolder(name: String, moveIds: Collection<String> = emptyList()) = act { api ->
        val folder = api.createFolder(FolderInput(name.trim()))
        moveIds.forEach { api.patchLibraryItem(it, LibraryItemPatch(folderId = folder.id)) }
    }

    fun renameFolder(folder: FolderDto, name: String) = act { it.updateFolder(folder.id, FolderInput(name.trim())) }

    fun deleteFolder(folder: FolderDto) = act { it.deleteFolder(folder.id) }

    /** Tries waiting shares again now, e.g. after the network came back. */
    fun retryUploads() = UploadWorker.enqueue(getApplication())

    fun discardPending(share: PendingShare) = ServiceLocator.pendingShares(getApplication()).remove(share)

    fun mediaUrl(assetId: String): String? = client?.mediaUrl(assetId)

    fun thumbnailUrl(assetId: String): String? = client?.thumbnailUrl(assetId)

    private fun act(block: suspend (ServerClient) -> Unit) = viewModelScope.launch {
        val api = client ?: return@launch
        runCatching { block(api) }
            .onFailure { e -> _messages.tryEmit((e as? ServerException)?.code ?: "client.unknown") }
        refresh(quiet = true)
    }

    override fun onCleared() = stop()

    private companion object {
        const val RETRY_MS = 5_000L
    }
}
