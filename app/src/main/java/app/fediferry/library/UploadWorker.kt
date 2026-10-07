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
                val file = pending.file(share).takeIf { share.hasFile && it.exists() }?.readBytes()
                client.ingest(share.id, share.mode, share.link, share.text, file, share.mime, share.capturedAt)
                pending.remove(share)
                DebugLog.d(LOG, "Uploaded a share")
            } catch (e: ServerException) {
                pending.update(share.copy(attempts = share.attempts + 1, lastError = e.code))
                DebugLog.w(LOG, "Upload failed: ${e.code}")
                // No server, or a passing fault: try again later. Anything else
                // (a revoked token, a refused file) needs a person, not a retry.
                if (e.unreachable || e.status >= 500 || e.status == 429) return Result.retry()
            }
        }
        return Result.success()
    }

    companion object {
        private const val LOG = "upload"
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
