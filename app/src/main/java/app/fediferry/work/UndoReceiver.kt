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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import app.fediferry.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cancels a post still inside its undo window and returns the item to the inbox
 * as a draft. The work request is cancelled by its unique name, so a worker that
 * has already started sending is not interrupted mid-flight.
 */
class UndoReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_UNDO) return
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: return
        val appContext = context.applicationContext
        val pending = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                PostScheduler.cancel(appContext, itemId)
                ServiceLocator.items(appContext).markDraft(itemId)
                Notifications.cancel(appContext, itemId)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Post cancelled — kept as a draft", Toast.LENGTH_SHORT)
                        .show()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_UNDO = "app.fediferry.action.UNDO"
        const val EXTRA_ITEM_ID = "app.fediferry.extra.ITEM_ID"
    }
}
