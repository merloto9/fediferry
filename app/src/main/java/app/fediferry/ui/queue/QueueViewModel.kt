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
import app.fediferry.work.ScheduleFormat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant

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
        val token = tokens.get(account.id) ?: throw MastodonException("No access token", code = 401)
        client.scheduledStatuses(account.instance, token).map { status ->
            QueuedPost(account, status, ScheduleFormat.parse(status.scheduledAt) ?: 0L)
        }
    }

    private fun problemOf(error: Throwable): QueueProblem {
        val code = (error as? MastodonException)?.code
        if (code == 401 || code == 403) return QueueProblem.NeedsReconnect
        DebugLog.w(LOG, "Could not read the scheduled posts", error)
        return QueueProblem.Failed(error.message ?: "Could not reach the server")
    }

    /** Signs in again, asking for the permission to read scheduled posts. */
    fun reconnect(account: Account) = viewModelScope.launch {
        runCatching { auth.beginAuthorization(account.instance) }
            .onFailure { _messages.tryEmit(it.message ?: "Could not start the sign-in") }
    }

    fun reschedule(post: QueuedPost, at: Long) = change(post, "Moved to ${ScheduleFormat.whenText(at)}") { token ->
        client.reschedule(post.account.instance, token, post.status.id, Instant.ofEpochMilli(at).toString())
    }

    fun cancel(post: QueuedPost) = change(post, "Cancelled. Mastodon won't post it.") { token ->
        client.cancelScheduled(post.account.instance, token, post.status.id)
    }

    private fun change(post: QueuedPost, done: String, call: suspend (String) -> Unit) = viewModelScope.launch {
        val result = runCatching {
            val token = tokens.get(post.account.id) ?: throw MastodonException("No access token — reconnect")
            call(token)
        }
        _messages.tryEmit(result.fold({ done }, { it.message ?: "That didn't work" }))
        refresh()
    }

    private companion object {
        const val LOG = "queue"
    }
}
