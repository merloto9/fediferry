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
package app.fediferry.ui.sources

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fediferry.data.model.Source
import app.fediferry.di.ServiceLocator
import app.fediferry.link.fieldsOf
import app.fediferry.source.SourcePost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChannelState(
    val source: Source? = null,
    val posts: List<SourcePost> = emptyList(),
    val loading: Boolean = true,
    val picking: String? = null,
    val error: String? = null,
    val message: String? = null,
)

class ChannelViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = ServiceLocator.database(app).sources()
    private val youtube = ServiceLocator.youtube()
    private val repo = ServiceLocator.items(app)

    private val _state = MutableStateFlow(ChannelState())
    val state: StateFlow<ChannelState> = _state.asStateFlow()

    fun load(sourceId: String) = viewModelScope.launch {
        val source = dao.byId(sourceId)
        if (source == null) {
            _state.update { it.copy(loading = false, error = "That channel is gone") }
            return@launch
        }
        _state.update { it.copy(source = source, loading = true, error = null) }
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        val source = _state.value.source ?: return@launch
        _state.update { it.copy(loading = true, error = null) }
        youtube.posts(source.handle).fold(
            onSuccess = { posts ->
                // Only posts with a picture are useful here.
                _state.update {
                    it.copy(loading = false, posts = posts.filter { p -> p.hasImages })
                }
            },
            onFailure = { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "Could not load posts") }
            },
        )
    }

    /**
     * Downloads the chosen picture and makes an item of it, so it continues into
     * the same editor as a share or a screenshot.
     */
    fun pick(post: SourcePost, imageUrl: String, onReady: (String) -> Unit) = viewModelScope.launch {
        _state.update { it.copy(picking = imageUrl) }
        repo.ingestFromSource(
            imageUrl = imageUrl,
            permalink = post.permalink,
            fields = fieldsOf("text" to post.text, "channel" to post.author),
        ).fold(
            onSuccess = { item ->
                _state.update { it.copy(picking = null) }
                onReady(item.id)
            },
            onFailure = { e ->
                _state.update {
                    it.copy(picking = null, message = "Could not use that picture: ${e.message}")
                }
            },
        )
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
