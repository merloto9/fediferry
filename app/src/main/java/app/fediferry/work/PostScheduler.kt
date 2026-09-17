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
package app.fediferry.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Enqueues [PostWorker] runs, one unique chain per item.
 *
 * The unique name is the item id, so re-queueing the same item replaces rather
 * than duplicates its work — and so the undo action has something to cancel by
 * name without needing to track a work id.
 */
object PostScheduler {

    private const val WORK_PREFIX = "post-"

    fun workName(itemId: String) = WORK_PREFIX + itemId

    /**
     * @param delayMillis the undo window, or a scheduled send time. The item is
     *   already persisted as QUEUED before this is called.
     */
    fun enqueue(context: Context, itemId: String, delayMillis: Long = 0) {
        val request = OneTimeWorkRequestBuilder<PostWorker>()
            .setInputData(Data.Builder().putString(PostWorker.KEY_ITEM_ID, itemId).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .apply { if (delayMillis > 0) setInitialDelay(delayMillis, TimeUnit.MILLISECONDS) }
            .addTag(TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(itemId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel(context: Context, itemId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(itemId))
    }

    const val TAG = "post"
}
