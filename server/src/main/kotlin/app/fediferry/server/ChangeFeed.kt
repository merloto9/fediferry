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
package app.fediferry.server

import app.fediferry.api.Change
import app.fediferry.api.Changes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Numbers every change in a project and lets clients wait for the next one.
 *
 * A change is written in the same transaction as the data it describes, so a
 * client that reads the feed never sees a change before the data is there.
 * Waiting clients are woken only after that transaction commits.
 */
class ChangeFeed(private val storage: Storage, private val clock: () -> Long = System::currentTimeMillis) {

    private val db get() = storage.db
    private val latest = ConcurrentHashMap<String, MutableStateFlow<Long>>()

    private fun flowOf(projectId: String): MutableStateFlow<Long> =
        latest.getOrPut(projectId) { MutableStateFlow(currentRev(projectId)) }

    fun currentRev(projectId: String): Long = db.projectQueries.rev(projectId).executeAsOneOrNull() ?: 0L

    /**
     * Runs [block] and records a change of [type] [id] with it, atomically.
     * Returns what [block] returned.
     */
    fun <T> change(projectId: String, type: String, id: String, deleted: Boolean = false, block: () -> T): T =
        db.transactionWithResult {
            val result = block()
            db.projectQueries.bumpRev(projectId)
            val rev = db.projectQueries.rev(projectId).executeAsOne()
            db.changeLogQueries.insert(projectId, rev, type, id, if (deleted) 1L else 0L, clock())
            afterCommit { flowOf(projectId).value = rev }
            result
        }

    fun since(projectId: String, since: Long, limit: Long = 500): Changes {
        val rows = db.changeLogQueries.since(projectId, since, limit).executeAsList()
        // With more than [limit] changes waiting, the client continues from the last one it got.
        val rev = rows.lastOrNull()?.rev ?: currentRev(projectId)
        return Changes(rev, rows.map { Change(it.rev, it.type, it.entity_id, it.deleted != 0L) })
    }

    /** Like [since], but waits up to [waitMillis] for something newer than [since] first. */
    suspend fun await(projectId: String, since: Long, waitMillis: Long): Changes {
        if (waitMillis > 0) withTimeoutOrNull(waitMillis) { flowOf(projectId).first { it > since } }
        return since(projectId, since)
    }
}
