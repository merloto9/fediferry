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
package app.fediferry.data

import app.fediferry.data.model.Visibility
import app.fediferry.mastodon.MediaAttachment

/** How a post Mastodon holds for later maps back onto a draft. */
object ScheduledDraft {

    /** Mastodon's visibility word back to ours; null for anything unknown. */
    fun visibilityOf(api: String?): Visibility? = Visibility.entries.firstOrNull { it.api == api }

    /**
     * The attachment's media type. Mastodon stores animations ("gifv") as
     * MP4; otherwise the file's extension says it.
     */
    fun mimeTypeOf(attachment: MediaAttachment): String {
        val path = attachment.url.orEmpty().substringBefore('?').lowercase()
        return when {
            attachment.type == "gifv" || attachment.type == "video" || path.endsWith(".mp4") -> "video/mp4"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".webp") -> "image/webp"
            else -> "image/jpeg"
        }
    }
}
