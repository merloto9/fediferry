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
import app.fediferry.api.ScheduleDto
import app.fediferry.api.ScheduleInput
import app.fediferry.client.ServerClient
import app.fediferry.client.ServerException
import app.fediferry.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SchedulesState(
    val schedules: List<ScheduleDto> = emptyList(),
    val channels: List<ChannelDto> = emptyList(),
    val loaded: Boolean = false,
    val message: String? = null,
)

/** The project's upload schedules: when each channel's posts go out. */
class SchedulesViewModel(app: Application) : AndroidViewModel(app) {

    private val connections = ServiceLocator.serverConnections(app)
    private val client: ServerClient? get() = connections.current()?.let(connections::client)

    private val _state = MutableStateFlow(SchedulesState())
    val state: StateFlow<SchedulesState> = _state

    fun refresh() = viewModelScope.launch {
        val api = client ?: return@launch
        runCatching { api.schedules() to api.channels() }
            .onSuccess { (schedules, channels) -> _state.update { it.copy(schedules = schedules, channels = channels, loaded = true) } }
            .onFailure { e -> _state.update { it.copy(loaded = true, message = code(e)) } }
    }

    /** Creates a schedule when [id] is null, otherwise changes it. */
    fun save(id: String?, input: ScheduleInput) = act { api -> if (id == null) api.createSchedule(input) else api.updateSchedule(id, input) }

    fun setActive(schedule: ScheduleDto, active: Boolean) = act { it.updateSchedule(schedule.id, ScheduleInput(active = active)) }

    fun delete(schedule: ScheduleDto) = act { it.deleteSchedule(schedule.id) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun act(block: suspend (ServerClient) -> Unit) = viewModelScope.launch {
        val api = client ?: return@launch
        runCatching { block(api) }.onFailure { e -> _state.update { it.copy(message = code(e)) } }
        refresh()
    }

    private fun code(e: Throwable) = (e as? ServerException)?.code ?: "client.unknown"
}
