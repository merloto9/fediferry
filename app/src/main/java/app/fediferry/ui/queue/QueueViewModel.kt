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
package app.fediferry.ui.queue

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fediferry.data.model.Account
import app.fediferry.di.ServiceLocator
import app.fediferry.log.DebugLog
import app.fediferry.mastodon.MastodonException
import app.fediferry.mastodon.ScheduledStatus
import app.fediferry.R
import app.fediferry.work.ScheduleFormat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** One post Mastodon holds for later, and whose it is. */
data class QueuedPost(val account: Account, val status: ScheduledStatus, val at: Long)

/** Why an account's queue could not be shown. */
sealed interface QueueProblem {
    /** Signed in before FediFerry asked to read scheduled posts. */
    data object NeedsReconnect : QueueProblem
    data class Failed(val reason: String) : QueueProblem
}

data class QueueState(
    val loading: Boolean = true,
    val loadedOnce: Boolean = false,
    val accounts: List<Account> = emptyList(),
    val posts: List<QueuedPost> = emptyList(),
    val problems: Map<Account, QueueProblem> = emptyMap(),
    /** A draft is being made from a queued post: its picture is downloading. */
    val drafting: Boolean = false,
)

/**
 * The posts Mastodon will publish later, read from each connected account.
 * Nothing here is stored: every look is a fresh read, and every change goes
 * straight to the server.
 */
class QueueViewModel(app: Application) : AndroidViewModel(app) {

    private val accounts = ServiceLocator.database(app).accounts()
    private val tokens = ServiceLocator.tokens(app)
    private val client = ServiceLocator.mastodon(app)
    private val auth = ServiceLocator.auth(app)
    private val actions = ScheduledActions(app)

    private val _state = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = _state

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true)
        val all = accounts.all()
        val results = all.map { account -> async { account to load(account) } }.awaitAll()
        _state.value = QueueState(
            loading = false,
            loadedOnce = true,
            accounts = all,
            posts = results.flatMap { (_, r) -> r.getOrNull().orEmpty() }.sortedBy { it.at },
            problems = results.mapNotNull { (a, r) -> r.exceptionOrNull()?.let { a to problemOf(it) } }.toMap(),
        )
    }

    private suspend fun load(account: Account): Result<List<QueuedPost>> = runCatching {
        val token = tokens.get(account.id) ?: throw MastodonException(getApplication<Application>().getString(R.string.queue_no_token), code = 401)
        client.scheduledStatuses(account.instance, token).map { status ->
            QueuedPost(account, status, ScheduleFormat.parse(status.scheduledAt) ?: 0L)
        }
    }

    private fun problemOf(error: Throwable): QueueProblem {
        val code = (error as? MastodonException)?.code
        if (code == 401 || code == 403) return QueueProblem.NeedsReconnect
        DebugLog.w(LOG, "Could not read the scheduled posts", error)
        return QueueProblem.Failed(error.message ?: getApplication<Application>().getString(R.string.queue_unreachable))
    }

    /** Signs in again, asking for the permission to read scheduled posts. */
    fun reconnect(account: Account) = viewModelScope.launch {
        runCatching { auth.beginAuthorization(account.instance) }
            .onFailure { _messages.tryEmit(it.message ?: getApplication<Application>().getString(R.string.queue_sign_in_failed)) }
    }

    fun reschedule(post: QueuedPost, at: Long) = act { actions.reschedule(post, at) }

    fun cancel(post: QueuedPost) = act { actions.cancel(post) }

    fun makeDraft(post: QueuedPost, cancelAfter: Boolean) = viewModelScope.launch {
        _state.value = _state.value.copy(drafting = true)
        val outcome = actions.makeDraft(post, cancelAfter)
        _state.value = _state.value.copy(drafting = false)
        _messages.tryEmit(outcome.message)
        refresh()
    }

    private fun act(call: suspend () -> ScheduledActions.Outcome) = viewModelScope.launch {
        _messages.tryEmit(call().message)
        refresh()
    }

    private companion object {
        const val LOG = "queue"
    }
}
