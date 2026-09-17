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
package app.fediferry

import android.app.Application
import app.fediferry.di.ServiceLocator
import app.fediferry.share.ShortcutPublisher
import app.fediferry.work.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FediFerryApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        ShortcutPublisher.publish(this)

        scope.launch {
            val repo = ServiceLocator.items(this@FediFerryApp)
            repo.seedIfEmpty()
            // Anything left POSTING is a survivor of a killed process, not a
            // running send; put it back in the queue.
            repo.recoverStalePosting()
            val days = ServiceLocator.settings(this@FediFerryApp).current().purgePostedAfterDays
            repo.purgePosted(days)
        }
    }
}
