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
package app.fediferry.analyze

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fediferry.api.ChannelDto
import app.fediferry.api.ChannelInsightsDto
import app.fediferry.api.InsightsPage
import app.fediferry.backend.ScreenCache
import app.fediferry.client.ServerException
import app.fediferry.di.ServiceLocator
import app.fediferry.insights.Analysis
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why there are no numbers to show. */
enum class AnalyzeProblem { UNREADABLE, SERVER_TOO_OLD }

data class AnalyzeState(
    /** 7, 30 or 90. */
    val days: Int = 30,
    /** One channel, or null for all of them together. */
    val channelId: String? = null,
    val page: InsightsPage? = null,
    val channels: List<ChannelDto> = emptyList(),
    val loading: Boolean = false,
    val problem: AnalyzeProblem? = null,
) {
    /** What is shown: the chosen channel, or every channel taken together. */
    val shown: ChannelInsightsDto?
        get() {
            val all = page?.channels ?: return null
            return if (channelId == null) Analysis.combine(all).takeIf { all.isNotEmpty() } else all.firstOrNull { it.channelId == channelId }
        }

    fun nameOf(channelId: String): String = channels.firstOrNull { it.id == channelId }?.name ?: channelId
}

/**
 * Analyze: reads the numbers through the backend, which reads them from the
 * channels' platforms. Platform-neutral: it only sees `api` types, and leaves
 * the arithmetic to [Analysis].
 */
class AnalyzeViewModel(app: Application) : AndroidViewModel(app) {

    private val backend get() = ServiceLocator.backend(getApplication())

    // The last reading shows at once; a new one is fetched behind it.
    private val _state = MutableStateFlow(ScreenCache.get<AnalyzeState>(backend, SCREEN)?.copy(loading = false, problem = null) ?: AnalyzeState())
    val state: StateFlow<AnalyzeState> = _state

    private var reading: Job? = null

    /** Reads again each time the space opens: the numbers change by the minute. */
    fun start() {
        if (!_state.value.loading) refresh()
    }

    fun setDays(days: Int) {
        if (days == _state.value.days) return
        _state.update { it.copy(days = days) }
        refresh()
    }

    fun setChannel(channelId: String?) = _state.update { it.copy(channelId = channelId) }

    fun refresh() {
        reading?.cancel()
        reading = viewModelScope.launch {
            val api = backend
            _state.update { it.copy(loading = true, problem = null) }
            runCatching { api.insights(_state.value.days) to runCatching { api.channels() }.getOrDefault(_state.value.channels) }
                .onSuccess { (page, channels) ->
                    _state.update { s ->
                        // A channel that is gone is no longer a choice.
                        val chosen = s.channelId?.takeIf { id -> page.channels.any { it.channelId == id } }
                        s.copy(page = page, channels = channels, channelId = chosen, loading = false)
                    }
                    ScreenCache.put(api, SCREEN, _state.value)
                }
                .onFailure { e ->
                    // A server from before Analyze has no such address.
                    val tooOld = e is ServerException && e.status == 404
                    _state.update { it.copy(loading = false, problem = if (tooOld) AnalyzeProblem.SERVER_TOO_OLD else AnalyzeProblem.UNREADABLE) }
                }
        }
    }

    private companion object {
        const val SCREEN = "analyze"
    }
}
