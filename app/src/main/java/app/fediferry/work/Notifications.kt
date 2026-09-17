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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.fediferry.MainActivity
import app.fediferry.R

object Notifications {

    const val CHANNEL_PENDING = "pending_posts"
    const val CHANNEL_RESULT = "post_results"

    fun ensureChannels(context: Context) {
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PENDING,
                "Pending posts",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "The undo window before a post is sent" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULT,
                "Post results",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Confirmations and failures" },
        )
    }

    /** The undo window. Cancelled by the worker as soon as it starts sending. */
    fun showUndo(context: Context, itemId: String, seconds: Int) {
        val undo = PendingIntent.getBroadcast(
            context,
            itemId.hashCode(),
            Intent(context, UndoReceiver::class.java)
                .setAction(UndoReceiver.ACTION_UNDO)
                .putExtra(UndoReceiver.EXTRA_ITEM_ID, itemId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_PENDING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Posting to Mastodon")
            .setContentText("Sending in ${seconds}s")
            .setTimeoutAfter(seconds * 1000L + 500)
            .setOngoing(false)
            .addAction(0, "Undo", undo)
            .setContentIntent(openApp(context))
            .build()

        notify(context, itemId.hashCode(), notification)
    }

    fun showResult(context: Context, itemId: String, title: String, text: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        notify(context, itemId.hashCode(), notification)
    }

    fun cancel(context: Context, itemId: String) =
        NotificationManagerCompat.from(context).cancel(itemId.hashCode())

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notify(context: Context, id: Int, notification: android.app.Notification) {
        val manager = NotificationManagerCompat.from(context)
        // POST_NOTIFICATIONS may be denied on 33+. The post still goes out; the
        // user simply loses the undo affordance, so this is not an error path.
        if (manager.areNotificationsEnabled()) {
            runCatching { manager.notify(id, notification) }
        }
    }
}
