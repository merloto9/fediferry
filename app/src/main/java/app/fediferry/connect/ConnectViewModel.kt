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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConnectState(
    val connection: ServerConnection? = null,
    val busy: Boolean = false,
    /** What went wrong on the last try, already in words. */
    val error: String? = null,
    /** Posts kept on this phone that are not in the project yet. */
    val waitingToImport: Int = 0,
    /** How many were just copied, to confirm it. */
    val justImported: Int? = null,
    /** A checked project this phone would bring its own into, waiting for the user's yes or no. */
    val pendingJoin: PendingJoin? = null,
    /** This phone's project is being copied into the server's. */
    val joining: Boolean = false,
)

/** A project ready to join, what joining copies, and the side chosen for each conflict. */
data class PendingJoin(
    val connection: ServerConnection,
    val plan: JoinPlan,
    val choices: Map<String, MergeSide> = plan.conflicts.associate { it.key to MergeSide.SERVER },
)

/**
 * Connects this phone to a FediFerry server project: checks the address,
 * checks the token by asking for its project, registers the phone, and keeps
 * the result. Nothing is kept until all three have worked.
 *
 * A phone with a project of its own does not connect straight away: it shows
 * what joining would copy, and asks which side stays where both have one
 * thing differently. Going back leaves everything as it was.
 */
class ConnectViewModel(app: Application) : AndroidViewModel(app) {

    private val connections = ServiceLocator.serverConnections(app)

    private val _state = MutableStateFlow(ConnectState(connection = connections.current()))
    val state: StateFlow<ConnectState> = _state

    private var lastAddress: String? = null

    private val importer get() = ServiceLocator.localImporter(getApplication())
    private val merge get() = ServiceLocator.projectMerge(getApplication())

    /** Copying outlives this screen: leaving it half-way must not stop it. */
    private val appScope get() = ServiceLocator.appScope

    init {
        refreshImport()
    }

    private fun refreshImport() = viewModelScope.launch {
        val connection = connections.current() ?: return@launch
        _state.update { it.copy(waitingToImport = importer.waiting(connection.projectId)) }
    }

    /** Copies this phone's posts not in the project yet: a try again after an interrupted join. */
    fun importPosts() = appScope.launch {
        val connection = connections.current() ?: return@launch
        _state.update { it.copy(joining = true, error = null) }
        val result = runCatching {
            merge.copyPostsAndSchedules(connections.client(connection), connection.projectId, merge.channels(connection.projectId))
        }
        _state.update {
            it.copy(
                joining = false, waitingToImport = importer.waiting(connection.projectId),
                justImported = result.getOrNull(), error = result.exceptionOrNull()?.let(::describe),
            )
        }
    }

    fun connect(address: String, token: String) = viewModelScope.launch {
        lastAddress = address
        _state.update { it.copy(busy = true, error = null) }
        val cleanAddress = ServerClient.normalise(address)?.toString()?.trimEnd('/')
        val cleanToken = token.trim()
        val result = runCatching {
            if (cleanAddress == null) throw ServerException(0, "client.bad_address")
            val client = connections.client(cleanAddress, cleanToken)
            val health = client.health()
            val project = client.project()
            val connection = ServerConnection(cleanAddress, project.id, project.name, cleanToken, connections.deviceId)
            connection to merge.plan(client, health)
        }
        result.onSuccess { (connection, plan) ->
            if (plan.phoneHasOwn) {
                _state.update { it.copy(busy = false, pendingJoin = PendingJoin(connection, plan)) }
            } else {
                join(connection, plan = null)
            }
        }.onFailure { e ->
            _state.update { it.copy(busy = false, error = describe(e)) }
        }
    }

    /** Which side stays for one conflict. */
    fun choose(key: String, side: MergeSide) = _state.update { s ->
        s.copy(pendingJoin = s.pendingJoin?.let { it.copy(choices = it.choices + (key to side)) })
    }

    /** The same side for every conflict. */
    fun chooseAll(side: MergeSide) = _state.update { s ->
        s.copy(pendingJoin = s.pendingJoin?.let { p -> p.copy(choices = p.plan.conflicts.associate { it.key to side }) })
    }

    /** Back without connecting: nothing was kept, nothing changed. */
    fun cancelJoin() = _state.update { it.copy(pendingJoin = null) }

    /** Yes: joins, copying this phone's project into the server's with the sides chosen. */
    fun confirmJoin() {
        val pending = _state.value.pendingJoin ?: return
        join(pending.connection, pending)
    }

    /**
     * Registers the phone and keeps the connection. With a [plan], settings
     * and channels are merged first, and posts are copied once connected.
     * Without one, the first sync takes the project's settings, or sends this
     * phone's to a project that has none.
     */
    private fun join(connection: ServerConnection, plan: PendingJoin?) = appScope.launch {
        _state.update { it.copy(busy = true, joining = plan != null, error = null) }
        val client = connections.client(connection)
        val result = runCatching {
            client.registerDevice(connections.registration())
            val channels = plan?.let { merge.mergeSettingsAndChannels(client, connection.projectId, it.choices) }
            connections.save(connection)
            ServiceLocator.projectSync(getApplication()).restartFollowing()
            _state.update { ConnectState(connection = connection, joining = plan != null) }
            channels
        }
        result.onSuccess { channels ->
            if (channels != null) {
                val copied = runCatching { merge.copyPostsAndSchedules(client, connection.projectId, channels) }
                _state.update { it.copy(joining = false, justImported = copied.getOrNull(), error = copied.exceptionOrNull()?.let(::describe)) }
            }
            refreshImport()
        }.onFailure { e ->
            _state.update { it.copy(busy = false, joining = false, error = describe(e)) }
        }
    }

    fun disconnect() {
        ServiceLocator.projectSync(getApplication()).stop()
        connections.clear()
        _state.value = ConnectState()
    }

    private fun describe(e: Throwable): String {
        val app = getApplication<Application>()
        val server = e as? ServerException ?: return app.getString(R.string.server_error_unknown, e.message.orEmpty())
        return when {
            server.code == "client.bad_address" -> app.getString(R.string.server_error_address)
            server.code == LocalImporter.SERVER_TOO_OLD -> app.getString(R.string.server_error_too_old)
            server.unreachable && lastAddress?.let { LocalNetwork.isLocal(it) && LocalNetwork.exists(app) && !LocalNetwork.granted(app) } == true ->
                app.getString(R.string.server_error_local_network)
            server.unreachable -> app.getString(R.string.server_error_unreachable, server.args["reason"].orEmpty())
            server.code == ErrorCodes.UNAUTHORIZED -> app.getString(R.string.server_error_token)
            server.status == 404 -> app.getString(R.string.server_error_not_fediferry)
            else -> app.getString(R.string.server_error_unknown, server.code)
        }
    }
}
