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
package app.fediferry.review

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fediferry.api.ChannelDto
import app.fediferry.api.LabelDto
import app.fediferry.api.PostDto
import app.fediferry.api.PostStages
import app.fediferry.api.ReviewFolderDto
import app.fediferry.api.ReviewFolderInput
import app.fediferry.api.ReviewPatch
import app.fediferry.api.PlanModes
import app.fediferry.api.PlanRequest
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

/** Which ready posts are shown. */
sealed interface ReviewFilter {
    data object All : ReviewFilter
    data object Unsorted : ReviewFilter
    data class Folder(val id: String) : ReviewFilter
    data class Label(val name: String) : ReviewFilter
}

data class ReviewState(
    val posts: List<PostDto> = emptyList(),
    val folders: List<ReviewFolderDto> = emptyList(),
    val labels: List<LabelDto> = emptyList(),
    val channels: List<ChannelDto> = emptyList(),
    val filter: ReviewFilter = ReviewFilter.All,
    val loadedOnce: Boolean = false,
    val error: String? = null,
) {
    val shown: List<PostDto>
        get() = when (val f = filter) {
            ReviewFilter.All -> posts
            ReviewFilter.Unsorted -> posts.filter { it.reviewFolderId == null }
            is ReviewFilter.Folder -> posts.filter { it.reviewFolderId == f.id }
            is ReviewFilter.Label -> posts.filter { p -> p.labels.any { it.equals(f.name, ignoreCase = true) } }
        }
}

/**
 * The project's ready posts: frozen, waiting to be scheduled, and sorted into
 * review folders and labels. Follows the change feed like the library.
 */
class ReviewViewModel(app: Application) : AndroidViewModel(app) {

    private val connections = ServiceLocator.serverConnections(app)
    private val client: ServerClient? get() = connections.current()?.let(connections::client)

    private val _state = MutableStateFlow(ReviewState())
    val state: StateFlow<ReviewState> = _state

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
                if (changes.changed.any { it.type in WATCHED }) refresh()
                rev = changes.rev
            }
        }
    }

    fun stop() {
        follow?.cancel()
        follow = null
    }

    fun setFilter(filter: ReviewFilter) = _state.update { it.copy(filter = filter) }

    fun refresh() = viewModelScope.launch {
        val api = client ?: return@launch
        runCatching { Loaded(api.posts(PostStages.READY), api.reviewFolders(), api.labels(), api.channels()) }
            .onSuccess { l ->
                _state.update { s ->
                    s.copy(
                        posts = l.posts.sortedByDescending { it.readyAt ?: it.updatedAt },
                        folders = l.folders, labels = l.labels, channels = l.channels,
                        loadedOnce = true, error = null,
                        // A folder or label gone on another phone falls back to everything.
                        filter = when (val f = s.filter) {
                            is ReviewFilter.Folder -> if (l.folders.any { it.id == f.id }) f else ReviewFilter.All
                            is ReviewFilter.Label -> if (l.labels.any { it.name.equals(f.name, ignoreCase = true) }) f else ReviewFilter.All
                            else -> f
                        },
                    )
                }
            }
            .onFailure { e -> _state.update { it.copy(loadedOnce = true, error = (e as? ServerException)?.code ?: "client.unknown") } }
    }

    private data class Loaded(val posts: List<PostDto>, val folders: List<ReviewFolderDto>, val labels: List<LabelDto>, val channels: List<ChannelDto>)

    fun move(ids: Collection<String>, folderId: String?) = act { api -> ids.forEach { api.sortForReview(it, ReviewPatch(folderId = folderId ?: "")) } }

    /** Puts [label] on every post in [ids], or takes it off every one. */
    fun setLabel(ids: Collection<String>, label: String, on: Boolean) = act { api ->
        val name = label.trim().removePrefix("#").takeIf { it.isNotEmpty() } ?: return@act
        _state.value.posts.filter { it.id in ids }.forEach { post ->
            val has = post.labels.any { it.equals(name, ignoreCase = true) }
            if (has != on) {
                api.sortForReview(post.id, ReviewPatch(labels = if (on) post.labels + name else post.labels.filterNot { it.equals(name, ignoreCase = true) }))
            }
        }
    }

    fun createFolder(name: String, moveIds: Collection<String> = emptyList()) = act { api ->
        val folder = api.createReviewFolder(ReviewFolderInput(name.trim()))
        moveIds.forEach { api.sortForReview(it, ReviewPatch(folderId = folder.id)) }
    }

    fun renameFolder(folder: ReviewFolderDto, name: String) = act { it.updateReviewFolder(folder.id, ReviewFolderInput(name.trim())) }

    fun deleteFolder(folder: ReviewFolderDto) = act { it.deleteReviewFolder(folder.id) }

    /**
     * Plans each post into the next free slot of its channel, in the order
     * shown, so the first in the list goes out first. Stops at the first
     * refusal (no schedule, no free slot) and says why.
     */
    fun planIntoSlots(ids: Collection<String>, onPlanned: (Int) -> Unit) = viewModelScope.launch {
        val api = client ?: return@launch
        var planned = 0
        for (post in _state.value.shown.filter { it.id in ids }) {
            val failed = runCatching { api.plan(post.id, PlanRequest(PlanModes.NEXT_SLOT)) }.exceptionOrNull()
            if (failed != null) {
                _messages.tryEmit((failed as? ServerException)?.code ?: "client.unknown")
                break
            }
            planned++
        }
        if (planned > 0) onPlanned(planned)
        refresh()
    }

    fun delete(ids: Collection<String>) = act { api -> ids.forEach { api.deletePost(it) } }

    fun thumbnailUrl(assetId: String): String? = client?.thumbnailUrl(assetId)

    private fun act(block: suspend (ServerClient) -> Unit) = viewModelScope.launch {
        val api = client ?: return@launch
        runCatching { block(api) }.onFailure { e -> _messages.tryEmit((e as? ServerException)?.code ?: "client.unknown") }
        refresh()
    }

    override fun onCleared() = stop()

    private companion object {
        const val RETRY_MS = 5_000L
        val WATCHED = setOf("post", "review_folder", "channel")
    }
}
