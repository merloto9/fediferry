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
package app.fediferry.publishing

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fediferry.api.ChannelDto
import app.fediferry.api.PostDto
import app.fediferry.api.PostStages
import app.fediferry.api.ScheduleDto
import app.fediferry.client.ServerClient
import app.fediferry.client.ServerException
import app.fediferry.di.ServiceLocator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PublishingState(
    /** Planned and being sent, soonest first. */
    val queue: List<PostDto> = emptyList(),
    val failed: List<PostDto> = emptyList(),
    /** Out already, most recent first. */
    val published: List<PostDto> = emptyList(),
    val channels: List<ChannelDto> = emptyList(),
    val schedules: List<ScheduleDto> = emptyList(),
    val loadedOnce: Boolean = false,
    val error: String? = null,
)

/**
 * The project's way out: what the server will publish and when, what failed,
 * and what went out. The server does the publishing; this only shows it, and
 * follows the change feed so a post going out shows here as it happens.
 */
class PublishingViewModel(app: Application) : AndroidViewModel(app) {

    private val connections = ServiceLocator.serverConnections(app)
    private val client: ServerClient? get() = connections.current()?.let(connections::client)

    private val _state = MutableStateFlow(PublishingState())
    val state: StateFlow<PublishingState> = _state

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

    fun refresh() = viewModelScope.launch {
        val api = client ?: return@launch
        runCatching {
            val waiting = api.posts(PostStages.SCHEDULED, PostStages.PUBLISHING, PostStages.FAILED)
            Loaded(waiting, api.posts(PostStages.PUBLISHED), api.channels(), api.schedules())
        }.onSuccess { l ->
            _state.update {
                it.copy(
                    queue = l.waiting.filter { p -> p.stage != PostStages.FAILED }.sortedBy { p -> p.publication?.publishAfter ?: Long.MAX_VALUE },
                    failed = l.waiting.filter { p -> p.stage == PostStages.FAILED },
                    published = l.published.sortedByDescending { p -> p.publication?.publishedAt ?: p.updatedAt }.take(HISTORY),
                    channels = l.channels,
                    schedules = l.schedules,
                    loadedOnce = true,
                    error = null,
                )
            }
        }.onFailure { e -> _state.update { it.copy(loadedOnce = true, error = (e as? ServerException)?.code ?: "client.unknown") } }
    }

    private class Loaded(val waiting: List<PostDto>, val published: List<PostDto>, val channels: List<ChannelDto>, val schedules: List<ScheduleDto>)

    fun thumbnailUrl(assetId: String): String? = client?.thumbnailUrl(assetId)

    override fun onCleared() = stop()

    private companion object {
        const val RETRY_MS = 5_000L
        const val HISTORY = 50
        val WATCHED = setOf("post", "schedule", "channel")
    }
}
