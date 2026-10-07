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
import app.fediferry.api.ReviewFolderDto
import app.fediferry.api.ReviewPatch
import app.fediferry.api.PlanRequest
import app.fediferry.api.ScheduleDto
import app.fediferry.client.ServerClient
import app.fediferry.client.ServerException
import app.fediferry.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewPostState(
    val post: PostDto? = null,
    val loaded: Boolean = false,
    val channels: List<ChannelDto> = emptyList(),
    val folders: List<ReviewFolderDto> = emptyList(),
    val labels: List<LabelDto> = emptyList(),
    val schedules: List<ScheduleDto> = emptyList(),
    /** An error code, for the screen to word. */
    val message: String? = null,
) {
    val channel: ChannelDto? get() = channels.firstOrNull { it.id == post?.channelId }

    /** The soonest free slot among the post's channel's active schedules, and which schedule offers it. */
    val nextSlot: Pair<Long, ScheduleDto>?
        get() = schedules.filter { it.active && it.channelId == post?.channelId }
            .mapNotNull { s -> s.nextFree.firstOrNull()?.let { it to s } }
            .minByOrNull { it.first }
}

/** One ready post: read-only, but it can be sorted, sent back to draft or deleted. */
class ReviewPostViewModel(app: Application) : AndroidViewModel(app) {

    private val connections = ServiceLocator.serverConnections(app)
    private val client: ServerClient? get() = connections.current()?.let(connections::client)
    private var postId: String? = null

    private val _state = MutableStateFlow(ReviewPostState())
    val state: StateFlow<ReviewPostState> = _state

    fun load(id: String) {
        postId = id
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        val api = client ?: return@launch
        val id = postId ?: return@launch
        runCatching { api.post(id) }
            .onSuccess { post -> _state.update { it.copy(post = post, loaded = true) } }
            .onFailure { e ->
                val gone = (e as? ServerException)?.status == 404
                _state.update { it.copy(loaded = true, post = if (gone) null else it.post, message = if (gone) null else code(e)) }
            }
        runCatching { Triple(api.channels(), api.reviewFolders(), api.labels()) }
            .onSuccess { (channels, folders, labels) -> _state.update { it.copy(channels = channels, folders = folders, labels = labels) } }
        runCatching { api.schedules() }.onSuccess { schedules -> _state.update { it.copy(schedules = schedules) } }
    }

    fun setFolder(folderId: String?) = sort(ReviewPatch(folderId = folderId ?: ""))

    fun setLabel(label: String, on: Boolean) {
        val post = _state.value.post ?: return
        val labels = if (on) post.labels + label else post.labels.filterNot { it.equals(label, ignoreCase = true) }
        sort(ReviewPatch(labels = labels))
    }

    private fun sort(patch: ReviewPatch) = viewModelScope.launch {
        val api = client ?: return@launch
        val id = postId ?: return@launch
        runCatching { api.sortForReview(id, patch) }
            .onSuccess { post -> _state.update { it.copy(post = post) } }
            .onFailure { e -> _state.update { it.copy(message = code(e)) } }
        runCatching { api.labels() }.onSuccess { labels -> _state.update { it.copy(labels = labels) } }
    }

    /** Into the queue: now, at [at], or into the next free slot. Also moves a planned post and retries a failed one. */
    fun plan(request: PlanRequest) = viewModelScope.launch {
        val api = client ?: return@launch
        val id = postId ?: return@launch
        runCatching { api.plan(id, request) }
            .onSuccess { post -> _state.update { it.copy(post = post) } }
            .onFailure { e -> _state.update { it.copy(message = code(e)) } }
        runCatching { api.schedules() }.onSuccess { schedules -> _state.update { it.copy(schedules = schedules) } }
    }

    /** Out of the queue, back to review. */
    fun unplan() = viewModelScope.launch {
        val api = client ?: return@launch
        val id = postId ?: return@launch
        runCatching { api.unplan(id) }
            .onSuccess { post -> _state.update { it.copy(post = post) } }
            .onFailure { e -> _state.update { it.copy(message = code(e)) } }
    }

    /** Opens the post for editing again; [onDraft] then shows it in the editor. */
    fun backToDraft(onDraft: (String) -> Unit) = viewModelScope.launch {
        val api = client ?: return@launch
        val id = postId ?: return@launch
        runCatching { api.backToDraft(id) }
            .onSuccess { onDraft(it.id) }
            .onFailure { e -> _state.update { it.copy(message = code(e)) } }
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        val api = client ?: return@launch
        val id = postId ?: return@launch
        runCatching { api.deletePost(id) }
            .onSuccess { onDone() }
            .onFailure { e -> _state.update { it.copy(message = code(e)) } }
    }

    fun mediaUrl(assetId: String): String? = client?.mediaUrl(assetId)

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun code(e: Throwable) = (e as? ServerException)?.code ?: "client.unknown"
}
