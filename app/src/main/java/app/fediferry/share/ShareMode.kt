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

import androidx.annotation.StringRes
import app.fediferry.R

/**
 * Where the shared-item pipeline pauses. Every mode ingests and persists first;
 * they differ only in what happens next.
 */
enum class ShareMode(val shortcutId: String, @StringRes val labelRes: Int) {
    /** Template applied, enqueued immediately, held for the undo window. */
    POST_NOW("post_now", R.string.share_post_now),

    /** Editor opens prefilled; the user sends. */
    COMPOSE("compose", R.string.share_compose),

    /** Persisted as a draft and left in the inbox. */
    SAVE_FOR_LATER("save_later", R.string.share_save_later);

    companion object {
        const val EXTRA = "app.fediferry.extra.SHARE_MODE"

        fun fromShortcutId(id: String?): ShareMode? = entries.firstOrNull { it.shortcutId == id }

        fun fromName(name: String?): ShareMode? =
            entries.firstOrNull { it.name == name }
    }
}
