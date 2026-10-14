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
package app.fediferry.backend

import app.fediferry.api.DeriveRequest
import app.fediferry.api.PlanRequest
import app.fediferry.api.PostPatch
import app.fediferry.api.ReviewFolderInput
import app.fediferry.api.ReviewPatch
import app.fediferry.api.ScheduleInput
import app.fediferry.client.ServerClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A project on a FediFerry server: every call goes to the server, and [changes] follows its change feed. */
class ServerBackend(private val client: ServerClient, override val deviceId: String) : PostBackend {

    override fun changes(): Flow<Set<String>> = flow {
        var rev = runCatching { client.project().rev }.getOrDefault(0L)
        while (true) {
            val changes = runCatching { client.changes(rev) }.getOrNull()
            if (changes == null) {
                delay(RETRY_MS)
                continue
            }
            if (changes.changed.isNotEmpty()) emit(changes.changed.map { it.type }.toSet())
            rev = changes.rev
        }
    }

    override suspend fun posts(vararg stages: String) = client.posts(*stages)
    override suspend fun post(id: String) = client.post(id)
    override suspend fun deletePost(id: String) = client.deletePost(id)

    override suspend fun lockPost(id: String, force: Boolean) = client.lockPost(id, force)
    override suspend fun unlockPost(id: String) = client.unlockPost(id)
    override suspend fun patchPost(id: String, version: Long, patch: PostPatch) = client.patchPost(id, version, patch)
    override suspend fun derive(assetId: String, request: DeriveRequest) = client.derive(assetId, request)
    override suspend fun suggestAlt(postId: String, position: Int) = client.suggestAlt(postId, position)

    override suspend fun markReady(id: String, version: Long) = client.markReady(id, version)
    override suspend fun backToDraft(id: String) = client.backToDraft(id)
    override suspend fun sortForReview(id: String, patch: ReviewPatch) = client.sortForReview(id, patch)
    override suspend fun reviewFolders() = client.reviewFolders()
    override suspend fun createReviewFolder(input: ReviewFolderInput) = client.createReviewFolder(input)
    override suspend fun updateReviewFolder(id: String, input: ReviewFolderInput) = client.updateReviewFolder(id, input)
    override suspend fun deleteReviewFolder(id: String) = client.deleteReviewFolder(id)
    override suspend fun labels() = client.labels()

    override suspend fun plan(id: String, request: PlanRequest) = client.plan(id, request)
    override suspend fun unplan(id: String) = client.unplan(id)
    override suspend fun schedules() = client.schedules()
    override suspend fun createSchedule(input: ScheduleInput) = client.createSchedule(input)
    override suspend fun updateSchedule(id: String, input: ScheduleInput) = client.updateSchedule(id, input)
    override suspend fun deleteSchedule(id: String) = client.deleteSchedule(id)
    override suspend fun published(limit: Int) = client.published(limit)

    override suspend fun channels() = client.channels()
    override fun mediaUrl(assetId: String) = client.mediaUrl(assetId)
    override fun thumbnailUrl(assetId: String) = client.thumbnailUrl(assetId)

    private companion object {
        const val RETRY_MS = 5_000L
    }
}
