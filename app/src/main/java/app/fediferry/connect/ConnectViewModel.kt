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
package app.fediferry.connect

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fediferry.R
import app.fediferry.api.ErrorCodes
import app.fediferry.client.ServerClient
import app.fediferry.client.ServerException
import app.fediferry.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ConnectState(
    val connection: ServerConnection? = null,
    val busy: Boolean = false,
    /** What went wrong on the last try, already in words. */
    val error: String? = null,
)

/**
 * Connects this phone to a FediFerry server project: checks the address,
 * checks the token by asking for its project, registers the phone, and keeps
 * the result. Nothing is kept until all three have worked.
 */
class ConnectViewModel(app: Application) : AndroidViewModel(app) {

    private val connections = ServiceLocator.serverConnections(app)

    private val _state = MutableStateFlow(ConnectState(connection = connections.current()))
    val state: StateFlow<ConnectState> = _state

    private var lastAddress: String? = null

    fun connect(address: String, token: String) = viewModelScope.launch {
        lastAddress = address
        _state.value = _state.value.copy(busy = true, error = null)
        val cleanAddress = ServerClient.normalise(address)?.toString()?.trimEnd('/')
        val cleanToken = token.trim()
        val result = runCatching {
            if (cleanAddress == null) throw ServerException(0, "client.bad_address")
            val client = connections.client(cleanAddress, cleanToken)
            client.health()
            val project = client.project()
            client.registerDevice(connections.registration())
            ServerConnection(cleanAddress, project.id, project.name, cleanToken, connections.deviceId)
        }
        result.onSuccess { connection ->
            connections.save(connection)
            _state.value = ConnectState(connection = connection)
        }.onFailure { e ->
            _state.value = _state.value.copy(busy = false, error = describe(e))
        }
    }

    fun disconnect() {
        connections.clear()
        _state.value = ConnectState()
    }

    private fun describe(e: Throwable): String {
        val app = getApplication<Application>()
        val server = e as? ServerException ?: return app.getString(R.string.server_error_unknown, e.message.orEmpty())
        return when {
            server.code == "client.bad_address" -> app.getString(R.string.server_error_address)
            server.unreachable && lastAddress?.let { LocalNetwork.isLocal(it) && LocalNetwork.exists(app) && !LocalNetwork.granted(app) } == true ->
                app.getString(R.string.server_error_local_network)
            server.unreachable -> app.getString(R.string.server_error_unreachable, server.args["reason"].orEmpty())
            server.code == ErrorCodes.UNAUTHORIZED -> app.getString(R.string.server_error_token)
            server.status == 404 -> app.getString(R.string.server_error_not_fediferry)
            else -> app.getString(R.string.server_error_unknown, server.code)
        }
    }
}
