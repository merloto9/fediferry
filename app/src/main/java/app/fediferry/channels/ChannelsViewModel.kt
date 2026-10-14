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
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fediferry.api.ChannelDto
import app.fediferry.api.ChannelInput
import app.fediferry.backend.PostBackend
import app.fediferry.client.ServerException
import app.fediferry.di.ServiceLocator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChannelsState(
    val channels: List<ChannelDto> = emptyList(),
    val loading: Boolean = true,
    /** Accounts this phone signed in to on its own that the project does not have yet. */
    val localAccounts: Int = 0,
    val error: String? = null,
)

/**
 * The project's channels: where its posts go. Each is set up on its own page
 * ([ChannelSettingsViewModel]); this lists them and starts new ones.
 */
class ChannelsViewModel(app: Application) : AndroidViewModel(app) {

    private val backend: PostBackend? get() = ServiceLocator.backend(getApplication())
    private val connections = ServiceLocator.serverConnections(app)
    private val db = ServiceLocator.database(app)
    private val tokens = ServiceLocator.tokens(app)

    private val _state = MutableStateFlow(ChannelsState())
    val state: StateFlow<ChannelsState> = _state

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        val api = backend ?: return@launch
        runCatching { api.channels() }
            .onSuccess { list ->
                val taken = list.mapNotNull { it.account?.let { a -> "${a.instance}/${a.acct}" } }.toSet() + list.map { it.id }
                // Only a server's project can take this phone's accounts over; the phone's own are its channels.
                val local = if (connections.current() == null) 0 else db.accounts().all().count { a -> a.id !in taken && tokens.get(a.id) != null }
                _state.update { ChannelsState(channels = list, loading = false, localAccounts = local) }
            }
            .onFailure { e -> _state.update { it.copy(loading = false, error = code(e)) } }
    }

    /** A new channel named [name], with no account yet; [onCreated] gets its id to open it. */
    fun create(name: String, onCreated: (String) -> Unit) = viewModelScope.launch {
        val api = backend ?: return@launch
        runCatching { api.createChannel(ChannelInput(name.trim())) }
            .onSuccess { refresh(); onCreated(it.id) }
            .onFailure { _messages.tryEmit(code(it)) }
    }

    /** Moves this phone's own Mastodon accounts into the project, tokens and all; no new sign-in. */
    fun takeOverAccounts() = viewModelScope.launch {
        val client = connections.current()?.let(connections::client) ?: return@launch
        val known = _state.value.channels.map { it.id }.toSet()
        for (account in db.accounts().all()) {
            if (account.id in known) continue
            val token = tokens.get(account.id) ?: continue
            runCatching { client.importMastodon(account.instance, token, account.id) }
                .onFailure { _messages.tryEmit(code(it)) }
        }
        refresh()
    }

    private fun code(e: Throwable) = (e as? ServerException)?.code ?: e.javaClass.simpleName
}

/**
 * The state of a sign-in this phone started through the server. The browser
 * hands it back with the code; it tells the redirect to finish on the server
 * rather than on the phone.
 */
object PendingChannelSignIn {
    private const val PREFS = "channel_sign_in"
    private const val KEY = "state"

    fun remember(context: Context, state: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, state) }

    /** True — once — when [state] is the server sign-in this phone started. */
    fun take(context: Context, state: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY, null) != state) return false
        prefs.edit { remove(KEY) }
        return true
    }
}
