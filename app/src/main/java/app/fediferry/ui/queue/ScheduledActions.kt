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
import app.fediferry.R
import app.fediferry.di.ServiceLocator
import app.fediferry.mastodon.MastodonException
import app.fediferry.work.ScheduleWords
import java.time.Instant

/**
 * What can be done to a post Mastodon holds for later, shared by the Queue and
 * a queued post's own screen. Every call goes straight to the server; each
 * returns what to tell the user, and whether the post has left the queue.
 */
class ScheduledActions(private val app: Application) {

    data class Outcome(val message: String, val leftQueue: Boolean = false)

    private val tokens = ServiceLocator.tokens(app)
    private val client = ServiceLocator.mastodon(app)
    private val repo = ServiceLocator.items(app)

    private suspend fun token(post: QueuedPost): String =
        tokens.get(post.account.id) ?: throw MastodonException(app.getString(R.string.queue_no_token))

    suspend fun reschedule(post: QueuedPost, at: Long): Outcome = runCatching {
        client.reschedule(post.account.instance, token(post), post.status.id, Instant.ofEpochMilli(at).toString())
    }.fold(
        { Outcome(app.getString(R.string.queue_moved, ScheduleWords.whenText(app.resources, at))) },
        { Outcome(failed(it)) },
    )

    suspend fun cancel(post: QueuedPost): Outcome = runCatching {
        client.cancelScheduled(post.account.instance, token(post), post.status.id)
    }.fold(
        { Outcome(app.getString(R.string.queue_cancelled), leftQueue = true) },
        { Outcome(failed(it)) },
    )

    /**
     * Copies the post into a new inbox draft. With [cancelAfter] the scheduled
     * post is cancelled too — only once the draft is safely made, so nothing
     * is lost if the copy fails.
     */
    suspend fun makeDraft(post: QueuedPost, cancelAfter: Boolean): Outcome {
        val draft = repo.draftFromScheduled(post.account.id, post.status)
        draft.exceptionOrNull()?.let { return Outcome(app.getString(R.string.queue_draft_failed, it.message.orEmpty())) }
        if (!cancelAfter) return Outcome(app.getString(R.string.queue_draft_made_kept))
        return runCatching { client.cancelScheduled(post.account.instance, token(post), post.status.id) }.fold(
            { Outcome(app.getString(R.string.queue_draft_made_cancelled), leftQueue = true) },
            { Outcome(app.getString(R.string.queue_draft_made_not_cancelled, it.message.orEmpty())) },
        )
    }

    private fun failed(error: Throwable) = error.message ?: app.getString(R.string.queue_failed)
}
