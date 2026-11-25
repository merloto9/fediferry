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
package app.fediferry.phone

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.fediferry.di.ServiceLocator
import java.util.concurrent.TimeUnit

/**
 * Once a day, notes each channel's follower count for Analyze, so growth
 * shows even on days the app is not opened. Only for the phone's own
 * project: with a server, the server keeps the counts.
 */
class FollowerCountWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (ServiceLocator.serverConnections(applicationContext).current() != null) return Result.success()
        // A count that cannot be read today is simply missing; nothing to retry.
        runCatching { ServiceLocator.phone(applicationContext).recordFollowersToday() }
        return Result.success()
    }

    companion object {
        private const val NAME = "follower-counts"

        /** Idempotent: an already scheduled run keeps its time. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<FollowerCountWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
