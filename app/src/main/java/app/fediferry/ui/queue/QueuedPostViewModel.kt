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
import app.fediferry.R
import app.fediferry.di.ServiceLocator
import app.fediferry.mastodon.MastodonException
import app.fediferry.work.ScheduleFormat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** One queued post, read from the server whenever its screen opens. */
class QueuedPostViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface Load {
        data object Loading : Load
        data class Loaded(val post: QueuedPost) : Load
        /** No longer queued: posted already, or cancelled elsewhere. */
        data object Gone : Load
        data class Failed(val reason: String) : Load
    }

    private val accounts = ServiceLocator.database(app).accounts()
    private val tokens = ServiceLocator.tokens(app)
    private val client = ServiceLocator.mastodon(app)
    private val actions = ScheduledActions(app)

    private val _load = MutableStateFlow<Load>(Load.Loading)
    val load: StateFlow<Load> = _load

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    /** Fires once the post has left the queue, so its screen can close. */
    private val _closed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val closed: SharedFlow<Unit> = _closed

    private var accountId = ""
    private var statusId = ""

    fun load(accountId: String, statusId: String) {
        this.accountId = accountId
        this.statusId = statusId
        reload()
    }

    private fun reload() = viewModelScope.launch {
        val app = getApplication<Application>()
        _load.value = runCatching {
            val account = accounts.byId(accountId) ?: return@runCatching Load.Gone
            val token = tokens.get(account.id) ?: throw MastodonException(app.getString(R.string.queue_no_token))
            val status = client.scheduledStatus(account.instance, token, statusId)
            Load.Loaded(QueuedPost(account, status, ScheduleFormat.parse(status.scheduledAt) ?: 0L))
        }.getOrElse { error ->
            if ((error as? MastodonException)?.code == 404) {
                Load.Gone
            } else {
                Load.Failed(error.message ?: app.getString(R.string.queue_unreachable))
            }
        }
    }

    fun reschedule(post: QueuedPost, at: Long) = act { actions.reschedule(post, at) }

    fun cancel(post: QueuedPost) = act { actions.cancel(post) }

    fun makeDraft(post: QueuedPost, cancelAfter: Boolean) = act { actions.makeDraft(post, cancelAfter) }

    private fun act(call: suspend () -> ScheduledActions.Outcome) = viewModelScope.launch {
        _busy.value = true
        val outcome = call()
        _busy.value = false
        _messages.tryEmit(outcome.message)
        if (outcome.leftQueue) _closed.tryEmit(Unit) else reload()
    }
}
