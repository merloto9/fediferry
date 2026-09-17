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
package app.fediferry.share

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.fediferry.R

/**
 * Publishes the three modes as Sharing Shortcuts so the workflow is picked in
 * the share sheet's direct-share row rather than in settings.
 *
 * Each shortcut carries the category declared by [ShareReceiverActivity]'s
 * intent filter; that match is what makes Android surface them for a share.
 */
object ShortcutPublisher {

    private const val CATEGORY_SHARE = "app.fediferry.category.SHARE_TARGET"

    fun publish(context: Context) {
        val shortcuts = ShareMode.entries.mapIndexed { index, mode ->
            val label = context.getString(mode.labelRes)
            ShortcutInfoCompat.Builder(context, mode.shortcutId)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(IconCompat.createWithResource(context, iconFor(mode)))
                .setCategories(setOf(CATEGORY_SHARE))
                .setLongLived(true)
                .setRank(index)
                .setIntent(
                    Intent(context, ShareReceiverActivity::class.java)
                        .setAction(Intent.ACTION_SEND)
                        .putExtra(ShareMode.EXTRA, mode.name),
                )
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    private fun iconFor(mode: ShareMode) = when (mode) {
        ShareMode.POST_NOW -> R.drawable.ic_shortcut_post_now
        ShareMode.COMPOSE -> R.drawable.ic_shortcut_compose
        ShareMode.SAVE_FOR_LATER -> R.drawable.ic_shortcut_save
    }
}
