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
package app.fediferry.library

import app.fediferry.api.LibraryItemPatch
import app.fediferry.api.FolderInput
import app.fediferry.api.CreatePost
import app.fediferry.api.IngestModes
import app.fediferry.api.PlanModes
import app.fediferry.api.PlanRequest
import app.fediferry.api.Violation
import app.fediferry.R
import app.fediferry.work.Notifications
import app.fediferry.client.ServerClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.fediferry.client.ServerException
import app.fediferry.di.ServiceLocator
import app.fediferry.log.DebugLog
import java.util.concurrent.TimeUnit

/**
 * Sends waiting shares to the server, oldest first, whenever there is a
 * network. A share leaves the phone only once the server has confirmed it;
 * sending one twice is harmless, because the server knows its id.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val pending = ServiceLocator.pendingShares(app)
        val connections = ServiceLocator.serverConnections(app)
        val connection = connections.current() ?: return Result.success()
        val client = connections.client(connection)

        for (share in pending.all.value) {
            try {
                val draft = send(app, client, share)
                // Shared with "Post now" while offline: it goes now that it is up.
                // The undo window has long passed, so there is none.
                if (draft != null && share.mode == IngestModes.POST_NOW) {
                    if (postNow(client, draft, delaySeconds = 0) is PostNowOutcome.NotReady) {
                        Notifications.showResult(app, draft, app.getString(R.string.post_now_not_ready_title), app.getString(R.string.post_now_not_ready_text))
                    }
                }
            } catch (e: ServerException) {
                // No server, or a passing fault: try again later. Anything else
                // (a revoked token, a refused file) needs a person, not a retry.
                if (e.unreachable || e.status >= 500 || e.status == 429) return Result.retry()
            }
        }
        return Result.success()
    }

    /** How a "Post now" went on the server. */
    sealed interface PostNowOutcome {
        /** In the queue; it goes after [delaySeconds], unless undone. */
        data class Queued(val postId: String, val delaySeconds: Int) : PostNowOutcome
        /** The channel would not take it as it is; it stays a draft. */
        data class NotReady(val postId: String, val violations: List<Violation>) : PostNowOutcome
    }

    companion object {
        private const val LOG = "upload"

        /**
         * Takes a fresh draft straight through: ready (checked against the
         * channel like any other) and into the queue for now plus [delaySeconds].
         */
        suspend fun postNow(client: ServerClient, postId: String, delaySeconds: Int): PostNowOutcome {
            val draft = client.post(postId)
            try {
                client.markReady(postId, draft.version)
            } catch (e: ServerException) {
                if (e.code == "post.not_ready") return PostNowOutcome.NotReady(postId, e.violations)
                throw e
            }
            client.plan(postId, PlanRequest(PlanModes.NOW, delaySeconds = delaySeconds))
            return PostNowOutcome.Queued(postId, delaySeconds)
        }

        /** One upload at a time, whether from here or straight from a share. */
        private val sending = Mutex()

        /**
         * Sends one waiting share and lets go of it once the server has it. A
         * Compose or Post-now share also starts a draft from what arrived; its id comes back.
         * Null when the share had already gone.
         */
        suspend fun send(context: Context, client: ServerClient, share: PendingShare): String? = sending.withLock {
            val pending = ServiceLocator.pendingShares(context)
            if (!pending.has(share.id)) return null
            try {
                val file = pending.file(share).takeIf { share.hasFile && it.exists() }?.readBytes()
                val result = client.ingest(share.id, share.mode, share.link, share.text, file, share.mime, share.capturedAt)
                share.folder?.let { name ->
                    val folder = client.folders().firstOrNull { it.name.equals(name, ignoreCase = true) }
                        ?: client.createFolder(FolderInput(name))
                    client.patchLibraryItem(result.item.id, LibraryItemPatch(folderId = folder.id))
                }
                // The share stays waiting until its draft exists too, so a
                // failure here starts the draft on the next try.
                val draft = if (share.mode == IngestModes.COMPOSE || share.mode == IngestModes.POST_NOW) {
                    client.createPost(CreatePost(listOf(result.item.id))).id
                } else {
                    null
                }
                pending.remove(share)
                DebugLog.d(LOG, "Uploaded a share")
                draft
            } catch (e: ServerException) {
                pending.update(share.copy(attempts = share.attempts + 1, lastError = e.code))
                DebugLog.w(LOG, "Upload failed: ${e.code}")
                throw e
            }
        }
        private const val NAME = "upload-shares"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // Appended, so a share arriving during an upload is not lost to a replace.
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
