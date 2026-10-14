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
import app.fediferry.api.PublishedStatusDto
import app.fediferry.api.ScheduleDto
import app.fediferry.backend.PostBackend
import app.fediferry.backend.ScreenCache
import app.fediferry.client.ServerException
import app.fediferry.di.ServiceLocator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PublishingState(
    /** Planned and being sent, soonest first. */
    val queue: List<PostDto> = emptyList(),
    val failed: List<PostDto> = emptyList(),
    /** Out already, most recent first, as the channels' instances have them. */
    val published: List<PublishedStatusDto> = emptyList(),
    /** Channels whose posts could not be read just now. */
    val publishedUnreachable: List<String> = emptyList(),
    val channels: List<ChannelDto> = emptyList(),
    val schedules: List<ScheduleDto> = emptyList(),
    val loadedOnce: Boolean = false,
    val error: String? = null,
)

/**
 * The project's way out: what the server will publish and when, what failed,
 * and what went out. The server does the publishing; this only shows it, and
 * follows the change feed so a post going out shows here as it happens.
 *
 * Nothing that went out is kept on the server, so the latest posts are read
 * from Mastodon itself. A channel that cannot be read leaves a gap, never an
 * error over the whole screen.
 */
class PublishingViewModel(app: Application) : AndroidViewModel(app) {

    private val backend: PostBackend? get() = ServiceLocator.backend(getApplication())

    // What this tab showed last, at once; it is read again in the background.
    private val _state = MutableStateFlow(ScreenCache.get<PublishingState>(ServiceLocator.backend(app), SCREEN)?.copy(error = null) ?: PublishingState())
    val state: StateFlow<PublishingState> = _state

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
            source.changes().collect { types -> if (types.any { it in WATCHED }) refresh() }
        }
    }

    fun stop() {
        follow?.cancel()
        follow = null
    }

    fun refresh() = viewModelScope.launch {
        val api = backend ?: return@launch
        runCatching {
            val waiting = api.posts(PostStages.SCHEDULED, PostStages.PUBLISHING, PostStages.FAILED)
            val published = runCatching { api.published(HISTORY) }.getOrNull()
            Loaded(waiting, published?.statuses, published?.unreachable.orEmpty(), api.channels(), api.schedules())
        }.onSuccess { l ->
            _state.update {
                it.copy(
                    queue = l.waiting.filter { p -> p.stage != PostStages.FAILED }.sortedBy { p -> p.publication?.publishAfter ?: Long.MAX_VALUE },
                    failed = l.waiting.filter { p -> p.stage == PostStages.FAILED },
                    // Mastodon not answering keeps what was shown rather than emptying it.
                    published = l.published ?: it.published,
                    publishedUnreachable = if (l.published == null) l.channels.map { c -> c.id } else l.unreachable,
                    channels = l.channels,
                    schedules = l.schedules,
                    loadedOnce = true,
                    error = null,
                )
            }
        }.onFailure { e -> _state.update { it.copy(loadedOnce = true, error = (e as? ServerException)?.code ?: "client.unknown") } }
    }

    private class Loaded(
        val waiting: List<PostDto>,
        val published: List<PublishedStatusDto>?,
        val unreachable: List<String>,
        val channels: List<ChannelDto>,
        val schedules: List<ScheduleDto>,
    )

    fun thumbnailUrl(assetId: String): String? = backend?.thumbnailUrl(assetId)

    override fun onCleared() = stop()

    private companion object {
        const val SCREEN = "publishing"
        /** How many of the latest posts are read back from the channels. */
        const val HISTORY = 20
        val WATCHED = setOf("post", "schedule", "channel")
    }
}
