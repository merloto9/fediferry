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
import app.fediferry.data.model.SourceKind
import app.fediferry.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class SourcesState(
    val adding: Boolean = false,
    val message: String? = null,
)

class SourcesViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = ServiceLocator.database(app).sources()
    private val youtube = ServiceLocator.youtube()

    val sources: StateFlow<List<Source>> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(SourcesState())
    val state: StateFlow<SourcesState> = _state.asStateFlow()

    /**
     * Adds a channel, after checking it actually has readable posts.
     *
     * Saving first and failing later would leave a row that never loads, so the
     * fetch is the validation: the channel's own name comes from the same
     * response.
     */
    fun add(input: String) = viewModelScope.launch {
        val handle = youtube.normaliseHandle(input)
        if (handle == null) {
            _state.update { it.copy(message = "Enter a channel handle like @heuteshow") }
            return@launch
        }
        if (dao.byHandle(SourceKind.YOUTUBE.name, handle) != null) {
            _state.update { it.copy(message = "@$handle is already here") }
            return@launch
        }

        _state.update { it.copy(adding = true, message = null) }
        youtube.posts(handle).fold(
            onSuccess = { posts ->
                dao.upsert(
                    Source(
                        id = UUID.randomUUID().toString(),
                        kind = SourceKind.YOUTUBE,
                        handle = handle,
                        displayName = posts.firstNotNullOfOrNull { it.author } ?: "@$handle",
                        sortOrder = dao.count(),
                    ),
                )
                _state.update { it.copy(adding = false) }
            },
            onFailure = { e ->
                _state.update { it.copy(adding = false, message = e.message ?: "Could not add that channel") }
            },
        )
    }

    fun remove(id: String) = viewModelScope.launch { dao.delete(id) }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
